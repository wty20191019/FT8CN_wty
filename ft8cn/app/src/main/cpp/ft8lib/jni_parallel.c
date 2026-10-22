/*
 * JNI bridge for the parallel FT8 decoder.
 *
 * The decoder core is the original kgoba/ft8_lib (see README/LICENSE in this
 * directory).  This file only adds:
 *   - one shared waterfall per slot,
 *   - a frequency-range limited candidate search,
 *   - a small per-worker candidate/result buffer,
 *   - waterfall-domain signal subtraction for deep passes.
 */

#include <jni.h>
#include <android/log.h>
#include <math.h>
#include <stdlib.h>
#include <string.h>
#include <stdbool.h>
#include <pthread.h>

#include "common/monitor.h"
#include "ft8/decode.h"
#include "ft8/encode.h"
#include "ft8/message.h"

#define TAG "Ft8Parallel"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define MAX_CANDIDATES 512
#define MAX_TOKENS 16
#define MAX_TOKEN_LEN 32

typedef struct
{
    monitor_t mon;
    int sample_rate;
    int num_samples;
    int max_candidates;
    int max_iterations;
} parallel_decoder_t;

typedef struct
{
    parallel_decoder_t* dec;
    ftx_candidate_t candidates[MAX_CANDIDATES];
    int num_candidates;
    ftx_message_t message;
    ftx_decode_status_t status;
    float freq_hz;
    float time_sec;
    int score;
    int snr;
    bool valid;
} parallel_worker_t;

/* ------------------------------------------------------------------------- */
/* Callsign hash table                                                       */
/*                                                                           */
/* ftx_message_decode() needs a hash interface for non-standard callsigns.    */
/* The table is filled by messages decoded earlier in the same session, so    */
/* hashed calls can be resolved to plain text when possible.                 */
/* ------------------------------------------------------------------------- */

#define CALLSIGN_HASHTABLE_SIZE 512

static struct
{
    char callsign[12];
    uint32_t hash;
} callsign_hashtable[CALLSIGN_HASHTABLE_SIZE];
static int callsign_hashtable_size;
static pthread_mutex_t callsign_mutex = PTHREAD_MUTEX_INITIALIZER;

static void callsign_hashtable_add(const char* callsign, uint32_t hash)
{
    uint16_t hash10 = (hash >> 12) & 0x3FFu;
    int idx = (hash10 * 23) % CALLSIGN_HASHTABLE_SIZE;
    int probes = 0;
    while (callsign_hashtable[idx].callsign[0] != '\0' && probes < CALLSIGN_HASHTABLE_SIZE)
    {
        if (((callsign_hashtable[idx].hash & 0x3FFFFFu) == hash) &&
            (strcmp(callsign_hashtable[idx].callsign, callsign) == 0))
            return;
        idx = (idx + 1) % CALLSIGN_HASHTABLE_SIZE;
        ++probes;
    }
    if (probes >= CALLSIGN_HASHTABLE_SIZE) return;
    callsign_hashtable_size++;
    strncpy(callsign_hashtable[idx].callsign, callsign, 11);
    callsign_hashtable[idx].callsign[11] = '\0';
    callsign_hashtable[idx].hash = hash;
}

static bool callsign_hashtable_lookup(ftx_callsign_hash_type_t hash_type, uint32_t hash,
                                      char* callsign)
{
    uint8_t shift = (hash_type == FTX_CALLSIGN_HASH_10_BITS) ? 12
                    : (hash_type == FTX_CALLSIGN_HASH_12_BITS ? 10 : 0);
    uint16_t hash10 = (hash >> (12 - shift)) & 0x3FFu;
    int idx = (hash10 * 23) % CALLSIGN_HASHTABLE_SIZE;
    int probes = 0;
    while (callsign_hashtable[idx].callsign[0] != '\0' && probes < CALLSIGN_HASHTABLE_SIZE)
    {
        if (((callsign_hashtable[idx].hash & 0x3FFFFFu) >> shift) == hash)
        {
            strcpy(callsign, callsign_hashtable[idx].callsign);
            return true;
        }
        idx = (idx + 1) % CALLSIGN_HASHTABLE_SIZE;
        ++probes;
    }
    callsign[0] = '\0';
    return false;
}

static ftx_callsign_hash_interface_t callsign_hash_if = {
    .lookup_hash = callsign_hashtable_lookup,
    .save_hash = callsign_hashtable_add,
};

