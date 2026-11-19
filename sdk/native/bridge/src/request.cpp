// lb_request_*: one llama-server route handler call per request (P1 T4, design §3.2).
//
// Each request runs on its own detached thread, as an HTTP worker thread does in llama-server. The
// thread builds a server_http_req, calls the ex_wrapper'd handler, pumps res->next() for streams and
// reports each chunk, then exactly one terminal event. The lb_request is ref-counted: one reference
// for the caller (dropped by lb_request_free) and one for the thread.
//
// Cancel sets the flag read by req.should_stop. The stream returns from next() at its next result or
// polling tick (server-queue.cpp server_response_reader::next, HTTP_POLLING_SECONDS); destroying the
// response then posts the cancel task to the server loop (server-queue.cpp server_response_reader::stop).
//
// Attachments (D84, F55) are read on the caller's thread, before the request's thread starts, so the caller may release
// its bytes or descriptor when the call returns. The request's thread owns the buffers: a transcription's audio moves
// into server_http_req::files, which upstream's handler reads as a multipart upload; for other routes each placeholder
// becomes a base64 data URL in the body. The buffers live no longer than the req, which ends before the terminal event.
// With a media_dir (llama-server's media root), a chat's or embedding's path attachment is not read: the bridge checks
// its first bytes, links it into that directory under a random name and writes file://<name>, so llama-server reads the
// file itself (F157: ~100 ms less per turn with three voice notes). The link lives until the terminal event, so the file
// must too. A caller-written file:// reaches only that directory.
//
// Realtime (D97): the "realtime" endpoint runs upstream's /v1/realtime WebSocket handler (server-context.cpp
// ws_realtime) on the request's thread with an in-memory connection: lb_request_send queues a client event, which the
// handler reads as a text message; each message it sends is one CHUNK. A cancel is the client closing the socket.
#include "model.h"
#include "upstream.h"

#include "base64.hpp"

#include <atomic>
#include <cerrno>
#include <condition_variable>
#include <deque>
#include <mutex>
#include <cstdlib>
#include <cstring>
#include <functional>
#include <iterator>
#include <random>
#include <stdexcept>
#include <vector>

#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

struct lb_request {
    std::atomic<int> refs{2};          // caller + thread
    std::atomic<bool> stop{false};
    std::function<bool()> should_stop; // server_http_req keeps a reference to this
    lb_request_cb cb = nullptr;
    void * user = nullptr;

    // realtime only: the client's events, read by upstream's handler through mem_conn
    bool realtime = false;
    std::mutex rt_mtx;
    std::condition_variable rt_cv;
    std::deque<std::string> inbox;
    bool rt_closed = false;     // the connection is closed, by either side
    bool server_ended = false;  // the handler closed it first: DONE, even if a cancel follows
    std::mutex cb_mtx;       // the handler sends from two threads (its reader and its result loop)

    void request_stop() {
        stop.store(true);
        std::lock_guard<std::mutex> lk(rt_mtx);
        rt_cv.notify_all();
    }
};

namespace {

constexpr const char * kPlaceholder = "lb-attachment:";
constexpr const char * kTranscriptions = "transcriptions";
constexpr const char * kRealtime = "realtime";

std::atomic<int> g_live_attachments{0};

// One attachment's bytes, read once. Counted while alive, for the tests that check a request frees them.
struct attachment {
    std::string name;
    std::string mime;
    std::string filename;  // a path's base name, as an upload would carry it
    std::string link;      // its name in the media root when linked (sent as file://), else empty
    raw_buffer data;

