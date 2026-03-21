#pragma once

#include <string_view>
#include <asm/hwcap.h>
#include <sys/auxv.h>

std::string_view resolve_best_ggml_backend() {
    unsigned long hwcap = getauxval(AT_HWCAP);
    unsigned long hwcap2 = getauxval(AT_HWCAP2);

    bool has_fp16 = hwcap & HWCAP_ASIMDHP;
    bool has_dotprod = hwcap & HWCAP_ASIMDDP;
    bool has_sve = hwcap & HWCAP_SVE;
    bool has_sve2 = hwcap2 & HWCAP2_SVE2;
    bool has_i8mm = hwcap2 & HWCAP2_I8MM;

    // Cascade down from highest architectural requirements to lowest
    if (has_sve && has_sve2 && has_i8mm && has_fp16 && has_dotprod) {
        return "libggml-cpu-android_armv9.0_1.so";
    }

    if (has_i8mm && has_fp16 && has_dotprod) {
        return "libggml-cpu-android_armv8.6_1.so";
    }

    if (has_fp16 && has_dotprod) {
        return "libggml-cpu-android_armv8.2_2.so";
    }

    if (has_dotprod) {
        return "libggml-cpu-android_armv8.2_1.so";
    }

    return "libggml-cpu-android_armv8.0_1.so";
}
