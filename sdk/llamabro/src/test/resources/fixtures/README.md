# Chat stream fixtures (P1 T8)

`*.jsonl`: upstream llama-server's raw SSE output for the spike's T2 requests on the phone CPU, copied from
`spike/p0/results/raw/cpu-t2-u1/` (one `{"t_us", "data"}` line per chunk). The requests are in
`sdk/native/tests/contract/requests.jsonl`.

`*.expected.json`: produced once from each `.jsonl` by `rebuild_chat` in `sdk/native/tests/contract/compare.py`
(copied from `spike/p0/scripts/compare.py`), keeping `content`, `reasoning_content`, `tool_calls` and
`finish_reason`.
