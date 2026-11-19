# The `noskel` flavor's broken skeleton

`assets/llamabro/htp/libggml-htp-v79.so` is an empty file on purpose. It replaces the Hexagon artifact's real v79
skeleton, so the NPU backend fails to load and `DevicePolicy.Auto` has to say why and fall back to the GPU. The
acceptance check runs this flavor for exactly that.
