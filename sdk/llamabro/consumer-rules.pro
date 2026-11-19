# Shipped in the AAR and applied to every app that minifies with R8 (findings F28).
# sdk/native/jni/lb_jni.cpp looks these up by name in JNI_OnLoad, registers the natives and calls these methods
# from native threads. Renamed or removed, JNI_OnLoad fails and the library can't load.
-keep class com.suhel.llamabro.internal.NativeBridge {
    native <methods>;
}
-keep interface com.suhel.llamabro.spi.RequestSink { *; }
-keep interface com.suhel.llamabro.internal.ProgressListener { *; }
-keep interface com.suhel.llamabro.internal.LogListener { *; }
-keep class com.suhel.llamabro.spi.NativeError {
    <init>(int, java.lang.String);
}

# Backend artifacts name their installer class in the manifest (<meta-data com.suhel.llamabro.backend.*>, D24); the
# core creates it by that name with reflection (LlamaBro.kt, manifestBackends). Renamed or removed, the backend
# would be reported as failed instead of loading.
-keep class * implements com.suhel.llamabro.spi.BackendInstaller {
    public <init>();
}
