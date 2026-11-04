# Shipped in the AAR and applied to every app that minifies with R8 (findings F28).
# sdk/native/jni/lb_jni.cpp looks these up by name in JNI_OnLoad, registers the natives and calls these methods
# from native threads. Renamed or removed, JNI_OnLoad fails and the library can't load.
-keep class com.suhel.llamabro.internal.NativeBridge {
    native <methods>;
}
-keep interface com.suhel.llamabro.internal.RequestSink { *; }
-keep interface com.suhel.llamabro.internal.ProgressListener { *; }
-keep interface com.suhel.llamabro.internal.LogListener { *; }
-keep class com.suhel.llamabro.internal.NativeError {
    <init>(int, java.lang.String);
}
