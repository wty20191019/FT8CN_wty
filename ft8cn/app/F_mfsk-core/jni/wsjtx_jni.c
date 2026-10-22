// SPDX-License-Identifier: GPL-3.0-or-later
//
// libwsjtx_Android.so — 很薄的一层 JNI，把 mfsk-ffi 的 C ABI 暴露给
// com.bg7yoz.ft8cn.wsjtx.WsjtxDecoder。
//
// 本文件不做任何解码逻辑：解码全部在 mfsk-core / mfsk-ffi 中完成。
// 这里只负责：
//   1. 持有 session 句柄；
//   2. 把 float[] 音频交给 mfsk_session_decode_f32；
//   3. 把 MfskDecode 行格式化成 Java String[]。
//
// 行格式（UTF-8，\t 分隔）：
//   text \t snr_db \t dt_sec \t freq_hz \t mode \t flags
//
// 编译：scripts/build.ps1

#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>

#include "mfsk.h"

// 与 WsjtxDecoder.java 中的包名/类名严格一致。
#define WSJTX_MODE_FT8 0
#define WSJTX_MODE_FT4 1

typedef struct wsjtx_handle {
    struct MfskDecodeSession *session;
    uint32_t mode;
} wsjtx_handle;

static void throw_illegal_state(JNIEnv *env, const char *msg) {
    jclass cls = (*env)->FindClass(env, "java/lang/IllegalStateException");
    if (cls != NULL) {
        (*env)->ThrowNew(env, cls, msg != NULL ? msg : "wsjtx native error");
        (*env)->DeleteLocalRef(env, cls);
    }
}

static const char *session_error(wsjtx_handle *h) {
    const char *detail = (h != NULL) ? mfsk_session_last_error(h->session) : NULL;
    if (detail == NULL) detail = mfsk_last_error();
    return detail != NULL ? detail : "unknown mfsk error";
}

static jobjectArray rows_to_java(JNIEnv *env, const struct MfskDecode *rows, size_t n) {
    jclass string_cls = (*env)->FindClass(env, "java/lang/String");
    if (string_cls == NULL) return NULL;

    jobjectArray out = (*env)->NewObjectArray(env, (jsize)n, string_cls, NULL);
    if (out == NULL) return NULL;

    for (size_t i = 0; i < n; ++i) {
        char line[256];
        const char *text = rows[i].text;
        if (text == NULL) text = "";

        // 文本中不会出现 \t；FT8/FT4 报文只使用可打印 ASCII。
        snprintf(line, sizeof(line), "%s\t%.1f\t%.2f\t%.0f\t%d\t%d",
                 text,
                 (double)rows[i].snr_db,
                 (double)rows[i].dt_sec,
                 (double)rows[i].freq_hz,
                 (int)rows[i].mode,
                 (int)rows[i].flags);

        jstring s = (*env)->NewStringUTF(env, line);
        if (s == NULL) {
            (*env)->DeleteLocalRef(env, string_cls);
            return NULL;
        }
        (*env)->SetObjectArrayElement(env, out, (jsize)i, s);
        (*env)->DeleteLocalRef(env, s);
    }

    (*env)->DeleteLocalRef(env, string_cls);
    return out;
}

JNIEXPORT jlong JNICALL
Java_com_bg7yoz_ft8cn_wsjtx_WsjtxDecoder_nativeOpen(JNIEnv *env, jclass cls, jint mode) {
    (void)cls;

    if (mode != WSJTX_MODE_FT8 && mode != WSJTX_MODE_FT4) {
        throw_illegal_state(env, "unsupported mode (only FT8=0 / FT4=1)");
        return 0;
    }

    enum MfskStatus status = MFSK_STATUS_OK;
    // NULL = 该模式的默认参数。FT8 默认覆盖整个音频通带。
    struct MfskDecodeSession *session =
        mfsk_session_open((uint32_t)mode, NULL, &status);

    if (session == NULL) {
        throw_illegal_state(env, mfsk_last_error());
        return 0;
    }

    wsjtx_handle *h = (wsjtx_handle *)calloc(1, sizeof(*h));
    if (h == NULL) {
        mfsk_session_close(session);
        throw_illegal_state(env, "out of memory");
        return 0;
    }

    h->session = session;
    h->mode = (uint32_t)mode;
    return (jlong)(intptr_t)h;
}