/* ------------------------------------------------------------------------- */
/* Small helpers                                                             */
/* ------------------------------------------------------------------------- */

static bool is_call_token(const char* s)
{
    if (s == NULL) return false;
    size_t n = strlen(s);
    if (n < 3 || n > 13) return false;
    if (strcmp(s, "CQ") == 0 || strcmp(s, "QRZ") == 0 || strcmp(s, "DE") == 0 ||
        strcmp(s, "RRR") == 0 || strcmp(s, "RR73") == 0 || strcmp(s, "73") == 0 ||
        strcmp(s, "TU;") == 0 || strcmp(s, "R") == 0)
        return false;

    bool has_letter = false;
    bool has_digit = false;
    for (size_t i = 0; i < n; ++i)
    {
        char c = s[i];
        if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) has_letter = true;
        else if (c >= '0' && c <= '9') has_digit = true;
        else if (c != '/' && c != '<' && c != '>') return false;
    }
    return has_letter && has_digit;
}

static bool is_grid_token(const char* s)
{
    if (s == NULL) return false;
    size_t n = strlen(s);
    if (n != 4 && n != 6) return false;
    if (s[0] < 'A' || s[0] > 'R') return false;
    if (s[1] < 'A' || s[1] > 'R') return false;
    if (s[2] < '0' || s[2] > '9') return false;
    if (s[3] < '0' || s[3] > '9') return false;
    return true;
}

static int parse_report(const char* s)
{
    if (s == NULL) return -100;
    const char* p = s;
    if (*p == '+' || *p == '-') ++p;
    if (*p < '0' || *p > '9') return -100;
    return atoi(s);
}

static void copy_trimmed(char* dst, size_t dst_size, const char* src)
{
    if (dst == NULL || dst_size == 0) return;
    dst[0] = '\0';
    if (src == NULL) return;
    while (*src == ' ') ++src;
    size_t len = strlen(src);
    while (len > 0 && src[len - 1] == ' ') --len;
    if (len >= dst_size) len = dst_size - 1;
    memcpy(dst, src, len);
    dst[len] = '\0';
}

/*
 * Very small message-field extractor.
 *
 * The displayed text always comes from ftx_message_decode(), so this parser is
 * only responsible for the fields used by FT8CN's QSO/QTH logic.  It handles
 * the common standard/CQ/non-standard layouts; the text itself is authoritative.
 */
static void fill_message_fields(const char* text, char* call_to, size_t call_to_size,
                                char* call_from, size_t call_from_size,
                                char* extra, size_t extra_size,
                                char* grid, size_t grid_size,
                                char* modifier, size_t modifier_size,
                                int* report)
{
    call_to[0] = '\0';
    call_from[0] = '\0';
    extra[0] = '\0';
    grid[0] = '\0';
    modifier[0] = '\0';
    *report = -100;

    char buf[128];
    copy_trimmed(buf, sizeof(buf), text);
    if (buf[0] == '\0') return;

    char* tokens[MAX_TOKENS];
    int n = 0;
    char* p = buf;
    while (*p != '\0' && n < MAX_TOKENS)
    {
        while (*p == ' ') ++p;
        if (*p == '\0') break;
        tokens[n++] = p;
        while (*p != '\0' && *p != ' ') ++p;
        if (*p == ' ') { *p = '\0'; ++p; }
    }

    int call_count = 0;
    char* calls[2] = { NULL, NULL };
    char* grid_token = NULL;
    char* report_token = NULL;
    char* cq_token = NULL;

    for (int i = 0; i < n; ++i)
    {
        char* t = tokens[i];
        if (strcmp(t, "CQ") == 0)
        {
            cq_token = t;
            if (i + 1 < n && !is_call_token(tokens[i + 1]))
            {
                copy_trimmed(modifier, modifier_size, tokens[i + 1]);
            }
        }
        else if (is_call_token(t))
        {
            if (call_count < 2) calls[call_count] = t;
            ++call_count;
        }
        else if (is_grid_token(t))
        {
            grid_token = t;
        }
        else if (parse_report(t) != -100 && report_token == NULL)
        {
            report_token = t;
        }
    }

    if (cq_token != NULL)
    {
        copy_trimmed(call_to, call_to_size, "CQ");
        if (call_count >= 1) copy_trimmed(call_from, call_from_size, calls[0]);
    }
    else
    {
        if (call_count >= 1) copy_trimmed(call_to, call_to_size, calls[0]);
        if (call_count >= 2) copy_trimmed(call_from, call_from_size, calls[1]);
    }

    if (grid_token != NULL)
    {
        copy_trimmed(grid, grid_size, grid_token);
        copy_trimmed(extra, extra_size, grid_token);
    }
    if (report_token != NULL)
    {
        *report = parse_report(report_token);
        copy_trimmed(extra, extra_size, report_token);
    }
    if (extra[0] == '\0')
    {
        /* Prefer a trailing token such as RR73/73/RRR; otherwise leave empty. */
        for (int i = n - 1; i >= 0; --i)
        {
            char* t = tokens[i];
            if (strcmp(t, "RR73") == 0 || strcmp(t, "RRR") == 0 || strcmp(t, "73") == 0 ||
                strcmp(t, "R") == 0 || strcmp(t, "TU;") == 0)
            {
                copy_trimmed(extra, extra_size, t);
                break;
            }
        }
    }
}

