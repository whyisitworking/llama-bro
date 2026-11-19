// lb-run: sends a request file through the C API and writes the same files as
// research/p0/scripts/http-run.py (OUT/<id>.jsonl with {"t_us", "data"} lines, OUT/summary.jsonl), so the
// contract test can compare it with upstream llama-server.
//
// usage: lb-run CONFIG_JSON REQUESTS.jsonl OUT_DIR
//   CONFIG_JSON: the lb_model_load config. Backends load from this executable's directory.
//   LB_RUN_BACKENDS (env, optional): the runtime config's "backends" array, e.g.
//     [{"name":"hexagon","files":["libggml-hexagon.so"],"env":{"GGML_HEXAGON_LIBHTP_DIR":"/data/local/tmp/x"}}]
//   LB_RUN_LOG_LEVEL (env, optional): the runtime's log_level (debug shows where each layer went)
//   REQUESTS.jsonl: {"id": "...", "endpoint": "chat", "body": {...}} per line (research/p0 format), plus
//     "attachments": [{"name", "mime", "path", "via": "path" | "fd" | "bytes"}] sent with lb_model_request_with:
//     the file by its path, an open descriptor (closed once the call returns) or its bytes (D84).
//   endpoint "realtime" (D97): "steps" instead of a body, run in order, then the session is closed (lb_request_cancel):
//     {"send": EVENT} sends a client event; {"wait": "TYPE"} waits for the next server event of that type;
//     {"append_clip": PATH, "chunk_ms": N} sends the WAV's PCM16 as input_audio_buffer.append events of N ms.
//     Each server event is one line of OUT/<id>.jsonl, as http-run.py writes them from a WebSocket.
#include "llamabro.h"
#include "lb_common.h"

#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iterator>
#include <mutex>
#include <string>
#include <vector>

#include <fcntl.h>
#include <unistd.h>

