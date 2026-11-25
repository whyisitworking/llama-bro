# Roadmap

What's planned, roughly in order. No dates: each step ships when its checks pass on a phone.

## Now

- **Serving on Wi-Fi** (`llamabro-server`): an app serves a loaded model to the other devices on its network, with
  llama-server's own routes, an API key, and discovery on the local network.
- **Search over your own documents** (`llamabro-rag`): retrieval-augmented generation on the phone, built on the
  SDK's embeddings and rerank.

## Next

- **More phones.** Only the OnePlus 13 (Snapdragon 8 Elite) is tested so far. Next are other Snapdragon generations
  (Hexagon v73, v75, v81) and phones without a Snapdragon. A report from your phone helps more than anything else.
- **Patches upstream.** The SDK's fixes to llama.cpp ([`sdk/native/patches`](sdk/native/patches/README.md)) belong in
  llama.cpp itself.

## Later

- **Kotlin Multiplatform**, so the same API reaches iOS, with Flutter on top.

Shipped work is in the [release notes](docs/release-notes.md).