/* ------------------------------------------------------------------------- */
/* Native API                                                                */
/* ------------------------------------------------------------------------- */

JNIEXPORT jlong JNICALL
Java_com_bg7yoz_ft8cn_ft8listener_Ft8LibJni_nativeCreate(
    JNIEnv* env, jclass clazz,
    jint sample_rate, jint num_samples,
    jfloat f_min, jfloat f_max,
    jint max_candidates, jint max_iterations)
{
    (void)env; (void)clazz;
    if (sample_rate <= 0 || num_samples <= 0 || f_max <= f_min)
        return 0;

    parallel_decoder_t* dec = (parallel_decoder_t*)calloc(1, sizeof(parallel_decoder_t));
    if (dec == NULL) return 0;

    dec->sample_rate = sample_rate;
    dec->num_samples = num_samples;
    dec->max_candidates = max_candidates > MAX_CANDIDATES ? MAX_CANDIDATES : max_candidates;
    dec->max_iterations = max_iterations;

    monitor_config_t cfg = {
        .f_min = f_min,
        .f_max = f_max,
        .sample_rate = sample_rate,
        .time_osr = 2,
        .freq_osr = 2,
        .protocol = FTX_PROTOCOL_FT8,
    };
    monitor_init(&dec->mon, &cfg);
    LOGI("nativeCreate: sr=%d samples=%d bins=%d minBin=%d maxCand=%d iter=%d",
         sample_rate, num_samples, dec->mon.wf.num_bins, dec->mon.min_bin,
         dec->max_candidates, dec->max_iterations);
    return (jlong)(intptr_t)dec;
}

JNIEXPORT void JNICALL
Java_com_bg7yoz_ft8cn_ft8listener_Ft8LibJni_nativePress(
    JNIEnv* env, jclass clazz, jlong handle, jfloatArray audio)
{
    (void)clazz;
    parallel_decoder_t* dec = (parallel_decoder_t*)(intptr_t)handle;
    if (dec == NULL || audio == NULL) return;

    jsize len = (*env)->GetArrayLength(env, audio);
    if (len <= 0) return;
    jfloat* data = (*env)->GetFloatArrayElements(env, audio, NULL);
    if (data == NULL) return;

    int limit = len < dec->num_samples ? len : dec->num_samples;
    for (int pos = 0; pos + dec->mon.block_size <= limit; pos += dec->mon.block_size)
        monitor_process(&dec->mon, data + pos);

    (*env)->ReleaseFloatArrayElements(env, audio, data, JNI_ABORT);
}

JNIEXPORT jlong JNICALL
Java_com_bg7yoz_ft8cn_ft8listener_Ft8LibJni_nativeCreateWorker(
    JNIEnv* env, jclass clazz, jlong decoder)
{
    (void)env; (void)clazz;
    parallel_decoder_t* dec = (parallel_decoder_t*)(intptr_t)decoder;
    if (dec == NULL) return 0;
    parallel_worker_t* w = (parallel_worker_t*)calloc(1, sizeof(parallel_worker_t));
    if (w == NULL) return 0;
    w->dec = dec;
    return (jlong)(intptr_t)w;
}

JNIEXPORT jint JNICALL
Java_com_bg7yoz_ft8cn_ft8listener_Ft8LibJni_nativeFindRange(
    JNIEnv* env, jclass clazz, jlong worker, jint bin_lo, jint bin_hi)
{
    (void)env; (void)clazz;
    parallel_worker_t* w = (parallel_worker_t*)(intptr_t)worker;
    if (w == NULL || w->dec == NULL) return 0;
    w->num_candidates = ftx_find_candidates(&w->dec->mon.wf,
                                            w->dec->max_candidates,
                                            w->candidates,
                                            10, bin_lo, bin_hi);
    return w->num_candidates;
}