    attachment() { g_live_attachments++; }
    attachment(attachment && o) noexcept
        : name(std::move(o.name)), mime(std::move(o.mime)), filename(std::move(o.filename)), link(std::move(o.link)),
          data(std::move(o.data)) {
        g_live_attachments++;
    }
    attachment(const attachment &) = delete;
    attachment & operator=(const attachment &) = delete;
    attachment & operator=(attachment &&) = delete;
    ~attachment() { g_live_attachments--; }
};

// lb_read_fd's buffer, handed to the caller as is: malloc'd, so lb_buffer_free is free(), with no copy at the end
struct malloc_buffer {
    uint8_t * p = nullptr;
    size_t n = 0;
    ~malloc_buffer() { std::free(p); }
    uint8_t * data() { return p; }
    size_t size() const { return n; }
    void resize(size_t size) {
        auto * q = static_cast<uint8_t *>(std::realloc(p, size ? size : 1));
        if (!q) throw std::bad_alloc();
        p = q;
        n = size;
    }
    void clear() { resize(0); }
    void shrink_to_fit() {}
};

// Reads the whole file behind fd from offset 0 with pread, so the caller's offset is untouched, or with read when the
// descriptor can't seek (a pipe, a socket). Never closes fd. Returns 0, or the errno that stopped it.
template <typename Buffer>
int read_fd(int fd, Buffer & out) {
    struct stat st{};
    if (fstat(fd, &st) != 0) return errno;
    out.resize(S_ISREG(st.st_mode) && st.st_size > 0 ? (size_t) st.st_size + 1 : 64 * 1024);  // +1: EOF in one read
    size_t len = 0;
    bool seekable = true;
    while (true) {
        if (len == out.size()) out.resize(out.size() * 2);
        const ssize_t n = seekable ? pread(fd, out.data() + len, out.size() - len, (off_t) len)
                                   : read(fd, out.data() + len, out.size() - len);
        if (n < 0) {
            if (errno == EINTR) continue;
            if (seekable && errno == ESPIPE && len == 0) {
                seekable = false;
                continue;
            }
            const int e = errno;
            out.clear();
            return e;
        }
        if (n == 0) break;
        len += (size_t) n;
    }
    const size_t cap = out.size();
    out.resize(len);
    if (cap - len > len / 4) out.shrink_to_fit();  // a pipe's doubling; a file's one spare byte isn't worth a copy
    return 0;
}

std::string fd_error(int fd, int e) {
    return "cannot read descriptor " + std::to_string(fd) + ": " + std::strerror(e);
}

bool read_path(const char * path, raw_buffer & out, std::string & err) {
    const int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        err = std::string("cannot open ") + path + ": " + std::strerror(errno);
        return false;
    }
    const int e = read_fd(fd, out);
    close(fd);
    if (e != 0) err = std::string("cannot read ") + path + ": " + std::strerror(e);
    return e == 0;
}

// WAV, MP3 or FLAC by their first bytes: the formats llama.cpp decodes (b12479 tools/mtmd/mtmd-helper.cpp
// audio_helpers::is_audio_file); anything else it would try as an image. One addition, as Kotlin's Media.isAudio: an
// MPEG sync word must also be layer I-III, since an ADTS AAC header has the sync word with layer bits 00, passes
// upstream's check, then fails to decode.
bool is_audio_file(const char * buf, size_t len) {
    if (len < 12) return false;
    const bool is_wav = memcmp(buf, "RIFF", 4) == 0 && memcmp(buf + 8, "WAVE", 4) == 0;
    const bool is_mp3 = len >= 3 && (memcmp(buf, "ID3", 3) == 0 ||
                                     ((unsigned char) buf[0] == 0xFF && ((unsigned char) buf[1] & 0xE0) == 0xE0 &&
                                      ((unsigned char) buf[1] & 0x06) != 0));
    const bool is_flac = memcmp(buf, "fLaC", 4) == 0;
    return is_wav || is_mp3 || is_flac;
}

// The links a request made in the media root, removed when it ends (or never starts)
struct media_links {
    std::vector<std::string> paths;
    media_links() = default;
    media_links(media_links && o) noexcept : paths(std::move(o.paths)) {}
    media_links(const media_links &) = delete;
    media_links & operator=(const media_links &) = delete;
    media_links & operator=(media_links &&) = delete;
    ~media_links() { remove(); }
    void remove() {
        for (const auto & p : paths) unlink(p.c_str());
        paths.clear();
    }
};

