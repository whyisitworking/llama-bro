#!/usr/bin/env bash
# F75 round 2: matmul as a whole, the Adreno tiled GEMVs (lm_head Q6_K, Q4_K), and the weights only Qwen3.5 has
# (Q5_K ssm_out, F32 ssm_alpha/ssm_beta) kept on the CPU with -ot.
here="$(cd "$(dirname "$0")" && pwd)"; q=Qwen3.5-4B-Q4_K_M
"$here/ob.sh" $q GGML_OPENCL_OPFILTER=MUL_MAT GGML_OPENCL_Q6K_GEMV_TILED=0 GGML_OPENCL_Q4K_GEMV_TILED=0
EXTRA="-ot ssm_out=CPU" "$here/ob.sh" $q -
EXTRA="-ot ssm_alpha=CPU,ssm_beta=CPU" "$here/ob.sh" $q -