// 共用实现。freq_min/freq_max 有效时只在该频段内搜索；否则沿用模式默认范围。
static jobjectArray decode_impl(JNIEnv *env, jlong handle, jfloatArray audio,
                                jint sample_rate, jboolean deep,
                                jfloat freq_min, jfloat freq_max) {
    wsjtx_handle *h = (wsjtx_handle *)(intptr_t)handle;
    if (h == NULL || h->session == NULL) {
        throw_illegal_state(env, "decoder is closed");
        return NULL;
    }
    if (audio == NULL) {
        throw_illegal_state(env, "audio is null");
        return NULL;
    }

    jsize n = (*env)->GetArrayLength(env, audio);
    if (n <= 0) {
        throw_illegal_state(env, "audio is empty");
        return NULL;
    }

    jfloat *samples = (*env)->GetFloatArrayElements(env, audio, NULL);
    if (samples == NULL) {
        throw_illegal_state(env, "cannot access audio array");
        return NULL;
    }

    struct MfskDecodeParams params;
    enum MfskStatus status = mfsk_decode_params_init(h->mode, &params);
    if (status != MFSK_STATUS_OK) {
        (*env)->ReleaseFloatArrayElements(env, audio, samples, JNI_ABORT);
        throw_illegal_state(env, session_error(h));
        return NULL;
    }

    if (deep) {
        // FT8 的深度档：完整 LLR 阶梯 + BP + OSD 回退，并采用更宽松的
        // 接受门限。mfsk-core 在 FT8/FT4 上默认会自己做 SIC（减法），
        // 因此这里不需要 FT8CN 原来的 ReBuildSignal 循环。
        params.depth = MFSK_DECODE_DEPTH_BP_ALL_OSD;
        params.strictness = MFSK_STRICTNESS_DEEP;
    }

    // 频谱分段：只在该频段内做候选搜索和解码。
    if (freq_min > 0.0f && freq_max > freq_min) {
        params.freq_min_hz = freq_min;
        params.freq_max_hz = freq_max;
    }

    size_t cap = 256;
    size_t out_len = 0;
    struct MfskDecode *rows = (struct MfskDecode *)malloc(cap * sizeof(*rows));
    if (rows == NULL) {
        (*env)->ReleaseFloatArrayElements(env, audio, samples, JNI_ABORT);
        throw_illegal_state(env, "out of memory");
        return NULL;
    }

    uint32_t rate = sample_rate > 0 ? (uint32_t)sample_rate : 12000u;
    status = mfsk_session_decode_f32(h->session, samples, (uintptr_t)n, rate,
                                     &params, rows, cap, &out_len);

    // 缓冲区不够时，ABI 会把所需数量写回 out_len，不会截断。
    if (status == MFSK_STATUS_INVALID_ARG && out_len > cap) {
        struct MfskDecode *grown =
            (struct MfskDecode *)realloc(rows, out_len * sizeof(*rows));
        if (grown == NULL) {
            free(rows);
            (*env)->ReleaseFloatArrayElements(env, audio, samples, JNI_ABORT);
            throw_illegal_state(env, "out of memory");
            return NULL;
        }
        rows = grown;
        cap = out_len;
        status = mfsk_session_decode_f32(h->session, samples, (uintptr_t)n, rate,
                                         &params, rows, cap, &out_len);
    }

    (*env)->ReleaseFloatArrayElements(env, audio, samples, JNI_ABORT);

    if (status != MFSK_STATUS_OK) {
        free(rows);
        throw_illegal_state(env, session_error(h));
        return NULL;
    }

    jobjectArray result = rows_to_java(env, rows, out_len);
    free(rows);
    return result;
}

JNIEXPORT jobjectArray JNICALL
Java_com_bg7yoz_ft8cn_wsjtx_WsjtxDecoder_nativeDecode(
        JNIEnv *env, jclass cls, jlong handle, jfloatArray audio,
        jint sample_rate, jboolean deep) {
    (void)cls;
    return decode_impl(env, handle, audio, sample_rate, deep, 0.0f, 0.0f);
}

JNIEXPORT jobjectArray JNICALL
Java_com_bg7yoz_ft8cn_wsjtx_WsjtxDecoder_nativeDecodeBand(
        JNIEnv *env, jclass cls, jlong handle, jfloatArray audio,
        jint sample_rate, jboolean deep, jfloat freq_min, jfloat freq_max) {
    (void)cls;
    return decode_impl(env, handle, audio, sample_rate, deep, freq_min, freq_max);
}

JNIEXPORT void JNICALL
Java_com_bg7yoz_ft8cn_wsjtx_WsjtxDecoder_nativeClose(JNIEnv *env, jclass cls, jlong handle) {
    (void)env;
    (void)cls;

    wsjtx_handle *h = (wsjtx_handle *)(intptr_t)handle;
    if (h == NULL) return;

    if (h->session != NULL) {
        mfsk_session_close(h->session);
        h->session = NULL;
    }
    free(h);
}

JNIEXPORT jint JNICALL
Java_com_bg7yoz_ft8cn_wsjtx_WsjtxDecoder_nativeVersion(JNIEnv *env, jclass cls) {
    (void)env;
    (void)cls;
    // mfsk-ffi 的版本号是 uint32_t（见 mfsk.h 的 mfsk_version）。
    return (jint)mfsk_version();
}

JNIEXPORT jstring JNICALL
Java_com_bg7yoz_ft8cn_wsjtx_WsjtxDecoder_nativeModeName(JNIEnv *env, jclass cls, jint mode) {
    (void)cls;
    const char *name = mfsk_mode_name((uint32_t)mode);
    return (*env)->NewStringUTF(env, name != NULL ? name : "unknown");
}
