// JNI over the C API. Kotlin side: sdk/llamabro/src/main/kotlin/com/suhel/llamabro/internal/NativeBridge.kt.
//
// Native threads (the model worker, each request's thread) call back into Kotlin. A thread is attached
// to the JVM on its first callback and detached when it exits (thread_local guard), which for a request
// thread is right after its terminal event. Strings from native code go through String(byte[], UTF-8)
// rather than NewStringUTF, which rejects invalid or 4-byte UTF-8.
#include "llamabro.h"

#include <jni.h>

#ifdef __ANDROID__
#include <android/log.h>
#else
// host JVM build (LB_HOST_JNI, for the host end-to-end tests): log to stderr
#include <cstdio>
#define ANDROID_LOG_ERROR 6
#define __android_log_print(prio, tag, ...) (std::fprintf(stderr, "%s: ", tag), std::fprintf(stderr, __VA_ARGS__), std::fputc('\n', stderr))
#endif

#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <string>
#include <utility>
#include <vector>

namespace {

JavaVM * g_vm = nullptr;

jclass    g_string_class;        // java.lang.String
jmethodID g_string_ctor;         // String(byte[], String charsetName)
jmethodID g_string_get_bytes;    // String.getBytes(String charsetName)
jstring   g_utf8;                // "UTF-8"
jclass    g_native_error_class;  // NativeError
jmethodID g_native_error_ctor;   // NativeError(int, String)
jmethodID g_sink_on_chunk;       // RequestSink.onChunk(int, byte[])
jmethodID g_sink_on_done;
jmethodID g_sink_on_cancelled;
jmethodID g_progress_on_progress;  // ProgressListener.onProgress(float): boolean
jmethodID g_log_on_log;            // LogListener.onLog(int, String)

// Attaches the calling native thread on first use; detaches it when the thread exits.
struct thread_env {
    JNIEnv * env = nullptr;
    bool attached_here = false;
    ~thread_env() {
        if (attached_here && g_vm) g_vm->DetachCurrentThread();
    }
};

JNIEnv * env_for_thread() {
    thread_local thread_env te;
    if (te.env) return te.env;
    JNIEnv * env = nullptr;
    if (g_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_OK) {
        return env;  // a JVM thread: don't cache, it may call from several frames
    }
    if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return nullptr;
    te.env = env;
    te.attached_here = true;
    return env;
}

// Out of memory in the JVM: drop the pending OutOfMemoryError (it must not cross into native code) and log.
void report_oom(JNIEnv * env, const char * what) {
    env->ExceptionClear();
    __android_log_print(ANDROID_LOG_ERROR, "llamabro", "out of memory creating %s", what);
}

jstring new_string(JNIEnv * env, const char * data, size_t len) {
    jbyteArray bytes = env->NewByteArray(static_cast<jsize>(len));
    if (!bytes) { report_oom(env, "a string"); return nullptr; }
    env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(len), reinterpret_cast<const jbyte *>(data));
    auto * s = static_cast<jstring>(env->NewObject(g_string_class, g_string_ctor, bytes, g_utf8));
    env->DeleteLocalRef(bytes);
    return s;
}

jstring take_string(JNIEnv * env, char * s) {  // takes ownership of an lb_* returned string
    if (!s) return nullptr;
    jstring out = new_string(env, s, std::strlen(s));
    lb_string_free(s);
    return out;
}

// Real UTF-8 via String.getBytes: GetStringUTFChars only promises modified UTF-8, which encodes characters
// outside the BMP (emoji) and NUL differently (findings F28).
std::string get_string(JNIEnv * env, jstring s) {
    if (!s) return {};
    auto * bytes = static_cast<jbyteArray>(env->CallObjectMethod(s, g_string_get_bytes, g_utf8));
    if (!bytes) { report_oom(env, "UTF-8 bytes"); return {}; }
    const jsize n = env->GetArrayLength(bytes);
    std::string out(static_cast<size_t>(n), '\0');
    if (n > 0) env->GetByteArrayRegion(bytes, 0, n, reinterpret_cast<jbyte *>(&out[0]));
    env->DeleteLocalRef(bytes);
    return out;
}

void throw_native_error(JNIEnv * env, int status, char * error_json,
                        const char * fallback = "{\"error\":\"unknown\"}") {
    jstring msg = error_json ? take_string(env, error_json) : new_string(env, fallback, std::strlen(fallback));
    auto * ex = static_cast<jthrowable>(env->NewObject(g_native_error_class, g_native_error_ctor, (jint) status, msg));
    env->Throw(ex);
}

