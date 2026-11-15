// lb-run: sends a request file through the C API and writes the same files as
// research/p0/scripts/http-run.py (OUT/<id>.jsonl with {"t_us", "data"} lines, OUT/summary.jsonl), so the
// contract test can compare it with upstream llama-server (P1 T5).
//
// usage: lb-run CONFIG_JSON REQUESTS.jsonl OUT_DIR
//   CONFIG_JSON: the lb_model_load config. Backends load from this executable's directory.
//   LB_RUN_BACKENDS (env, optional): the runtime config's "backends" array, e.g.
//     [{"name":"hexagon","files":["libggml-hexagon.so"],"env":{"GGML_HEXAGON_LIBHTP_DIR":"/data/local/tmp/x"}}]
//   LB_RUN_LOG_LEVEL (env, optional): the runtime's log_level (debug shows where each layer went)
//   REQUESTS.jsonl: {"id": "...", "endpoint": "chat", "body": {...}} per line (research/p0 format).
#include "llamabro.h"
#include "lb_common.h"

#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <mutex>
#include <string>

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
    // optional backends, as the app passes them (the device contract on the phone, P2 T9)
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
        lb_request * req = nullptr;
        if (lb_model_request(model, endpoint.c_str(), nullptr, body.data(), body.size(), on_event, &s, &req) != LB_OK) {
            std::fprintf(stderr, "%s: lb_model_request failed\n", id.c_str());
            rc = 1;
            continue;
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
