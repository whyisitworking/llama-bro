#pragma once

#include <jni.h>
#include <string>
#include <exception>

#include "jni_refs.hpp"

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

    int getInt(const char *fieldName) const {
        auto fid = env->GetFieldID(clazz, fieldName, jni_refs::types::int32);
        return env->GetIntField(obj, fid);
    }

    float getFloat(const char *fieldName) const {
        auto fid = env->GetFieldID(clazz, fieldName, jni_refs::types::float32);
        return env->GetFloatField(obj, fid);
    }

    bool getBool(const char *fieldName) const {
        auto fid = env->GetFieldID(clazz, fieldName, jni_refs::types::boolean);
        return env->GetBooleanField(obj, fid);
    }

    std::string getString(const char *fieldName) const {
        jfieldID fid = env->GetFieldID(clazz, fieldName, jni_refs::types::string);
        auto jstr = (jstring) env->GetObjectField(obj, fid);

        if (jstr == nullptr) return "";

        auto chars = env->GetStringUTFChars(jstr, nullptr);
        std::string result(chars);
        env->ReleaseStringUTFChars(jstr, chars);

        env->DeleteLocalRef(jstr);

        return result;
    }

    // Handles nested Kotlin objects (e.g., getting the executionConfig from the EngineConfig)
    JniConfigReader getNestedObject(const char *fieldName, const char *signature) const {
        auto fid = env->GetFieldID(clazz, fieldName, signature);
        auto nestedObj = env->GetObjectField(obj, fid);
        return {env, nestedObj};
    }
};
