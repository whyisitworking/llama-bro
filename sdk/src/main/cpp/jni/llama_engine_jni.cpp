#include <jni.h>
#include "utils/jni_config_reader.h"
#include "utils/jni_error_thrower.h"
#include "utils/error_codes.h"
#include "engine.h"
#include "llama.h"

extern "C"
JNIEXPORT jlong JNICALL
Java_com_suhel_llamabro_sdk_internal_LlamaEngineImpl_00024Jni_create(JNIEnv *env, jclass clazz,
                                                                     jobject kConfig) {
    auto configReader = JniConfigReader(env, kConfig);

    auto modelPath = configReader.getString("modelPath");
    auto threads = configReader.getInt("threads");
    auto useMmap = configReader.getBool("useMmap");
    auto useMlock = configReader.getBool("useMlock");

    auto config = NativeEngineParams{
            .model_path = modelPath,
            .threads = threads,
            .use_mmap = useMmap,
            .use_mlock = useMlock,
    };

    try {
        auto instance = new LlamaEngine(config);
        return reinterpret_cast<jlong>(instance);
    } catch (const std::runtime_error &e) {
        // The error message is already formatted as "<code>:<detail>" by engine.cpp
        jclass exc = env->FindClass("java/lang/RuntimeException");
        env->ThrowNew(exc, e.what());
        env->DeleteLocalRef(exc);
        return 0L;
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_suhel_llamabro_sdk_internal_LlamaEngineImpl_00024Jni_destroy(JNIEnv *env,
                                                                      jclass clazz,
                                                                      jlong ptr) {
    auto instance = reinterpret_cast<LlamaEngine *>(ptr);
    delete instance;
}
