# HTP probe: can an app load the unsigned Hexagon skeleton?

**Question.** Hexagon's NPU backend needs a DSP-side skeleton library (`libggml-htp-v79.so`) that is not signed for the
device. Can an ordinary Android app, debuggable or not, place it in app-private storage and run a model on the NPU
through FastRPC, so that the SDK can ship the skeleton as an asset?

## Method

A throwaway test app (not shipped), package `com.suhel.htpprobe`:

- `ProbeActivity` copies the skeleton from the APK's `assets/htp/` to `noBackupFilesDir/llamabro/htp/` on a worker
  thread, then calls the native `run`. Intent extras: `args` (llama-server arguments, space-separated; `{files}` stands for
  `getExternalFilesDir`) and `path` (`nobackup`, default, or `nobackup+system`, which appends the system DSP directories).
  Logs go to `getExternalFilesDir()/probe.log`.
- `native/probe.c` sets `ADSP_LIBRARY_PATH` and the `CDSP*_LIBRARY_PATH` variants (F19) before any ggml code runs,
  redirects stdout and stderr to the log, loads `libllama-server-impl.so` and calls upstream's
  `llama_server(argc, argv)`, so llama-server runs inside the app's process.
- Libraries are the P0 `P` build (b12052 plus the delta-net patch, [P0](../../p0/README.md)) under upstream's names in
  `jniLibs`; `prepare.sh` builds `libprobe.so` with NDK 29 (`ANDROID_NDK` or the default SDK location; variant `V`,
  default `P`) and sorts the libraries: the `libggml-htp-v*.so` skeletons become assets, the bench, cli and perplexity
  implementation libraries are left out.
- Device: OnePlus 13 (SM8750, Hexagon v79, Android 16), Qwen3.5-4B-Q4_K_M, `-dev HTP0 -ngl 99 -c 4096 -np 1`.
  Both a debug build and a release build (`debuggable=false`, signed with the debug key) were run.

## How to run

```bash
research/p2/htp-probe/prepare.sh                                   # libprobe.so, the P libraries and the skeleton asset
./gradlew -p research/p2/htp-probe assembleDebug assembleRelease
adb install -r research/p2/htp-probe/build/outputs/apk/release/htp-probe-release.apk   # ANDROID_SERIAL=<serial>
# the model must be readable by the app: copy it to /sdcard/Android/data/com.suhel.htpprobe/files/ and chmod 644
adb shell am start -n com.suhel.htpprobe/.ProbeActivity --es args "'-m {files}/Qwen3.5-4B-Q4_K_M.gguf -dev HTP0 -ngl 99 -c 4096 -np 1 --host 127.0.0.1 --port 8099 -lv 4'"
adb forward tcp:8099 tcp:8099    # then send a request to /completion
```

## Result

Yes, for both builds. FastRPC opens the skeleton from the app's directory and starts an unsigned protection domain
(`fastrpc_shell_unsigned_3`) for the app's own user. Qwen3.5 4B loads with 34/34 layers on HTP0 (2,856.64 MiB model
buffer, KV and recurrent state on HTP0 too). The 16-token greedy reply is byte-identical to a shell run of the same build.
Single-run speeds: decode 15.2 (debug) and 16.5 (release) t/s against 16.1 from the shell; prefill 42.5 and 43.4 against
48.1. Two log lines are harmless: FastRPC first looks in `<dir>/cdsp/` (error 0x2) before `<dir>/`, and a `dspqueue`
invoke error 0x80000414 also appears in the shell run.

Packaging requirements: `<uses-native-library>` entries for `libcdsprpc.so` (a vendor public library on this phone) and
for `libOpenCL.so` when the process links ggml-opencl; a model file the app can read.

## Findings / decisions

F36 (an ordinary app loads the unsigned skeleton and runs on the NPU), F19 (Hexagon runtime environment); design
section on backends (`llamabro-hexagon` ships skeletons as assets and sets `ADSP_LIBRARY_PATH` at runtime init). See
[findings](../../../docs/research/findings.md) and [decisions](../../../docs/decisions.md).

## Notes

One phone and one chip (v79) were tested; other OEMs may refuse unsigned protection domains, and other skeleton versions
are untested. The release-run evidence log (`release-evidence.log`: logcat lines and the placement lines) is not kept.
Date of the run: 2026-11-05.
