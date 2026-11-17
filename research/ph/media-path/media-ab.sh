#!/usr/bin/env bash
# W9 step 3 (D84): what chat media's base64 costs per turn, against llama-server reading the file itself (file://,
# --media-path) and against the bridge handing its buffer over (raw, research patch). LFM3-Omni-2B on the NPU through the
# C API (lb-run, as the SDK sends it, polling on as LlamaBroConfig's default): a chat holding three voice notes with
# replies, then a text question; the same body six times, the first filling the prompt cache, so the other five measure
# the media path plus the new tokens. One lb-run per variant, the phone cooled to thermal status 0 before each.
# usage: ANDROID_SERIAL=<serial> media-ab.sh <research device tools> <clips dir with v1.wav v2.wav v3.wav> <label> [variants...]
#        variants: base64 (default), file, raw (LB_MEDIA, the research build of research/ph/media-path/), linked (the
#        carried bridge, D87: media_dir in the model config, no switch)
set -euo pipefail
tools="$1"; clips="$2"; label="$3"; shift 3
variants=("$@"); [ ${#variants[@]} -gt 0 ] || variants=(base64 file raw)
here="$(cd "$(dirname "$0")" && pwd)"
out="$here/ab-$label"; rm -rf "$out"; mkdir -p "$out"
remote=/data/local/tmp/lb-media
models=/data/local/tmp/lb-spike/models
adb shell "rm -rf $remote && mkdir -p $remote/bin $remote/clips"
adb push -q "$tools/." "$remote/bin/" > /dev/null
adb push -q "$clips/v1.wav" "$clips/v2.wav" "$clips/v3.wav" "$remote/clips/" > /dev/null

python3 - "$remote/clips" > "$out/requests.jsonl" <<'PY'
import json, sys
clips = sys.argv[1]
msgs = []
for i, (note, reply) in enumerate([
        ("Here is a voice note about my morning.", "You went to the market and met an old friend."),
        ("And this one is about work.", "The meeting moved to four o'clock."),
        ("Last one, about dinner.", "Pasta with garlic and lemon, and a salad.")], 1):
    msgs.append({"role": "user", "content": [
        {"type": "input_audio", "input_audio": {"data": f"lb-attachment:v{i}", "format": "wav"}},
        {"type": "text", "text": note}]})
    msgs.append({"role": "assistant", "content": reply})
msgs.append({"role": "user", "content": "What did I buy at the market?"})
body = {"messages": msgs, "max_tokens": 1, "temperature": 0, "stream": False}
atts = [{"name": f"v{i}", "mime": "audio/wav", "path": f"{clips}/v{i}.wav", "via": "path"} for i in (1, 2, 3)]
for r in range(1, 7):
    print(json.dumps({"id": f"r{r}", "endpoint": "chat", "body": body, "attachments": atts}))
PY
adb push -q "$out/requests.jsonl" "$remote/requests.jsonl"

config=$(python3 -c "import json; print(json.dumps({'model_path': '$models/LFM3-Omni-2B-Q4_K_M.gguf', 'mmproj_path': '$models/mmproj-LFM3-Omni-2B-Q8_0.gguf', 'n_ctx': 8192, 'devices': ['HTP0'], 'n_gpu_layers': 999}))")
backends="[{\"name\":\"hexagon\",\"files\":[\"libggml-hexagon.so\"],\"env\":{\"GGML_HEXAGON_LIBHTP_DIR\":\"$remote/bin\",\"GGML_HEXAGON_OPPOLL\":\"1\"}}]"
for v in "${variants[@]}"; do
  for _ in $(seq 1 60); do
    s="$(adb shell dumpsys thermalservice | sed -n 's/^Thermal Status: //p' | tr -d '\r')"
    [ "${s:-0}" -eq 0 ] && break; sleep 10
  done
  envs=""; cfg="$config"
  case "$v" in
    file|raw) envs="LB_MEDIA=$v" ;;
    linked) adb shell "rm -rf $remote/media && mkdir -p $remote/media"
            cfg=$(python3 -c "import json,sys; c=json.loads(sys.argv[1]); c['media_dir']=sys.argv[2]; print(json.dumps(c))" "$config" "$remote/media") ;;
  esac
  adb shell "cd $remote && rm -rf out-$v && mkdir -p out-$v && $envs LB_RUN_BACKENDS='$backends' LD_LIBRARY_PATH=$remote/bin bin/lb-run '$cfg' requests.jsonl out-$v 2> lb-run-$v.log" || {
    adb shell "tail -20 $remote/lb-run-$v.log"; echo "FAIL: lb-run $v"; exit 1; }
  mkdir -p "$out/$v"; adb pull -q "$remote/out-$v/." "$out/$v/"
  python3 - "$out/$v" "$v" <<'PY'
import json, os, statistics, sys
d, v = sys.argv[1], sys.argv[2]
rows = [json.loads(l) for l in open(os.path.join(d, "summary.jsonl"))]
res = []
for r in rows:
    data = "".join(json.loads(l)["data"] for l in open(os.path.join(d, r["id"] + ".jsonl")))
    t = json.loads(data).get("timings", {})
    res.append((r["id"], r["t_first_us"] / 1000, t.get("prompt_ms", 0), t.get("prompt_n", 0), t.get("cache_n", 0)))
warm = res[1:]
print(f"== {v}: first request {res[0][1]:.0f} ms; then median {statistics.median(x[1] for x in warm):.0f} ms "
      f"(min {min(x[1] for x in warm):.0f}, max {max(x[1] for x in warm):.0f}); server prompt median "
      f"{statistics.median(x[2] for x in warm):.0f} ms, {warm[0][3]} tokens processed, {warm[0][4]} cached")
PY
done
