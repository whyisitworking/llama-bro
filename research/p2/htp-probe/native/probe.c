// P2 T1 probe shim: set the DSP search path, then run upstream llama-server's main in this process.
#include <dlfcn.h>
#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <android/log.h>

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "htp-probe", __VA_ARGS__)

JNIEXPORT jint JNICALL Java_com_suhel_htpprobe_ProbeActivity_run(JNIEnv * env, jclass cls, jstring jdsp, jstring jlog, jobjectArray jargs) {
    const char * dsp = (*env)->GetStringUTFChars(env, jdsp, NULL);
    const char * log = (*env)->GetStringUTFChars(env, jlog, NULL);
    // as upstream's scripts/snapdragon/run.py (findings F19), before any ggml code runs
    const char * vars[] = { "ADSP_LIBRARY_PATH", "CDSP_LIBRARY_PATH", "CDSP1_LIBRARY_PATH", "CDSP2_LIBRARY_PATH", "CDSP3_LIBRARY_PATH" };
    for (size_t i = 0; i < sizeof(vars) / sizeof(vars[0]); i++) setenv(vars[i], dsp, 1);
    LOG("ADSP_LIBRARY_PATH=%s, log %s", dsp, log);
    freopen(log, "w", stderr);
    freopen(log, "a", stdout);
    setvbuf(stderr, NULL, _IONBF, 0);
    setvbuf(stdout, NULL, _IONBF, 0);

    void * h = dlopen("libllama-server-impl.so", RTLD_NOW);
    if (!h) { LOG("dlopen failed: %s", dlerror()); return -100; }
    int (*llama_server)(int, char **) = (int (*)(int, char **)) dlsym(h, "_Z12llama_serveriPPc");
    if (!llama_server) { LOG("dlsym failed: %s", dlerror()); return -101; }

    int argc = (*env)->GetArrayLength(env, jargs);
    char ** argv = calloc(argc + 1, sizeof(char *));
    for (int i = 0; i < argc; i++) {
        jstring s = (*env)->GetObjectArrayElement(env, jargs, i);
        argv[i] = (char *) (*env)->GetStringUTFChars(env, s, NULL);
    }
    LOG("starting llama_server with %d args", argc);
    return llama_server(argc, argv);
}
