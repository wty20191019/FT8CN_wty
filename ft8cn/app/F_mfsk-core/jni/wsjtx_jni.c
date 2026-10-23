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
#include <unistd.h>
#include <android/log.h>

#include "mfsk.h"

#define LOG_TAG "wsjtx_jni"

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

// --- rayon (mfsk parallel) 线程池配置 --------------------------------------
// 带 parallel 特性构建时，mfsk 用 rayon 并行处理候选。Android 上这些
// 工作线程需要 Attach 到 ART，否则依赖 JNI 的操作不安全。
static JavaVM *g_java_vm = NULL;

static void worker_start(uint32_t index, void *user_data) {
    (void)index;
    (void)user_data;
    JNIEnv *env = NULL;
    if (g_java_vm != NULL) {
        (*g_java_vm)->AttachCurrentThread(g_java_vm, &env, NULL);
    }
}

static void worker_stop(uint32_t index, void *user_data) {
    (void)index;
    (void)user_data;
    if (g_java_vm != NULL) {
        (*g_java_vm)->DetachCurrentThread(g_java_vm);
    }
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    g_java_vm = vm;

    long cores = sysconf(_SC_NPROCESSORS_ONLN);
    if (cores < 1) cores = 1;
    if (cores > 8) cores = 8;

    struct MfskRuntimeConfig cfg;
    memset(&cfg, 0, sizeof(cfg));
    cfg.size = (uint32_t)sizeof(cfg);
    cfg.num_threads = (uint32_t)cores;
    cfg.on_thread_start = worker_start;
    cfg.on_thread_stop = worker_stop;
    // 没有 parallel 特性时返回 UNSUPPORTED，忽略即可。
    mfsk_runtime_configure(&cfg);
    return JNI_VERSION_1_6;
}

// FT8/FT4 报文只应包含可打印 ASCII。底层偶尔会返回带高位字节的
// 无效文本，直接交给 NewStringUTF 会触发 JNI abort（非法 UTF-8），
// 因此这里先过滤掉这类行，避免整个进程被带崩。
static int is_valid_text(const char *text) {
    if (text == NULL || text[0] == '\0') return 0;
    for (size_t i = 0; i < 64; ++i) {
        unsigned char c = (unsigned char)text[i];
        if (c == '\0') return 1;
        if (c < 0x20 || c > 0x7e) return 0;
    }
    return 0; // 超长文本同样视为异常
}

static int is_valid_row(const struct MfskDecode *row) {
    if (!is_valid_text(row->text)) return 0;
    // FT8/FT4 的 mode 只有 0/1；数值字段限定在物理可能的范围内，
    // 用来拦住底层异常返回的未初始化行。
    if (row->mode != WSJTX_MODE_FT8 && row->mode != WSJTX_MODE_FT4) return 0;
    if (!(row->freq_hz >= 0.0f && row->freq_hz <= 6000.0f)) return 0;
    if (!(row->dt_sec >= -5.0f && row->dt_sec <= 5.0f)) return 0;
    if (!(row->snr_db >= -60.0f && row->snr_db <= 60.0f)) return 0;
    return 1;
}

static jobjectArray rows_to_java(JNIEnv *env, const struct MfskDecode *rows, size_t n) {
    jclass string_cls = (*env)->FindClass(env, "java/lang/String");
    if (string_cls == NULL) return NULL;

    size_t valid = 0;
    for (size_t i = 0; i < n; ++i) {
        if (is_valid_row(&rows[i])) ++valid;
    }
    if (valid < n) {
        __android_log_print(ANDROID_LOG_WARN, LOG_TAG,
                            "skip %zu invalid row(s)", n - valid);
    }

    jobjectArray out = (*env)->NewObjectArray(env, (jsize)valid, string_cls, NULL);
    if (out == NULL) {
        (*env)->DeleteLocalRef(env, string_cls);
        return NULL;
    }

    size_t j = 0;
    for (size_t i = 0; i < n; ++i) {
        const char *text = rows[i].text;
        if (!is_valid_row(&rows[i])) continue;

        char line[256];
        int written = snprintf(line, sizeof(line), "%s\t%.1f\t%.2f\t%.0f\t%d\t%d",
                               text,
                               (double)rows[i].snr_db,
                               (double)rows[i].dt_sec,
                               (double)rows[i].freq_hz,
                               (int)rows[i].mode,
                               (int)rows[i].flags);
        if (written < 0 || written >= (int)sizeof(line)) {
            __android_log_print(ANDROID_LOG_WARN, LOG_TAG,
                                "skip row with oversized text");
            continue;
        }

        jstring s = (*env)->NewStringUTF(env, line);
        if (s == NULL) {
            (*env)->DeleteLocalRef(env, string_cls);
            return NULL;
        }
        (*env)->SetObjectArrayElement(env, out, (jsize)j++, s);
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
                                jfloat freq_min, jfloat freq_max, jint profile) {
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

    // 速度档位（实验用）：
    //   0=默认，1=关闭OSD，2=关闭OSD+单遍，3=单遍，4=严格门限+单遍
    //   5=sic_early，6=2轮SIC，7=1轮SIC，8=sic_early+关闭OSD，9=1轮SIC+关闭OSD
    switch (profile) {
        case 1:
            params.depth = MFSK_DECODE_DEPTH_BP_ALL;
            break;
        case 2:
            params.depth = MFSK_DECODE_DEPTH_BP_ALL;
            params.single_pass = true;
            break;
        case 3:
            params.single_pass = true;
            break;
        case 4:
            params.strictness = MFSK_STRICTNESS_STRICT;
            params.single_pass = true;
            break;
        case 5:
            params.sic_early = true;
            break;
        case 6:
            params.sic_rounds = 2;
            break;
        case 7:
            params.sic_rounds = 1;
            break;
        case 8:
            params.sic_early = true;
            params.depth = MFSK_DECODE_DEPTH_BP_ALL;
            break;
        case 9:
            params.sic_rounds = 1;
            params.depth = MFSK_DECODE_DEPTH_BP_ALL;
            break;
        default:
            break;
    }

    size_t cap = 256;
    size_t out_len = 0;
    struct MfskDecode *rows = (struct MfskDecode *)malloc(cap * sizeof(*rows));
    if (rows == NULL) {
        (*env)->ReleaseFloatArrayElements(env, audio, samples, JNI_ABORT);
        throw_illegal_state(env, "out of memory");
        return NULL;
    }
    memset(rows, 0, cap * sizeof(*rows));

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
        memset(rows + cap, 0, (out_len - cap) * sizeof(*rows));
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
    return decode_impl(env, handle, audio, sample_rate, deep, 0.0f, 0.0f, 0);
}

JNIEXPORT jobjectArray JNICALL
Java_com_bg7yoz_ft8cn_wsjtx_WsjtxDecoder_nativeDecodeBand(
        JNIEnv *env, jclass cls, jlong handle, jfloatArray audio,
        jint sample_rate, jboolean deep, jfloat freq_min, jfloat freq_max) {
    (void)cls;
    return decode_impl(env, handle, audio, sample_rate, deep, freq_min, freq_max, 0);
}

JNIEXPORT jobjectArray JNICALL
Java_com_bg7yoz_ft8cn_wsjtx_WsjtxDecoder_nativeDecodeBandEx(
        JNIEnv *env, jclass cls, jlong handle, jfloatArray audio,
        jint sample_rate, jboolean deep, jfloat freq_min, jfloat freq_max,
        jint profile) {
    (void)cls;
    return decode_impl(env, handle, audio, sample_rate, deep,
                       freq_min, freq_max, profile);
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
