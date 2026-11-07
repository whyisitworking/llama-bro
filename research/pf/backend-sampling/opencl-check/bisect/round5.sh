#!/usr/bin/env bash
# F75 round 5: ffn_down alone on the GPU breaks the reply (round 4). Which type: Q4_K (16 layers) or Q6_K (17)? Same for attn_qkv; attn_gate (all Q4_K) alone.
# Each arm keeps every layer weight on the CPU except the ffn_down weights named.
here="$(cd "$(dirname "$0")" && pwd)"; q=Qwen3.5-4B-Q4_K_M
q4k="4|5|7|8|10|11|13|14|16|17|19|20|22|23|25|26"; q6k="0|1|2|3|6|9|12|15|18|21|24|27|28|29|30|31|32"
qkv4="4|5|8|10|13|14|16|17|20|22|25|26"; qkv6="0|1|2|6|9|12|18|21|24|28|29|30"
for o in "'blk\\.(?!($q4k)\\.ffn_down)[0-9]+\\.=CPU'" "'blk\\.(?!($q6k)\\.ffn_down)[0-9]+\\.=CPU'" \
         "'blk\\.(?!4\\.ffn_down)[0-9]+\\.=CPU'" "'blk\\.(?!0\\.ffn_down)[0-9]+\\.=CPU'" \
         "'blk\\.(?!($qkv4)\\.attn_qkv)[0-9]+\\.=CPU'" "'blk\\.(?!($qkv6)\\.attn_qkv)[0-9]+\\.=CPU'" \
         "'blk\\.[0-9]+\\.(?!attn_gate)=CPU'"; do
  EXTRA="-ot $o" "$here/ob.sh" $q - | sed "s/| (none) |/| -ot $o |/"
done
