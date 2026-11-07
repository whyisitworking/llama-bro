#!/usr/bin/env bash
# F75 round 3: Q5_K ssm_out on the CPU fixes part of it (round 2). With it on the CPU, which other weights are wrong?
# Plus the Q5_K kernel variant on its own (GGML_OPENCL_Q5_K_BIN_DP4A=0).
here="$(cd "$(dirname "$0")" && pwd)"; q=Qwen3.5-4B-Q4_K_M
"$here/ob.sh" $q GGML_OPENCL_Q5_K_BIN_DP4A=0
for o in "ssm_out=CPU,attn_qkv=CPU" "ssm_out=CPU,attn_gate=CPU" "ssm_out=CPU,ffn_down=CPU" \
         "ssm_out=CPU,ffn_gate=CPU,ffn_up=CPU" "ssm_out=CPU,attn_q\\\\.=CPU,attn_k\\\\.=CPU,attn_v=CPU,attn_output=CPU"; do
  EXTRA="-ot $o" "$here/ob.sh" $q - | sed "s/| (none) |/| -ot $o |/"
done