void clear_pending(JNIEnv * env) {
    // an exception thrown by a Kotlin callback must not cross into native code
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
}

// --- logging -----------------------------------------------------------------------------------

jobject g_log_listener = nullptr;  // global ref; swapped under lb_runtime_set_log

void log_cb(void * user, lb_log_level level, const char * text) {
    JNIEnv * env = env_for_thread();
    if (!env) return;
    jstring s = new_string(env, text, std::strlen(text));
    if (!s) return;
    env->CallVoidMethod(static_cast<jobject>(user), g_log_on_log, (jint) level, s);
    clear_pending(env);
    env->DeleteLocalRef(s);
}

// --- requests ----------------------------------------------------------------------------------

struct request_ctx {
    jobject sink;  // global ref, deleted after the terminal event
};

void request_cb(void * user, lb_event event, int http_status, const char * data, size_t len) {
    auto * ctx = static_cast<request_ctx *>(user);
    JNIEnv * env = env_for_thread();
    if (!env) return;
    if (event == LB_EVENT_CHUNK) {
        jbyteArray bytes = env->NewByteArray(static_cast<jsize>(len));
        if (!bytes) { report_oom(env, "a response chunk"); return; }
        env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(len), reinterpret_cast<const jbyte *>(data));
        env->CallVoidMethod(ctx->sink, g_sink_on_chunk, (jint) http_status, bytes);
        clear_pending(env);
        env->DeleteLocalRef(bytes);
        return;
    }
    env->CallVoidMethod(ctx->sink, event == LB_EVENT_DONE ? g_sink_on_done : g_sink_on_cancelled);
    clear_pending(env);
    env->DeleteGlobalRef(ctx->sink);
    delete ctx;
}

// --- natives -----------------------------------------------------------------------------------

// null on success, else {"status": <lb_status>, "error": "..."} (Kotlin tells ALREADY_INITIALIZED apart)
jstring runtimeInit(JNIEnv * env, jobject, jstring config) {
    char * err = nullptr;
    const std::string c = get_string(env, config);
    const lb_status st = lb_runtime_init(c.c_str(), &err);
    if (st == LB_OK) return nullptr;
    std::string body = err ? err : "{\"error\":\"lb_runtime_init failed\"}";
    if (err) lb_string_free(err);
    body = "{\"status\":" + std::to_string(static_cast<int>(st)) + "," + body.substr(1);
    return new_string(env, body.data(), body.size());
}

jstring runtimeDevices(JNIEnv * env, jobject) {
    return take_string(env, lb_runtime_devices());
}

jstring runtimeBackends(JNIEnv * env, jobject) {
    return take_string(env, lb_runtime_backends());
}

void runtimeSetLog(JNIEnv * env, jobject, jobject listener) {
    jobject old = g_log_listener;
    if (listener) {
        g_log_listener = env->NewGlobalRef(listener);
        lb_runtime_set_log(log_cb, g_log_listener);
    } else {
        g_log_listener = nullptr;
        lb_runtime_set_log(nullptr, nullptr);
    }
    // lb_runtime_set_log returned: no call with `old` is still running on another thread (llamabro.h)
    if (old) env->DeleteGlobalRef(old);
}

// Kotlin answers whether its caller still wants the model: false stops the load (D67). A listener that threw says
// nothing about the caller, so the load goes on
bool progress_cb(void * user, float p) {
    JNIEnv * env = env_for_thread();
    if (!env) return true;
    const jboolean go_on = env->CallBooleanMethod(static_cast<jobject>(user), g_progress_on_progress, (jfloat) p);
    if (env->ExceptionCheck()) {
        clear_pending(env);
        return true;
    }
    return go_on == JNI_TRUE;
}

jlong modelLoad(JNIEnv * env, jobject, jstring config, jobject progress) {
    const std::string c = get_string(env, config);
    jobject listener = progress ? env->NewGlobalRef(progress) : nullptr;
    lb_model * model = nullptr;
    char * err = nullptr;
    const lb_status st = lb_model_load(c.c_str(), listener ? progress_cb : nullptr, listener, &model, &err);
    if (listener) env->DeleteGlobalRef(listener);  // no callbacks after lb_model_load returns
    if (st != LB_OK) {
        // LB_ERR_CANCELLED included: Kotlin turns that status into the caller's cancellation
        throw_native_error(env, st, err);
        return 0;
    }
    return reinterpret_cast<jlong>(model);
}

