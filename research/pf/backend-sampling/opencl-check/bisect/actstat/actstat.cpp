// For every MUL_MAT on the CPU: the largest |sum| of a 32-value block of its activation input (src1), along K,
// and the largest |value|. OpenCL's Q8_1 activation quantizer stores the block sum as half: > 65504 is inf.
#include "arg.h"
#include "common.h"
#include "llama.h"
#include "ggml.h"
#include <cmath>
#include <cstdio>
#include <map>
#include <string>
#include <vector>
#include <clocale>

struct stat { double max_bsum = 0, max_abs = 0; long over = 0, blocks = 0; int64_t K = 0; };
static std::map<std::string, stat> g;

static bool cb(struct ggml_tensor * t, bool ask, void *) {
    if (ask) return t->op == GGML_OP_MUL_MAT;
    const ggml_tensor * w = t->src[0], * a = t->src[1];
    if (a->type != GGML_TYPE_F32 || !ggml_is_contiguous(a)) return true;
    std::string name = w->name;  // weight name, e.g. blk.4.ffn_down.weight
    for (auto & c : name) if (isdigit((unsigned char)c)) c = '#';
    const float * d = (const float *) a->data;  // CPU backend: host memory
    const int64_t K = a->ne[0], rows = ggml_nrows(a);
    stat & s = g[name + " (" + ggml_type_name(w->type) + ")"]; s.K = K;
    for (int64_t r = 0; r < rows; r++)
        for (int64_t b = 0; b + 32 <= K; b += 32) {
            double sum = 0, amax = 0;
            for (int i = 0; i < 32; i++) { float v = d[r * K + b + i]; amax = std::max(amax, (double) std::fabs(v)); sum += v; }
            // the kernel's value: d * sum(q) with d = amax/127, q = rint(v*127/amax)
            double dd = amax / 127.0, qs = 0; if (amax > 0) for (int i = 0; i < 32; i++) qs += std::rint(d[r * K + b + i] * 127.0 / amax);
            double hs = std::fabs(dd * qs);
            s.max_bsum = std::max(s.max_bsum, hs); s.max_abs = std::max(s.max_abs, amax); s.blocks++; if (hs > 65504.0) s.over++;
        }
    return true;
}

int main(int argc, char ** argv) {
    std::setlocale(LC_NUMERIC, "C");
    common_params params;
    common_init();
    if (!common_params_parse(argc, argv, params, LLAMA_EXAMPLE_COMMON)) return 1;
    llama_backend_init();
    params.cb_eval = cb; params.cb_eval_user_data = nullptr; params.warmup = false;
    auto init = common_init_from_params(params);
    auto * ctx = init->context();
    if (!ctx) return 1;
    auto tokens = common_tokenize(ctx, params.prompt, true, true);
    fprintf(stderr, "tokens: %zu\n", tokens.size());
    common_batch batch = common_batch_get_one(ctx, tokens);
    if (llama_process(ctx, LLAMA_PROCESS_TYPE_DECODE, batch.get())) return 1;
    printf("%-40s %6s %14s %12s %10s %10s\n", "weight", "K", "max|blocksum|", "max|act|", "blocks>65504", "blocks");
    for (auto & [k, s] : g) printf("%-40s %6lld %14.1f %12.1f %10ld %10ld\n", k.c_str(), (long long) s.K, s.max_bsum, s.max_abs, s.over, s.blocks);
    return 0;
}