namespace {

int64_t now_us() {
    return std::chrono::duration_cast<std::chrono::microseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
}

struct run_state {
    std::mutex mtx;
    std::condition_variable cv;
    std::ofstream * out = nullptr;
    int64_t t0 = 0, t_first = 0;
    int n_chunks = 0;
    int status = 0;
    bool done = false;
    bool cancelled = false;
    int cancel_after = 0;
    std::vector<std::string> events;  // realtime: the server's events, in order
};

void on_event(void * user, lb_event ev, int status, const char * data, size_t len) {
    auto * s = static_cast<run_state *>(user);
    std::lock_guard<std::mutex> lk(s->mtx);
    if (ev == LB_EVENT_CHUNK) {
        const int64_t t = now_us();
        if (s->n_chunks == 0) s->t_first = t;
        s->n_chunks++;
        s->status = status;
        s->cv.notify_all();
        json line = json::object();
        line["t_us"] = t - s->t0;
        line["data"] = std::string(data, len);
        *s->out << line.dump() << "\n";
        s->events.emplace_back(data, len);
    } else {
        s->done = true;
        s->cancelled = ev == LB_EVENT_CANCELLED;
        s->cv.notify_all();
    }
}

std::string exe_dir(const char * argv0) {
    const std::string p(argv0);
    const size_t slash = p.find_last_of('/');
    return slash == std::string::npos ? "." : p.substr(0, slash);
}

std::string base64_of(const std::string & in) {
    static const char * abc = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    std::string out;
    out.reserve((in.size() + 2) / 3 * 4);
    for (size_t i = 0; i < in.size(); i += 3) {
        const uint32_t n = (uint8_t) in[i] << 16 | (i + 1 < in.size() ? (uint8_t) in[i + 1] << 8 : 0) |
                           (i + 2 < in.size() ? (uint8_t) in[i + 2] : 0);
        out += abc[n >> 18 & 63];
        out += abc[n >> 12 & 63];
        out += i + 1 < in.size() ? abc[n >> 6 & 63] : '=';
        out += i + 2 < in.size() ? abc[n & 63] : '=';
    }
    return out;
}

// the PCM16 samples of a WAV file (its "data" chunk) and its sample rate
bool wav_pcm(const std::string & path, std::string & pcm, int & rate) {
    std::ifstream f(path, std::ios::binary);
    const std::string all((std::istreambuf_iterator<char>(f)), std::istreambuf_iterator<char>());
    if (all.size() < 12 || all.compare(0, 4, "RIFF") != 0 || all.compare(8, 4, "WAVE") != 0) return false;
    auto u32 = [&](size_t at) { uint32_t v; std::memcpy(&v, all.data() + at, 4); return v; };
    for (size_t at = 12; at + 8 <= all.size();) {
        const std::string id = all.substr(at, 4);
        const size_t n = u32(at + 4);
        if (id == "fmt " && at + 16 <= all.size()) rate = (int) u32(at + 12);
        if (id == "data") {
            pcm = all.substr(at + 8, std::min(n, all.size() - at - 8));
            return true;
        }
        at += 8 + n + (n & 1);
    }
    return false;
}

// runs a realtime spec's steps on an open session; false (with a message) when a step fails
bool run_steps(lb_request * req, run_state & s, const json & steps, std::string & error) {
    size_t seen = 0;  // server events already matched by a wait
    for (const auto & step : steps) {
        if (step.contains("send")) {
            const std::string msg = step.at("send").dump();
            if (lb_request_send(req, msg.data(), msg.size()) != LB_OK) { error = "send failed"; return false; }
        } else if (step.contains("append_clip")) {
            std::string pcm;
            int rate = 0;
            if (!wav_pcm(step.at("append_clip").get<std::string>(), pcm, rate)) { error = "not a WAV file"; return false; }
            const size_t chunk = (size_t) rate * 2 * step.value("chunk_ms", 100) / 1000;
            for (size_t at = 0; at < pcm.size(); at += chunk) {
                const std::string msg = json{{"type", "input_audio_buffer.append"},
                                             {"audio", base64_of(pcm.substr(at, chunk))}}.dump();
                if (lb_request_send(req, msg.data(), msg.size()) != LB_OK) { error = "send failed"; return false; }
            }
        } else if (step.contains("wait")) {
            const std::string type = step.at("wait").get<std::string>();
            std::unique_lock<std::mutex> lk(s.mtx);
            bool found = false;
            const bool ok = s.cv.wait_for(lk, std::chrono::seconds(300), [&] {
                for (; seen < s.events.size() && !found; seen++) {
                    found = json::parse(s.events[seen]).value("type", "") == type;
                }
                return found || s.done;
            });
            if (!ok || !found) { error = "no " + type + " event"; return false; }
        }
    }
    return true;
}

}  // namespace