// The first n bytes of a file, enough for is_audio_file
bool read_head(const char * path, raw_buffer & out, size_t n, std::string & err) {
    const int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        err = std::string("cannot open ") + path + ": " + std::strerror(errno);
        return false;
    }
    out.resize(n);
    ssize_t got;
    do {
        got = read(fd, out.data(), n);
    } while (got < 0 && errno == EINTR);
    const int e = errno;
    close(fd);
    if (got < 0) {
        err = std::string("cannot read ") + path + ": " + std::strerror(e);
        return false;
    }
    out.resize((size_t) got);
    return true;
}

// Links path into media_root under a random name with path's extension; returns the name, or empty if it can't
std::string link_into(const std::string & media_root, const char * path) {
    char * abs = realpath(path, nullptr);
    if (!abs) return "";
    const std::string target(abs);
    std::free(abs);
    static std::atomic<uint64_t> counter{0};
    std::random_device rd;
    char name[64];
    const char * dot = std::strrchr(path, '.');
    const char * slash = std::strrchr(path, '/');
    std::string ext = dot && (!slash || dot > slash) ? std::string(dot) : std::string(".bin");
    if (ext.size() > 8 || ext.find_first_of(":*?\"<>|/\\ ") != std::string::npos) ext = ".bin";
    // 128 random bits (D87: a caller-written file:// reaches only media_root, whose names can't be guessed), plus a
    // counter so two links never collide
    std::snprintf(name, sizeof(name), "%08x%08x%08x%08x%016llx", rd(), rd(), rd(), rd(),
                  (unsigned long long) counter.fetch_add(1));
    const std::string file = std::string(name) + ext;
    if (symlink(target.c_str(), (media_root + file).c_str()) != 0) return "";
    return file;
}

std::string data_url(const attachment & a) {
    std::string url = "data:" + a.mime + ";base64,";
    url.reserve(url.size() + base64::required_encode_size(a.data.size()));
    base64::encode(a.data.begin(), a.data.end(), std::back_inserter(url));
    return url;
}

// The routes whose body is a chat completion's (server-context.cpp: post_chat_completions, handle_count_tokens with
// TASK_RESPONSE_TYPE_OAI_CHAT, post_apply_template; each calls oaicompat_chat_params_parse on it). Only these get
// placeholders filled (D84); every other route's body goes to its handler as the caller wrote it.
bool takes_chat_messages(const std::string & path) {
    return path == "/chat" || path == "/count_tokens" || path == "/apply_template";
}

// One media field of a content part: replaced when its value is exactly "lb-attachment:<name>". Returns whether it was.
// Throws std::invalid_argument for a placeholder with no attachment of that name.
bool fill_field(json & part, const char * holder, const char * field, const std::vector<attachment> & atts) {
    if (!part.contains(holder) || !part.at(holder).is_object()) return false;
    json & obj = part.at(holder);
    if (!obj.contains(field) || !obj.at(field).is_string()) return false;
    const std::string s = obj.at(field).get<std::string>();
    if (s.rfind(kPlaceholder, 0) != 0) return false;
    const std::string name = s.substr(std::strlen(kPlaceholder));
    for (const auto & a : atts) {
        if (a.name == name) {
            obj[field] = a.link.empty() ? data_url(a) : "file://" + a.link;
            return true;
        }
    }
    throw std::invalid_argument("no attachment for the placeholder \"" + s + "\"");
}

// The embeddings routes (server-context.cpp handle_embeddings_impl, post_embeddings and post_embeddings_oai): their
// "input" (or "content") may hold OAI content parts, read by tokenize_oai_content_array. Filled too (D84).
bool takes_embedding_content(const std::string & path) {
    return path == "/embeddings" || path == "/embeddings_native";
}