JNIEXPORT jboolean JNICALL
Java_com_bg7yoz_ft8cn_ft8listener_Ft8LibJni_nativeDecode(
    JNIEnv* env, jclass clazz, jlong worker, jint idx, jint max_iterations)
{
    (void)env; (void)clazz;
    parallel_worker_t* w = (parallel_worker_t*)(intptr_t)worker;
    w->valid = false;
    if (w == NULL || w->dec == NULL || idx < 0 || idx >= w->num_candidates)
        return JNI_FALSE;

    ftx_candidate_t* cand = &w->candidates[idx];
    if (!ftx_decode_candidate(&w->dec->mon.wf, cand,
                              max_iterations > 0 ? max_iterations : w->dec->max_iterations,
                              &w->message, &w->status))
        return JNI_FALSE;

    w->freq_hz = (w->dec->mon.min_bin + cand->freq_offset +
                  (float)cand->freq_sub / w->dec->mon.wf.freq_osr) /
                 w->dec->mon.symbol_period;
    w->time_sec = (cand->time_offset +
                   (float)cand->time_sub / w->dec->mon.wf.time_osr) *
                  w->dec->mon.symbol_period;
    w->score = cand->score;
    /* ft8_lib does not compute SNR; use the same rough estimate as its demo. */
    w->snr = (int)(cand->score * 0.5f);
    w->valid = true;
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_bg7yoz_ft8cn_ft8listener_Ft8LibJni_nativeFillMessage(
    JNIEnv* env, jclass clazz, jlong worker, jobject msg)
{
    (void)clazz;
    parallel_worker_t* w = (parallel_worker_t*)(intptr_t)worker;
    if (w == NULL || !w->valid || msg == NULL) return;

    jclass cls = (*env)->GetObjectClass(env, msg);

    jfieldID f_isValid = (*env)->GetFieldID(env, cls, "isValid", "Z");
    jfieldID f_snr = (*env)->GetFieldID(env, cls, "snr", "I");
    jfieldID f_time = (*env)->GetFieldID(env, cls, "time_sec", "F");
    jfieldID f_freq = (*env)->GetFieldID(env, cls, "freq_hz", "F");
    jfieldID f_score = (*env)->GetFieldID(env, cls, "score", "I");
    jfieldID f_hash = (*env)->GetFieldID(env, cls, "messageHash", "I");
    jfieldID f_i3 = (*env)->GetFieldID(env, cls, "i3", "I");
    jfieldID f_n3 = (*env)->GetFieldID(env, cls, "n3", "I");
    jfieldID f_from = (*env)->GetFieldID(env, cls, "callsignFrom", "Ljava/lang/String;");
    jfieldID f_to = (*env)->GetFieldID(env, cls, "callsignTo", "Ljava/lang/String;");
    jfieldID f_extra = (*env)->GetFieldID(env, cls, "extraInfo", "Ljava/lang/String;");
    jfieldID f_grid = (*env)->GetFieldID(env, cls, "maidenGrid", "Ljava/lang/String;");
    jfieldID f_report = (*env)->GetFieldID(env, cls, "report", "I");
    jfieldID f_modifier = (*env)->GetFieldID(env, cls, "modifier", "Ljava/lang/String;");
    jfieldID f_text = (*env)->GetFieldID(env, cls, "decodedText", "Ljava/lang/String;");
    jfieldID f_payload = (*env)->GetFieldID(env, cls, "payload", "[B");

    (*env)->SetBooleanField(env, msg, f_isValid, JNI_TRUE);
    (*env)->SetIntField(env, msg, f_snr, w->snr);
    (*env)->SetFloatField(env, msg, f_time, w->time_sec);
    (*env)->SetFloatField(env, msg, f_freq, w->freq_hz);
    (*env)->SetIntField(env, msg, f_score, w->score);
    (*env)->SetIntField(env, msg, f_hash, (jint)w->message.hash);
    (*env)->SetIntField(env, msg, f_i3, (jint)ftx_message_get_i3(&w->message));
    (*env)->SetIntField(env, msg, f_n3, (jint)ftx_message_get_n3(&w->message));

    char text[FTX_MAX_MESSAGE_LENGTH];
    ftx_message_offsets_t offsets;
    pthread_mutex_lock(&callsign_mutex);
    ftx_message_rc_t rc = ftx_message_decode(&w->message, &callsign_hash_if, text, &offsets);
    pthread_mutex_unlock(&callsign_mutex);
    if (rc != FTX_MESSAGE_RC_OK)
        text[0] = '\0';

    char call_to[16], call_from[16], extra[32], grid[16], modifier[8];
    int report = -100;
    fill_message_fields(text, call_to, sizeof(call_to), call_from, sizeof(call_from),
                        extra, sizeof(extra), grid, sizeof(grid),
                        modifier, sizeof(modifier), &report);

    (*env)->SetObjectField(env, msg, f_text, (*env)->NewStringUTF(env, text));
    (*env)->SetObjectField(env, msg, f_from, (*env)->NewStringUTF(env, call_from));
    (*env)->SetObjectField(env, msg, f_to, (*env)->NewStringUTF(env, call_to));
    (*env)->SetObjectField(env, msg, f_extra, (*env)->NewStringUTF(env, extra));
    (*env)->SetObjectField(env, msg, f_grid, (*env)->NewStringUTF(env, grid));
    (*env)->SetObjectField(env, msg, f_modifier, (*env)->NewStringUTF(env, modifier));
    (*env)->SetIntField(env, msg, f_report, report);

    jbyteArray payload = (*env)->NewByteArray(env, FTX_PAYLOAD_LENGTH_BYTES);
    if (payload != NULL)
    {
        (*env)->SetByteArrayRegion(env, payload, 0, FTX_PAYLOAD_LENGTH_BYTES,
                                   (const jbyte*)w->message.payload);
        (*env)->SetObjectField(env, msg, f_payload, payload);
        (*env)->DeleteLocalRef(env, payload);
    }
}

JNIEXPORT void JNICALL
Java_com_bg7yoz_ft8cn_ft8listener_Ft8LibJni_nativeSubtract(
    JNIEnv* env, jclass clazz, jlong handle, jbyteArray payload,
    jfloat freq_hz, jfloat time_sec)
{
    (void)clazz;
    parallel_decoder_t* dec = (parallel_decoder_t*)(intptr_t)handle;
    if (dec == NULL || payload == NULL) return;
    if ((*env)->GetArrayLength(env, payload) < FTX_PAYLOAD_LENGTH_BYTES) return;

    uint8_t p[FTX_PAYLOAD_LENGTH_BYTES];
    (*env)->GetByteArrayRegion(env, payload, 0, FTX_PAYLOAD_LENGTH_BYTES, (jbyte*)p);

    uint8_t tones[FT8_NN];
    ft8_encode(p, tones);

    ftx_waterfall_t* wf = &dec->mon.wf;
    int base_bin = (int)lroundf(freq_hz / 6.25f) - dec->mon.min_bin;
    int base_block = (int)lroundf(time_sec / dec->mon.symbol_period);

    for (int sym = 0; sym < FT8_NN; ++sym)
    {
        int block = base_block + sym;
        if (block < 0 || block >= wf->num_blocks) continue;
        int bin = base_bin + tones[sym];
        for (int dt = -1; dt <= 1; ++dt)
        {
            int b = bin + dt;
            if (b < 0 || b >= wf->num_bins) continue;
            for (int time_sub = 0; time_sub < wf->time_osr; ++time_sub)
            {
                for (int freq_sub = 0; freq_sub < wf->freq_osr; ++freq_sub)
                {
                    int idx = block * wf->block_stride +
                              time_sub * wf->freq_osr * wf->num_bins +
                              freq_sub * wf->num_bins + b;
                    wf->mag[idx] = 0;
                }
            }
        }
    }
}

JNIEXPORT void JNICALL
Java_com_bg7yoz_ft8cn_ft8listener_Ft8LibJni_nativeFreeWorker(
    JNIEnv* env, jclass clazz, jlong worker)
{
    (void)env; (void)clazz;
    parallel_worker_t* w = (parallel_worker_t*)(intptr_t)worker;
    if (w != NULL) free(w);
}

JNIEXPORT void JNICALL
Java_com_bg7yoz_ft8cn_ft8listener_Ft8LibJni_nativeFree(
    JNIEnv* env, jclass clazz, jlong handle)
{
    (void)env; (void)clazz;
    parallel_decoder_t* dec = (parallel_decoder_t*)(intptr_t)handle;
    if (dec == NULL) return;
    monitor_free(&dec->mon);
    free(dec);
}
