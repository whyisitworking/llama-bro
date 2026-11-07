# P2 T1 probe: can an app load the unsigned HTP skeleton?

> **Parent:** [findings F36](../../../docs/sdk-v2/findings.md) · [P2 plan T1](../../../docs/sdk-v2/plans/p2-backends.md) · 2026-11-05 · **Result: yes, debug and release**

Throwaway app (not shipped). `ProbeActivity` copies the skeleton from the APK's assets to
`noBackupFilesDir/llamabro/htp/`, and `native/probe.c` sets `ADSP_LIBRARY_PATH` (and the `CDSP*` variants, F19)
before calling upstream's `llama_server(argc, argv)` from `libllama-server-impl.so`, so llama-server runs inside
the app's process. Libraries: the P0 spike's `P` build (b12052 + delta-net patch), upstream names, `jniLibs`.

```bash
spike/p2/htp-probe/prepare.sh                                   # libprobe.so (NDK 29) + spike libs + skeleton asset
./gradlew -p spike/p2/htp-probe assembleDebug assembleRelease   # release: debuggable=false, debug-signed
adb install -r spike/p2/htp-probe/build/outputs/apk/release/htp-probe-release.apk
# the model must be readable by the app: copy to /sdcard/Android/data/com.suhel.htpprobe/files/ and chmod 644
adb shell am start -n com.suhel.htpprobe/.ProbeActivity --es args "'-m {files}/Qwen3.5-4B-Q4_K_M.gguf -dev HTP0 -ngl 99 -c 4096 -np 1 --host 127.0.0.1 --port 8099 -lv 4'"
adb forward tcp:8099 tcp:8099   # then curl /completion
```

Evidence for the release run: [release-evidence.log](release-evidence.log) (logcat and the placement lines).
