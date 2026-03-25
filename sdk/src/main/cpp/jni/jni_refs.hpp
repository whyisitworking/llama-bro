#pragma once

#include "jni.h"

namespace jni_refs {
    inline JavaVM *vm = nullptr;

    namespace exceptions {
        inline jclass c_runtime_exp;
    }

    namespace session {
        inline jfieldID f_result_token;
        inline jfieldID f_result_code;
        inline jfieldID f_result_is_complete;
    }

    namespace engine {
        inline jmethodID m_listener_on_progress;
    }

    namespace types {
        inline constexpr const char *string = "Ljava/lang/String;";
        inline constexpr const char *boolean = "Z";
        inline constexpr const char *int32 = "I";
        inline constexpr const char *float32 = "F";
        inline constexpr const char *long64 = "J";
        inline constexpr const char *double64 = "D";
    }
}