int main(int argc, char ** argv) {
    if (argc != 4) {
        std::fprintf(stderr, "usage: %s CONFIG_JSON REQUESTS.jsonl OUT_DIR\n", argv[0]);
        return 1;
    }
    const std::string out_dir = argv[3];

    char * err = nullptr;
    json rt = json::object();
    rt["backend_dir"] = exe_dir(argv[0]);
    // optional backends, as the app passes them (the device contract on the phone)
    if (const char * b = std::getenv("LB_RUN_BACKENDS"); b && *b) rt["backends"] = json::parse(b);
    if (const char * l = std::getenv("LB_RUN_LOG_LEVEL"); l && *l) rt["log_level"] = l;
    if (lb_runtime_init(rt.dump().c_str(), &err) != LB_OK) {
        std::fprintf(stderr, "lb_runtime_init: %s\n", err ? err : "");
        return 1;
    }
    lb_model * model = nullptr;
    if (lb_model_load(argv[1], nullptr, nullptr, &model, &err) != LB_OK) {
        std::fprintf(stderr, "lb_model_load: %s\n", err ? err : "");
        return 1;
    }

    std::ifstream in(argv[2]);
    std::ofstream summary(out_dir + "/summary.jsonl");
    std::string line;
    int rc = 0;
    while (std::getline(in, line)) {
        if (line.empty() || line[0] == '#') continue;
        const json spec = json::parse(line);
        const std::string id = spec.at("id").get<std::string>();
        const std::string endpoint = spec.at("endpoint").get<std::string>();
        if (spec.contains("cancel_after_ms")) {
            std::fprintf(stderr, "%s: cancel_after_ms is not supported by lb-run\n", id.c_str());
            rc = 1;
            continue;
        }
        // body_raw is sent as is (malformed-JSON cases); a missing body is an empty one (GET endpoints)
        const std::string body = spec.contains("body_raw") ? spec.at("body_raw").get<std::string>()
                               : spec.contains("body") && !spec.at("body").is_null() ? spec.at("body").dump()
                               : std::string();
        const int cancel_after = spec.contains("cancel_after_chunks") ? spec.at("cancel_after_chunks").get<int>() : 0;

        std::ofstream out(out_dir + "/" + id + ".jsonl");
        run_state s;
        s.out = &out;
        s.cancel_after = cancel_after;
        s.t0 = now_us();
        // attachments: the caller's side of each source is released as soon as the call returns
        const json atts_spec = spec.value("attachments", json::array());
        std::vector<std::string> strings;
        strings.reserve(atts_spec.size() * 3);
        std::vector<std::vector<uint8_t>> buffers;
        buffers.reserve(atts_spec.size());
        std::vector<int> fds;
        std::vector<lb_attachment> atts;
        for (const auto & a : atts_spec) {
            const std::string & name = strings.emplace_back(a.at("name").get<std::string>());
            const std::string & mime = strings.emplace_back(a.at("mime").get<std::string>());
            const std::string & path = strings.emplace_back(a.at("path").get<std::string>());
            const std::string via = a.value("via", "path");
            lb_attachment att{ name.c_str(), mime.c_str(), nullptr, -1, nullptr, 0 };
            if (via == "fd") {
                att.fd = open(path.c_str(), O_RDONLY);
                fds.push_back(att.fd);
            } else if (via == "bytes") {
                std::ifstream f(path, std::ios::binary);
                auto & b = buffers.emplace_back((std::istreambuf_iterator<char>(f)), std::istreambuf_iterator<char>());
                att.data = b.data();
                att.len = b.size();
            } else {
                att.path = path.c_str();
            }
            atts.push_back(att);
        }
        lb_request * req = nullptr;
        const lb_status st = lb_model_request_with(model, endpoint.c_str(), nullptr, body.data(), body.size(),
                                                   atts.data(), atts.size(), on_event, &s, &req);
        for (const int fd : fds) if (fd >= 0) close(fd);
        buffers.clear();
        if (st != LB_OK) {
            std::fprintf(stderr, "%s: lb_model_request failed\n", id.c_str());
            rc = 1;
            continue;
        }
        if (endpoint == "realtime") {
            std::string error;
            if (!run_steps(req, s, spec.at("steps"), error)) {
                std::fprintf(stderr, "%s: %s\n", id.c_str(), error.c_str());
                rc = 1;
            }
            lb_request_cancel(req);  // the client closes the socket
        }
        {
            std::unique_lock<std::mutex> lk(s.mtx);
            // like http-run.py: after cancel_after_chunks chunks, stop the request
            s.cv.wait(lk, [&] { return s.done || (s.cancel_after > 0 && s.n_chunks >= s.cancel_after); });
            if (!s.done) {
                lb_request_cancel(req);
                s.cv.wait(lk, [&] { return s.done; });
            }
        }
        lb_request_free(req);
        const int64_t t_returned = now_us();

        json row = json::object();
        row["id"] = id;
        row["endpoint"] = endpoint;
        row["status"] = s.status;
        row["n_chunks"] = s.n_chunks;
        row["t_first_us"] = s.t_first ? s.t_first - s.t0 : -1;
        row["t_returned_us"] = t_returned - s.t0;
        summary << row.dump() << "\n";
        summary.flush();
        std::fprintf(stderr, "%s: %d chunks, status %d\n", id.c_str(), s.n_chunks, s.status);
    }

    lb_model_free(model);
    return rc;
}