// One content array's media parts: image_url.url and input_audio.data, the two fields media arrives in
// (server-common.cpp oaicompat_content_load_media). Returns whether anything was replaced.
bool fill_parts(json & content, const std::vector<attachment> & atts) {
    if (!content.is_array()) return false;
    bool replaced = false;
    for (auto & part : content) {
        if (!part.is_object()) continue;
        replaced = fill_field(part, "image_url", "url", atts) || replaced;
        replaced = fill_field(part, "input_audio", "data", atts) || replaced;
    }
    return replaced;
}

// Replaces each placeholder that is the whole value of a content part's image_url.url or input_audio.data with the
// attachment's data URL: in a chat body, the parts of messages[].content[]; in an embeddings body, the parts of
// {"content": [...]} entries in "input" (or "content", as handle_embeddings_impl reads it), one entry or an array of
// them. Nothing else in the body is looked at (a message's text, response_format, tools, a text input). The body is
// written again only when something was replaced.
void write_placeholders(std::string & body, const std::vector<attachment> & atts, bool embeddings) {
    if (body.find(kPlaceholder) == std::string::npos) return;
    json j = json::parse_no_throw(body);
    if (j.is_discarded() || !j.is_object()) return;
    bool replaced = false;
    if (!embeddings) {
        if (!j.contains("messages") || !j.at("messages").is_array()) return;
        for (auto & msg : j.at("messages")) {
            if (msg.is_object() && msg.contains("content")) replaced = fill_parts(msg.at("content"), atts) || replaced;
        }
    } else {
        const char * key = j.contains("input") ? "input" : "content";
        if (!j.contains(key)) return;
        json & prompt = j.at(key);
        auto entry = [&](json & e) {
            if (e.is_object() && e.contains("content")) replaced = fill_parts(e.at("content"), atts) || replaced;
        };
        if (prompt.is_array()) {
            for (auto & e : prompt) entry(e);
        } else {
            entry(prompt);
        }
    }
    if (replaced) body = j.dump_safe();
}

void release(lb_request * r) {
    if (r->refs.fetch_sub(1) == 1) delete r;
}

void run_request(lb_model * m, lb_request * r, server_http_context::handler_t handler,
                 std::map<std::string, std::string> params, std::string query, std::string path, std::string body,
                 std::vector<attachment> atts, std::string att_error, media_links links) {
    bool cancelled = false;
    {
        // the attachments become what upstream's handler reads: the upload's files for a transcription, data URLs in
        // the body for the rest. A bad attachment is a bad request, answered as ex_wrapper answers one (400)
        std::map<std::string, uploaded_file> files;
        const bool transcription = path == std::string("/") + kTranscriptions;
        auto prepare = [&](const server_http_req &) -> server_http_res_ptr {
            if (!att_error.empty()) throw std::invalid_argument(att_error);
            if (!transcription) {
                if (takes_chat_messages(path)) write_placeholders(body, atts, false);
                if (takes_embedding_content(path)) write_placeholders(body, atts, true);
                atts.clear();  // in the body now, or not wanted by this route
            }
            return nullptr;
        };
        const server_http_req no_req{ {}, {}, {}, {}, {}, {}, r->should_stop };
        server_http_res_ptr failed = ex_wrapper(prepare)(no_req);
        if (transcription && !failed) {
            for (auto & a : atts) files[a.name] = uploaded_file{ std::move(a.data), a.filename, a.mime };
        }

        // req must outlive the response: a stream keeps a pointer to it (server-stream.cpp:611-629)
        server_http_req req{ std::move(params), {}, std::move(path), std::move(query), std::move(body), std::move(files),
                             r->should_stop };
        // ex_wrapper turns exceptions into an error response
        server_http_res_ptr res = failed ? std::move(failed) : handler(req);
        const int status = res->status;

        if (res->is_stream()) {
            bool has_next = true;
            while (has_next) {
                std::string chunk;
                try {
                    has_next = res->next(chunk);
                } catch (const std::exception & e) {
                    SRV_ERR("llamabro: stream failed: %s\n", e.what());
                    has_next = false;
                }
                if (r->stop.load()) break;  // nothing reaches the caller after a cancel
                if (!chunk.empty()) r->cb(r->user, LB_EVENT_CHUNK, status, chunk.data(), chunk.size());
            }
        } else if (!r->stop.load()) {
            r->cb(r->user, LB_EVENT_CHUNK, status, res->data.data(), res->data.size());
        }
        cancelled = r->stop.load();  // requested before the stream ended

        // destroying the response is what sends the cancel to the server loop (server-queue.cpp:603)
        res->on_complete();
        res.reset();
        atts.clear();  // with req's files, freed at the end of this scope: before the terminal event
        links.remove();
    }

    r->cb(r->user, cancelled ? LB_EVENT_CANCELLED : LB_EVENT_DONE, 0, nullptr, 0);

    {
        std::lock_guard<std::mutex> lk(m->mtx);
        m->requests.erase(r);
        m->cv.notify_all();
    }
    // `m` may be freed from here on
    release(r);
}