jstring fitEstimate(JNIEnv * env, jobject, jstring config) {
    const std::string c = get_string(env, config);
    char * result = nullptr;
    char * err = nullptr;
    const lb_status st = lb_fit_estimate(c.c_str(), &result, &err);
    if (st != LB_OK) {
        throw_native_error(env, st, err);
        return nullptr;
    }
    return take_string(env, result);
}

jstring modelInfo(JNIEnv * env, jobject, jlong model) {
    char * info = nullptr;
    char * err = nullptr;
    const lb_status st = lb_model_info(reinterpret_cast<lb_model *>(model), &info, &err);
    if (st != LB_OK) {
        throw_native_error(env, st, err);
        return nullptr;
    }
    return take_string(env, info);
}

void release_lent(JNIEnv * env, std::vector<std::pair<jbyteArray, jbyte *>> & lent) {
    for (auto & [arr, p] : lent) {
        env->ReleaseByteArrayElements(arr, p, JNI_ABORT);  // unchanged: nothing to copy back
        env->DeleteLocalRef(arr);
    }
    lent.clear();
}

// Attachments arrive as parallel arrays (names, mimes, paths, fds, bytes; JniEngine): a path or fd passes as is. A
// byte[] is lent with GetByteArrayElements and released unchanged (JNI_ABORT) once the call returns, since
// lb_model_request_with copies it before then (D84): one native copy. ART lends a large array (the large-object space
// doesn't move) without a copy of its own; a JVM that copies frees that copy at once.
jlong modelRequest(JNIEnv * env, jobject, jlong model, jstring endpoint, jstring params, jbyteArray body,
                   jobjectArray names, jobjectArray mimes, jobjectArray paths, jintArray fds, jobjectArray bytes,
                   jobject sink) {
    const std::string ep = get_string(env, endpoint);
    const std::string pj = get_string(env, params);
    const jsize n = body ? env->GetArrayLength(body) : 0;
    std::string b(static_cast<size_t>(n), '\0');
    if (n > 0) env->GetByteArrayRegion(body, 0, n, reinterpret_cast<jbyte *>(&b[0]));

    const jsize n_atts = names ? env->GetArrayLength(names) : 0;
    // the five arrays describe the same attachments: a mismatch is the caller's bug, not something to guess around
    const bool same_length = (!mimes ? n_atts == 0 : env->GetArrayLength(mimes) == n_atts) &&
                             (!paths ? n_atts == 0 : env->GetArrayLength(paths) == n_atts) &&
                             (!fds ? n_atts == 0 : env->GetArrayLength(fds) == n_atts) &&
                             (!bytes ? n_atts == 0 : env->GetArrayLength(bytes) == n_atts);
    if (!same_length) {
        throw_native_error(env, LB_ERR_INVALID_ARG, nullptr,
                           "{\"error\":\"attachment arrays differ in length (names, mimes, paths, fds, bytes)\"}");
        return 0;
    }
    std::vector<std::string> strings(static_cast<size_t>(n_atts) * 3);  // name, mime, path per attachment
    std::vector<std::pair<jbyteArray, jbyte *>> lent;  // released after the call
    std::vector<lb_attachment> atts(static_cast<size_t>(n_atts));
    std::vector<jint> fd_values(static_cast<size_t>(n_atts), -1);
    if (n_atts > 0) env->GetIntArrayRegion(fds, 0, n_atts, fd_values.data());
    static const uint8_t empty = 0;  // an empty byte[] is still a bytes source: data must not be NULL
    for (jsize i = 0; i < n_atts; i++) {
        const size_t k = static_cast<size_t>(i);
        auto * name = static_cast<jstring>(env->GetObjectArrayElement(names, i));
        auto * mime = static_cast<jstring>(env->GetObjectArrayElement(mimes, i));
        auto * path = static_cast<jstring>(env->GetObjectArrayElement(paths, i));
        auto * data = static_cast<jbyteArray>(env->GetObjectArrayElement(bytes, i));
        strings[k * 3] = get_string(env, name);
        strings[k * 3 + 1] = get_string(env, mime);
        strings[k * 3 + 2] = get_string(env, path);
        lb_attachment & a = atts[k];
        a = lb_attachment{ strings[k * 3].c_str(), strings[k * 3 + 1].c_str(), nullptr, fd_values[k], nullptr, 0 };
        if (path) a.path = strings[k * 3 + 2].c_str();
        for (jobject o : { (jobject) name, (jobject) mime, (jobject) path }) {
            if (o) env->DeleteLocalRef(o);
        }
        if (!data) continue;
        const jsize len = env->GetArrayLength(data);
        jbyte * p = len > 0 ? env->GetByteArrayElements(data, nullptr) : nullptr;
        if (len > 0 && !p) {  // out of memory: give back what was lent, fail the call
            report_oom(env, "an attachment's bytes");
            env->DeleteLocalRef(data);
            release_lent(env, lent);
            throw_native_error(env, LB_ERR_INTERNAL, nullptr, "{\"error\":\"out of memory reading an attachment\"}");
            return 0;
        }
        a.data = p ? reinterpret_cast<const uint8_t *>(p) : &empty;
        a.len = static_cast<size_t>(len);
        if (p) {
            lent.emplace_back(data, p);  // its local ref goes with it
        } else {
            env->DeleteLocalRef(data);
        }
    }

    auto * ctx = new request_ctx{ env->NewGlobalRef(sink) };
    lb_request * req = nullptr;
    char * err = nullptr;
    const lb_status st = lb_model_request_with(reinterpret_cast<lb_model *>(model), ep.c_str(),
                                               params ? pj.c_str() : nullptr, b.data(), b.size(), atts.data(),
                                               atts.size(), request_cb, ctx, &req, &err);
    release_lent(env, lent);  // read by now (llamabro.h)
    if (st != LB_OK) {
        env->DeleteGlobalRef(ctx->sink);
        delete ctx;
        throw_native_error(env, st, err);
        return 0;
    }
    return reinterpret_cast<jlong>(req);
}

