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
#include "model.h"

#include <atomic>
#include <cstring>
#include <functional>

struct lb_request {
    std::atomic<int> refs{2};          // caller + thread
    std::atomic<bool> stop{false};
    std::function<bool()> should_stop; // server_http_req keeps a reference to this
    lb_request_cb cb = nullptr;
    void * user = nullptr;
};

namespace {

void release(lb_request * r) {
    if (r->refs.fetch_sub(1) == 1) delete r;
}

void run_request(lb_model * m, lb_request * r, server_http_context::handler_t handler,
                 std::map<std::string, std::string> params, std::string query, std::string path, std::string body) {
    bool cancelled = false;
    {
        // req must outlive the response: a stream keeps a pointer to it (server-stream.cpp:611-629)
        server_http_req req{ std::move(params), {}, std::move(path), std::move(query), std::move(body), {}, r->should_stop };
        server_http_res_ptr res = handler(req);  // ex_wrapper turns exceptions into an error response
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

}  // namespace

void lb_model_cancel_all_requests(lb_model * m) {
    std::unique_lock<std::mutex> lk(m->mtx);
    m->closing = true;
    for (lb_request * r : m->requests) r->stop.store(true);
    m->cv.wait(lk, [m] { return m->requests.empty(); });
}

extern "C" {

lb_status lb_model_request(lb_model * m, const char * endpoint, const char * params_json,
                           const char * body, size_t body_len, lb_request_cb cb, void * user,
                           lb_request ** out) {
    if (out) *out = nullptr;
    if (!m || !endpoint || !cb || !out) return LB_ERR_INVALID_ARG;

    const auto it = m->endpoints.find(endpoint);
    if (it == m->endpoints.end()) return LB_ERR_INVALID_ARG;

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
        std::thread(run_request, m, r, it->second, std::move(params), std::move(query),
                    std::string("/") + endpoint, std::string(body ? body : "", body ? body_len : 0)).detach();
    } catch (...) {
        std::lock_guard<std::mutex> lk(m->mtx);
        m->requests.erase(r);
        m->cv.notify_all();
        delete r;
        return LB_ERR_INTERNAL;
    }
    *out = r;
    return LB_OK;
}

void lb_request_cancel(lb_request * r) {
    if (r) r->stop.store(true);
}

void lb_request_free(lb_request * r) {
    if (r) release(r);
}

}  // extern "C"
