#!/usr/bin/env bash
# F75 round 1: is the delta-net patch to blame (U = unpatched b12052), and which Qwen3.5-only op family moves the reply?
here="$(cd "$(dirname "$0")" && pwd)"
BIN=U "$here/ob.sh" Qwen3.5-4B-Q4_K_M -
"$here/ob.sh" Qwen3.5-4B-Q4_K_M - \
  GGML_OPENCL_OPFILTER=GATED_DELTA_NET \
  GGML_OPENCL_OPFILTER=SSM_CONV \
  GGML_OPENCL_DISABLE_FUSION=1 \
  'GGML_OPENCL_OPFILTER=GATED_DELTA_NET\|SSM_CONV\|L2_NORM\|SOFTPLUS\|SIGMOID\|CUMSUM\|TRI\|SOLVE_TRI\|FILL\|CONCAT'