// the whole file behind fd, which stays open (lb_read_fd, D84)
jbyteArray readFd(JNIEnv * env, jobject, jint fd) {
    uint8_t * buf = nullptr;
    size_t len = 0;
    char * err = nullptr;
    const lb_status st = lb_read_fd(fd, &buf, &len, &err);
    if (st != LB_OK) {
        throw_native_error(env, st, err);
        return nullptr;
    }
    if (len > static_cast<size_t>(INT32_MAX)) {  // a Java array holds at most 2^31 - 1 bytes
        lb_buffer_free(buf);
        const std::string e = "{\"error\":\"descriptor " + std::to_string(fd) + " holds " + std::to_string(len) +
                              " bytes, more than a byte array can\"}";
        throw_native_error(env, LB_ERR_INVALID_ARG, nullptr, e.c_str());
        return nullptr;
    }
    jbyteArray out = env->NewByteArray(static_cast<jsize>(len));
    if (out && len > 0) env->SetByteArrayRegion(out, 0, static_cast<jsize>(len), reinterpret_cast<const jbyte *>(buf));
    lb_buffer_free(buf);
    return out;  // null with OutOfMemoryError pending when the array couldn't be made
}

void requestCancel(JNIEnv *, jobject, jlong req) { lb_request_cancel(reinterpret_cast<lb_request *>(req)); }
void requestFree(JNIEnv *, jobject, jlong req)   { lb_request_free(reinterpret_cast<lb_request *>(req)); }

// a realtime client event (D97); the lb_status, which Kotlin maps: the bridge copies the bytes before it returns. The
// reason is not asked for: a send to a closed conversation is expected (the status says so), and this runs per audio
// chunk
jint requestSend(JNIEnv * env, jobject, jlong req, jbyteArray msg) {
    const jsize len = env->GetArrayLength(msg);
    std::vector<char> buf(static_cast<size_t>(len));
    if (len > 0) env->GetByteArrayRegion(msg, 0, len, reinterpret_cast<jbyte *>(buf.data()));
    return static_cast<jint>(lb_request_send(reinterpret_cast<lb_request *>(req), buf.data(), buf.size(), nullptr));
}
void modelFree(JNIEnv *, jobject, jlong model)   { lb_model_free(reinterpret_cast<lb_model *>(model)); }

