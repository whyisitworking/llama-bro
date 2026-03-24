#pragma once

#include <sys/auxv.h>
#include <asm/hwcap.h>
#include "utils/log.hpp"

static inline bool has_bit(unsigned long value, unsigned long bit) {
    return (value & bit) != 0;
}

/**
 * Probes the CPU for features and chooses the most optimized backend for GGML
 * for fastest inference without encountering SIGILL
 *
 * @return Path to the best GGML backend
 */
const char *resolve_best_ggml_backend() {
    const unsigned long hwcap = getauxval(AT_HWCAP);
    const unsigned long hwcap2 = getauxval(AT_HWCAP2);

    bool fp16 = has_bit(hwcap, HWCAP_ASIMDHP);
    bool dotprod = has_bit(hwcap, HWCAP_ASIMDDP);
    bool sve = has_bit(hwcap, HWCAP_SVE);

    bool sve2 = has_bit(hwcap2, HWCAP2_SVE2);
    bool i8mm = has_bit(hwcap2, HWCAP2_I8MM);
    bool sme = has_bit(hwcap2, HWCAP2_SME);

    // Defensive normalization against bad kernel reports
    if (!dotprod) {
        fp16 = false;
        i8mm = false;
        sve2 = false;
        sme = false;
    }

    if (!fp16) {
        i8mm = false;
        sve2 = false;
        sme = false;
    }

    if (!i8mm) {
        sve2 = false;
        sme = false;
    }

    if (!sve) {
        sme = false;
    }

    if (dotprod && fp16 && i8mm && sve && sve2 && sme) {
        LOGI("DOTPROD + FP16 + I8MM + SVE + SVE2 + SME = libggml-cpu-android_armv9.2_2.so");
        return "libggml-cpu-android_armv9.2_2.so";
    }

    if (dotprod && fp16 && i8mm && sve && sme) {
        LOGI("DOTPROD + FP16 + I8MM + SVE + SME = libggml-cpu-android_armv9.2_1.so");
        return "libggml-cpu-android_armv9.2_1.so";
    }

    if (dotprod && fp16 && i8mm && sve2) {
        LOGI("DOTPROD + FP16 + I8MM + SVE2 = libggml-cpu-android_armv9.0_1.so");
        return "libggml-cpu-android_armv9.0_1.so";
    }

    if (dotprod && fp16 && i8mm) {
        LOGI("DOTPROD + FP16 + I8MM = libggml-cpu-android_armv8.6_1.so");
        return "libggml-cpu-android_armv8.6_1.so";
    }

    if (dotprod && fp16) {
        LOGI("DOTPROD + FP16 = libggml-cpu-android_armv8.2_2.so");
        return "libggml-cpu-android_armv8.2_2.so";
    }

    if (dotprod) {
        LOGI("DOTPROD = libggml-cpu-android_armv8.2_1.so");
        return "libggml-cpu-android_armv8.2_1.so";
    }

    LOGI("Base = libggml-cpu-android_armv8.0_1.so");
    return "libggml-cpu-android_armv8.0_1.so";
}
