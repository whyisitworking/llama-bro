#!/usr/bin/env bash
# F75 round 4, the inverse: every layer weight on the CPU except one group, so a broken reply names that group.
# I0 keeps only the lm_head (token_embd, tied, Q6_K, K=2560) on the GPU.
here="$(cd "$(dirname "$0")" && pwd)"; q=Qwen3.5-4B-Q4_K_M
for o in "'blk\\.=CPU'" \
         "'blk\\.[0-9]+\\.(?!ffn_gate|ffn_up)=CPU'" \
         "'blk\\.[0-9]+\\.(?!ffn_down)=CPU'" \
         "'blk\\.[0-9]+\\.(?!ssm_out)=CPU'" \
         "'blk\\.[0-9]+\\.(?!attn_qkv|attn_gate)=CPU'" \
         "'blk\\.[0-9]+\\.(?!attn_q\\.|attn_k\\.|attn_v|attn_output)=CPU'"; do
  EXTRA="-ot $o" "$here/ob.sh" $q - | sed "s/| (none) |/| -ot $o |/"
done
