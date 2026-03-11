#include <jni.h>
#include "utils/jni_config_reader.h"
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

    auto instance = new LlamaEngine(config);
    return reinterpret_cast<jlong>(instance);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_suhel_llamabro_sdk_internal_LlamaEngineImpl_00024Jni_destroy(JNIEnv *env,
                                                                      jclass clazz,
                                                                      jlong ptr) {
    auto instance = reinterpret_cast<LlamaEngine *>(ptr);
    delete instance;
}
