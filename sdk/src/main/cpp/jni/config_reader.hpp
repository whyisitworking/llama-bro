#pragma once

#include <jni.h>
#include <string>
#include <exception>

class JniConfigReader {
private:
    JNIEnv *env;
    jobject obj;
    jclass clazz;

public:
    JniConfigReader(JNIEnv *e, jobject o) : env(e), obj(o) {
        if (obj != nullptr) {
            clazz = env->GetObjectClass(obj);
        } else {
            throw std::runtime_error("Invalid JNI object");
        }
    }

    ~JniConfigReader() {
        env->DeleteLocalRef(clazz);
    }

    int getInt(const char *fieldName) {
        jfieldID fid = env->GetFieldID(clazz, fieldName, "I");
        return env->GetIntField(obj, fid);
    }

    float getFloat(const char *fieldName) {
        jfieldID fid = env->GetFieldID(clazz, fieldName, "F");
        return env->GetFloatField(obj, fid);
    }

    bool getBool(const char *fieldName) {
        jfieldID fid = env->GetFieldID(clazz, fieldName, "Z");
        return env->GetBooleanField(obj, fid);
    }

    std::string getString(const char *fieldName) {
        jfieldID fid = env->GetFieldID(clazz, fieldName, "Ljava/lang/String;");
        auto jstr = (jstring) env->GetObjectField(obj, fid);

        if (jstr == nullptr) return "";

        const char *chars = env->GetStringUTFChars(jstr, nullptr);
        std::string result(chars);

        // Crucial: Release memory to prevent JNI table overflows
        env->ReleaseStringUTFChars(jstr, chars);
        env->DeleteLocalRef(jstr);

        return result;
    }

    // Handles nested Kotlin objects (e.g., getting the executionConfig from the EngineConfig)
    JniConfigReader getNestedObject(const char *fieldName, const char *signature) {
        jfieldID fid = env->GetFieldID(clazz, fieldName, signature);
        jobject nestedObj = env->GetObjectField(obj, fid);
        return {env, nestedObj};
    }
};