jclass global_class(JNIEnv * env, const char * name) {
    jclass local = env->FindClass(name);
    if (!local) return nullptr;
    auto * g = static_cast<jclass>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    return g;
}

}  // namespace

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM * vm, void *) {
    g_vm = vm;
    JNIEnv * env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;

    // classes are looked up here: FindClass on a native thread would use the system class loader. Any lookup that
    // fails (an app's R8 renamed something: consumer-rules.pro) ends in JNI_ERR, so System.loadLibrary throws
    // UnsatisfiedLinkError instead of the runtime aborting on a null class.
    auto fail = [env](const char * what) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        __android_log_print(ANDROID_LOG_ERROR, "llamabro", "JNI_OnLoad: %s not found (R8 keep rules missing?)", what);
        return JNI_ERR;
    };
    jclass sink, progress, log, bridge;
    if (!(g_string_class = global_class(env, "java/lang/String"))) return fail("java.lang.String");
    g_string_ctor = env->GetMethodID(g_string_class, "<init>", "([BLjava/lang/String;)V");
    g_string_get_bytes = env->GetMethodID(g_string_class, "getBytes", "(Ljava/lang/String;)[B");
    g_utf8 = static_cast<jstring>(env->NewGlobalRef(env->NewStringUTF("UTF-8")));
    if (!g_string_ctor || !g_string_get_bytes || !g_utf8) return fail("String methods");

    if (!(g_native_error_class = global_class(env, "com/suhel/llamabro/spi/NativeError"))) return fail("NativeError");
    if (!(g_native_error_ctor = env->GetMethodID(g_native_error_class, "<init>", "(ILjava/lang/String;)V"))) return fail("NativeError(int, String)");

    if (!(sink = env->FindClass("com/suhel/llamabro/spi/RequestSink"))) return fail("RequestSink");
    g_sink_on_chunk = env->GetMethodID(sink, "onChunk", "(I[B)V");
    g_sink_on_done = env->GetMethodID(sink, "onDone", "()V");
    g_sink_on_cancelled = env->GetMethodID(sink, "onCancelled", "()V");
    if (!g_sink_on_chunk || !g_sink_on_done || !g_sink_on_cancelled) return fail("RequestSink methods");
    if (!(progress = env->FindClass("com/suhel/llamabro/internal/ProgressListener"))) return fail("ProgressListener");
    if (!(g_progress_on_progress = env->GetMethodID(progress, "onProgress", "(F)Z"))) return fail("ProgressListener.onProgress");
    if (!(log = env->FindClass("com/suhel/llamabro/internal/LogListener"))) return fail("LogListener");
    if (!(g_log_on_log = env->GetMethodID(log, "onLog", "(ILjava/lang/String;)V"))) return fail("LogListener.onLog");

    const JNINativeMethod methods[] = {
        {"runtimeInit",    "(Ljava/lang/String;)Ljava/lang/String;", (void *) runtimeInit},
        {"runtimeDevices", "()Ljava/lang/String;", (void *) runtimeDevices},
        {"runtimeBackends", "()Ljava/lang/String;", (void *) runtimeBackends},
        {"runtimeSetLog",  "(Lcom/suhel/llamabro/internal/LogListener;)V", (void *) runtimeSetLog},
        {"modelLoad",      "(Ljava/lang/String;Lcom/suhel/llamabro/internal/ProgressListener;)J", (void *) modelLoad},
        {"fitEstimate",    "(Ljava/lang/String;)Ljava/lang/String;", (void *) fitEstimate},
        {"modelInfo",      "(J)Ljava/lang/String;", (void *) modelInfo},
        {"modelRequest",   "(JLjava/lang/String;Ljava/lang/String;[B[Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/String;[I[[B"
                           "Lcom/suhel/llamabro/spi/RequestSink;)J", (void *) modelRequest},
        {"readFd",         "(I)[B", (void *) readFd},
        {"requestCancel",  "(J)V", (void *) requestCancel},
        {"requestFree",    "(J)V", (void *) requestFree},
        {"requestSend",    "(J[B)I", (void *) requestSend},
        {"modelFree",      "(J)V", (void *) modelFree},
    };
    if (!(bridge = env->FindClass("com/suhel/llamabro/internal/NativeBridge"))) return fail("NativeBridge");
    if (env->RegisterNatives(bridge, methods, sizeof(methods) / sizeof(methods[0])) != JNI_OK) return fail("NativeBridge natives");
    return JNI_VERSION_1_6;
}
