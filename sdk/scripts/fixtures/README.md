# Test fixtures

`clip.wav`: the first 6 seconds of llama.cpp's `tools/mtmd/test-2.mp3` (MIT, at the pinned submodule commit),
converted to 16 kHz mono 16-bit WAV, the format every audio path takes. The audio tests (native, host, contract) use it
as speech; none of them checks what it says.
