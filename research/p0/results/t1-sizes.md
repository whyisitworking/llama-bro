# T1: build sizes (arm64-android-snapdragon-release, b12052, stripped with NDK 29 llvm-strip)

> **Parent:** [P0](../README.md) · **Summarised in:** [findings](../../../docs/research/findings.md) (T1; finding F2)

| File | Stripped | Used by |
|---|---|---|
| lb-spike (server-context linked in statically) | 1.27 MB | harness only |
| llama-server + libllama-server-impl.so (HTTP, router, server-context) | 0.00 + 6.25 MB | llama-server only |
| libllama-common.so | 4.16 MB | both |
| libllama.so | 4.21 MB | both |
| libmtmd.so | 1.52 MB | both |
| libggml-base.so + libggml.so + libggml-cpu.so | 1.87 MB | both |
| libggml-hexagon.so + libggml-htp-v79.so | 1.82 MB | both (NPU) |
| libggml-opencl.so | 4.51 MB | both (GPU) |
| libc++_shared.so | 9.29 MB (unstripped NDK copy) | both |

Result: PASS. The in-process engine adds 1.27 MB, against 6.25 MB for llama-server's HTTP layer.
Not yet checked: whether the linker drops server-tools / server-mcp from lb-spike (they are inside the 1.27 MB).
