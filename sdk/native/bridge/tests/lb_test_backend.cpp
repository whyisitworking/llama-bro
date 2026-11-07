// A ggml backend library for the host tests only (P2 T2): it has no devices. ggml_backend_init records the value of
// LB_TEST_BACKEND_VALUE as it sees it, so a test can check that a backend's env was set before it initialised, and
// returns NULL when LB_TEST_BACKEND_FAIL is set, the way a backend whose driver is missing does.
#include "ggml-backend-impl.h"

#include <cstdlib>
#include <string>

namespace {

std::string g_seen;

const char * reg_name(ggml_backend_reg_t) { return "LBTEST"; }
size_t reg_device_count(ggml_backend_reg_t) { return 0; }
ggml_backend_dev_t reg_device(ggml_backend_reg_t, size_t) { return nullptr; }
void * reg_proc_address(ggml_backend_reg_t, const char *) { return nullptr; }

ggml_backend_reg g_reg = {
    /* .api_version = */ GGML_BACKEND_API_VERSION,
    /* .iface       = */ { reg_name, reg_device_count, reg_device, reg_proc_address },
    /* .context     = */ nullptr,
};

}  // namespace

extern "C" {

__attribute__((visibility("default"))) const char * lb_test_backend_seen(void) { return g_seen.c_str(); }

__attribute__((visibility("default"))) ggml_backend_reg_t ggml_backend_init(void) {
    const char * v = std::getenv("LB_TEST_BACKEND_VALUE");
    g_seen = v ? v : "";
    return std::getenv("LB_TEST_BACKEND_FAIL") ? nullptr : &g_reg;
}

}  // extern "C"