// The WebSocket upstream's realtime handler talks to, in memory: read() takes what lb_request_send queued, send() is
// one CHUNK. Closed by either side: a cancel (the client) or the handler's close() (the server).
class mem_conn final : public server_http_context::ws_conn {
public:
    explicit mem_conn(lb_request * r) : r_(r) {}

    int read(std::string & msg) override {
        std::unique_lock<std::mutex> lk(r_->rt_mtx);
        r_->rt_cv.wait(lk, [this] { return !r_->inbox.empty() || r_->stop.load() || r_->rt_closed; });
        if (r_->stop.load() || r_->rt_closed) return 0;
        msg = std::move(r_->inbox.front());
        r_->inbox.pop_front();
        return 1;
    }
    bool send(const std::string & msg) override {
        std::lock_guard<std::mutex> lk(r_->cb_mtx);
        if (!is_open()) return false;  // nothing reaches the caller after a cancel
        r_->cb(r_->user, LB_EVENT_CHUNK, 200, msg.data(), msg.size());
        return true;
    }
    bool send_binary(const std::string & msg) override { return send(msg); }
    bool is_open() const override {
        std::lock_guard<std::mutex> lk(r_->rt_mtx);
        return !r_->stop.load() && !r_->rt_closed;
    }
    void close() override {
        std::lock_guard<std::mutex> lk(r_->rt_mtx);
        if (!r_->rt_closed && !r_->stop.load()) r_->server_ended = true;
        r_->rt_closed = true;
        r_->rt_cv.notify_all();
    }

private:
    lb_request * r_;
};

void run_realtime(lb_model * m, lb_request * r, server_http_context::ws_handler_t handler,
                  std::map<std::string, std::string> params, std::string query) {
    {
        mem_conn conn(r);
        // req must outlive the handler, as an HTTP worker's does
        const server_http_req req{ std::move(params), {}, "/v1/realtime", std::move(query), {}, {}, r->should_stop };
        try {
            handler(req, conn);  // returns once the conversation has ended, its threads joined
        } catch (const std::exception & e) {
            SRV_ERR("llamabro: realtime failed: %s\n", e.what());
        }
        conn.close();
    }
    bool cancelled;
    {
        std::lock_guard<std::mutex> lk(r->rt_mtx);
        cancelled = !r->server_ended;  // a cancel closed it before the handler did
    }
    r->cb(r->user, cancelled ? LB_EVENT_CANCELLED : LB_EVENT_DONE, 0, nullptr, 0);

    {
        std::lock_guard<std::mutex> lk(m->mtx);
        m->requests.erase(r);
        m->cv.notify_all();
    }
    release(r);
}

}  // namespace

