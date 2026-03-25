# ── llama-bro SDK ProGuard consumer rules ─────────────────────────────────────
# These rules are automatically applied to any app that depends on this SDK.
# They protect JNI-visible classes and fields from being renamed or removed.

# Keep native method declarations (JNI resolves by exact mangled name)
-keepclassmembers class com.suhel.llamabro.sdk.engine.internal.LlamaEngineImpl$Jni {
    native <methods>;
}
-keepclassmembers class com.suhel.llamabro.sdk.engine.internal.LlamaSessionImpl$Jni {
    native <methods>;
}

# Keep config classes whose fields are read reflectively from JNI via JniConfigReader
-keepclassmembers class com.suhel.llamabro.sdk.engine.internal.LlamaEngineImpl$NativeCreateParams {
    <fields>;
}
-keepclassmembers class com.suhel.llamabro.sdk.engine.internal.LlamaSessionImpl$NativeCreateParams {
    <fields>;
}
-keepclassmembers class com.suhel.llamabro.sdk.engine.internal.LlamaSessionImpl$NativeTokenGenerationResult {
    <fields>;
}
-keepclassmembers class com.suhel.llamabro.sdk.engine.internal.LlamaSessionImpl$NativeInferenceParams {
    <fields>;
}

# Keep ProgressListener.onProgress — called from native code via JNI CallBooleanMethod
-keepclassmembers class * implements com.suhel.llamabro.sdk.ProgressListener {
    boolean onProgress(float);
}
