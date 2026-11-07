#!/usr/bin/env bash
# F75 round 6: attn_gate (Q4_K, K=2560, M=4096) alone on the GPU breaks the reply (round 5). Its prefill runs the
# Adreno DP4A ILA GEMM (default) unless GGML_OPENCL_Q4_K_BIN_DP4A=0 (F32 ILA GEMM). Whole model, then attn_gate alone.
here="$(cd "$(dirname "$0")" && pwd)"; q=Qwen3.5-4B-Q4_K_M
"$here/ob.sh" $q GGML_OPENCL_Q4_K_BIN_DP4A=0
o="'blk\\.[0-9]+\\.(?!attn_gate)=CPU'"
EXTRA="-ot $o" "$here/ob.sh" $q GGML_OPENCL_Q4_K_BIN_DP4A=0 | sed "s/| GGML/| -ot $o GGML/"