void lb_model_cancel_all_requests(lb_model * m) {
    std::unique_lock<std::mutex> lk(m->mtx);
    m->closing = true;
    for (lb_request * r : m->requests) r->request_stop();
    m->cv.wait(lk, [m] { return m->requests.empty(); });
}

int lb_attachments_live() { return g_live_attachments.load(); }

extern "C" {

lb_status lb_model_request(lb_model * m, const char * endpoint, const char * params_json,
                           const char * body, size_t body_len, lb_request_cb cb, void * user,
                           lb_request ** out) {
    return lb_model_request_with(m, endpoint, params_json, body, body_len, nullptr, 0, cb, user, out);
}

lb_status lb_model_request_with(lb_model * m, const char * endpoint, const char * params_json,
                                const char * body, size_t body_len, const lb_attachment * atts, size_t n_atts,
                                lb_request_cb cb, void * user, lb_request ** out) {
    return lb_guard(LB_ERR_INTERNAL, nullptr, [&]() -> lb_status {
        if (out) *out = nullptr;
        if (!m || !endpoint || !cb || !out || (n_atts > 0 && !atts)) return LB_ERR_INVALID_ARG;

        const bool realtime = std::strcmp(endpoint, kRealtime) == 0;
        const auto it = m->endpoints.find(endpoint);
        if (realtime) {
            // a WebSocket: no body or attachments, the client's events come through lb_request_send
            if (body_len > 0 || n_atts > 0 || !m->routes || !m->routes->ws_realtime) return LB_ERR_INVALID_ARG;
        } else if (it == m->endpoints.end()) {
            return LB_ERR_INVALID_ARG;
        }
        const bool transcription = std::strcmp(endpoint, kTranscriptions) == 0;

        // the caller's mistakes first, then every read, all before this returns (the caller may release its sources)
        for (size_t i = 0; i < n_atts; i++) {
            const lb_attachment & a = atts[i];
            const int sources = (a.path ? 1 : 0) + (a.fd >= 0 ? 1 : 0) + (a.data ? 1 : 0);
            if (!a.name || !*a.name || sources != 1) return LB_ERR_INVALID_ARG;
            if (!transcription && (!a.mime || !*a.mime)) return LB_ERR_INVALID_ARG;  // a data URL needs its type
            for (size_t j = 0; j < i; j++) {
                if (std::strcmp(atts[j].name, a.name) == 0) return LB_ERR_INVALID_ARG;
            }
        }
        std::vector<attachment> read;
        std::string att_error;
        media_links links;
        // a chat's or embedding's path attachment is linked into the media root instead of read, when there is one
        const std::string route = std::string("/") + endpoint;
        const bool link_paths = !m->params.media_path.empty() && (takes_chat_messages(route) || takes_embedding_content(route));
        try {
            read.reserve(n_atts);
            for (size_t i = 0; i < n_atts && att_error.empty(); i++) {
                const lb_attachment & a = atts[i];
                attachment & b = read.emplace_back();
                b.name = a.name;
                b.mime = a.mime ? a.mime : "";
                std::string err;
                if (a.path) {
                    const char * slash = std::strrchr(a.path, '/');
                    b.filename = slash ? slash + 1 : a.path;
                    if (link_paths && read_head(a.path, b.data, 16, err)) {
                        b.link = link_into(m->params.media_path, a.path);
                        if (!b.link.empty()) links.paths.push_back(m->params.media_path + b.link);
                    }
                    if (!err.empty()) {
                        att_error = "attachment \"" + b.name + "\": " + err;
                    } else if (b.link.empty() && !read_path(a.path, b.data, err)) {
                        att_error = "attachment \"" + b.name + "\": " + err;
                    }
                } else if (a.fd >= 0) {
                    if (const int e = read_fd(a.fd, b.data)) att_error = "attachment \"" + b.name + "\": " + fd_error(a.fd, e);
                } else {
                    b.data.assign(a.data, a.data + a.len);
                }
                const bool audio = transcription || b.mime.rfind("audio/", 0) == 0;
                if (att_error.empty() && audio && !is_audio_file(reinterpret_cast<const char *>(b.data.data()), b.data.size())) {
                    att_error = "attachment \"" + b.name + "\" is not WAV, MP3 or FLAC audio, the formats llama.cpp decodes";
                }
            }
        } catch (const std::bad_alloc &) {
            att_error = "attachments too large to read into memory";
        }
        if (!att_error.empty()) read.clear();  // the request only reports the error

        std::map<std::string, std::string> params;
        std::string query;
        if (params_json && *params_json) {
            json p;
            try {
                p = json::parse(params_json);
            } catch (...) {
                return LB_ERR_INVALID_ARG;
            }
            if (!p.is_object()) return LB_ERR_INVALID_ARG;
            for (const auto & item : p.items()) {
                if (!item.value().is_string()) return LB_ERR_INVALID_ARG;
                params[item.key()] = item.value().get<std::string>();
                query += (query.empty() ? "" : "&") + item.key() + "=" + params[item.key()];
            }
        }

        auto * r = new lb_request();
        r->cb = cb;
        r->user = user;
        r->realtime = realtime;
        r->should_stop = [r]() { return r->stop.load(); };

        {
            std::lock_guard<std::mutex> lk(m->mtx);
            if (m->closing) {
                delete r;
                return LB_ERR_INVALID_ARG;
            }
            m->requests.insert(r);
        }
        try {
            if (realtime) {
                std::thread(run_realtime, m, r, m->routes->ws_realtime, std::move(params), std::move(query)).detach();
            } else {
                std::thread(run_request, m, r, it->second, std::move(params), std::move(query),
                            std::string("/") + endpoint, std::string(body ? body : "", body ? body_len : 0),
                            std::move(read), std::move(att_error), std::move(links)).detach();
            }
        } catch (...) {
            std::lock_guard<std::mutex> lk(m->mtx);
            m->requests.erase(r);
            m->cv.notify_all();
            delete r;
            return LB_ERR_INTERNAL;
        }
        *out = r;
        return LB_OK;
    });
}

void lb_request_cancel(lb_request * r) {
    if (r) r->request_stop();
}

lb_status lb_request_send(lb_request * r, const char * msg, size_t len) {
    return lb_guard(LB_ERR_INTERNAL, nullptr, [&]() -> lb_status {
        if (!r || !r->realtime || (!msg && len > 0)) return LB_ERR_INVALID_ARG;
        std::lock_guard<std::mutex> lk(r->rt_mtx);
        if (r->stop.load() || r->rt_closed) return LB_ERR_CANCELLED;
        r->inbox.emplace_back(msg ? msg : "", len);
        r->rt_cv.notify_all();
        return LB_OK;
    });
}

void lb_request_free(lb_request * r) {
    if (r) release(r);
}

lb_status lb_read_fd(int fd, uint8_t ** out, size_t * out_len, char ** error_json) {
    if (out) *out = nullptr;
    if (out_len) *out_len = 0;
    if (error_json) *error_json = nullptr;
    if (!out || !out_len) return LB_ERR_INVALID_ARG;
    malloc_buffer data;
    try {
        if (const int e = fd < 0 ? EBADF : read_fd(fd, data)) {
            lb_set_error(error_json, fd_error(fd, e));
            return LB_ERR_INVALID_ARG;
        }
        data.resize(data.size());  // never NULL, even for an empty file
    } catch (const std::bad_alloc &) {
        lb_set_error(error_json, "descriptor " + std::to_string(fd) + " is too large to read into memory");
        return LB_ERR_INVALID_ARG;
    }
    *out = data.p;
    *out_len = data.n;
    data.p = nullptr;  // the caller's now
    return LB_OK;
}

void lb_buffer_free(uint8_t * buf) { std::free(buf); }

}  // extern "C"
