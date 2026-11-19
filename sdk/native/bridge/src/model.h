// lb_model internals, shared by model.cpp and request.cpp.
#pragma once

#include "llamabro.h"
#include "lb_common.h"

#include "server-common.h"
#include "server-context.h"
#include "server-http.h"

#include "common.h"

#include <atomic>
#include <condition_variable>
#include <map>
#include <memory>
#include <mutex>
#include <set>
#include <string>
#include <thread>

struct lb_model {
    // load
    common_params params;
    json ctx_fit;                                        // lb_fit_auto_ctx's numbers, null when not fitted
    lb_progress_cb progress_cb = nullptr;
    void * progress_user = nullptr;
    std::atomic<bool> loading{true};
    // set when progress_cb returns false; read by server_context's load abort hook (patch 0003) on the loader's thread
    std::atomic<bool> stop_requested{false};

    server_context ctx_server;
    std::unique_ptr<server_routes> routes;               // keeps a reference to params
    std::map<std::string, server_http_context::handler_t> endpoints;

    std::thread worker;                                  // load_model + start_loop
    std::mutex mtx;
    std::condition_variable cv;
    enum { LOADING, READY, FAILED } state = LOADING;
    lb_status load_status = LB_OK;
    std::string load_error;
    bool loop_exited = false;

    // in-flight requests (request.cpp); guarded by mtx
    std::set<lb_request *> requests;
    bool closing = false;
};

// request.cpp: cancels every registered request and waits until each has sent its terminal event
void lb_model_cancel_all_requests(lb_model * model);

// request.cpp: attachment buffers alive now (read, not yet freed); for the tests that check a request frees them
int lb_attachments_live();
