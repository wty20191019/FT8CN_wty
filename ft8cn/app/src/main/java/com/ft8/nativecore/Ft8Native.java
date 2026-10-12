package com.ft8.nativecore;

/**
 * ft8_w 新解码库（libft8.so）的 Java 侧 JNI 封装。
 *
 * <p>与 native/jni/ft8_jni.cpp 的 JNI 符号
 * {@code Java_com_ft8_nativecore_Ft8Native_*} 严格对应：
 * 包名/类名/方法名不可随意改动，否则运行期会报 {@code UnsatisfiedLinkError}。
 *
 * <p>本库与 FT8CN 原有的 libft8cn.so <b>并存</b>：本库只负责解码
 * （瀑布 → 候选 → LDPC(BP) → OSD 兜底 → 多遍信号消除 → 文本 + 频谱噪声底 SNR）；
 * 原有库继续负责发射、呼号哈希、频谱与重采样。二者符号前缀不同，互不冲突。
 */
public final class Ft8Native {

    static {
        System.loadLibrary("ft8");
    }

    private Ft8Native() {
    }

    // ------------------------------------------------------------------
    // 基础
    // ------------------------------------------------------------------

    /** 返回库版本字符串，如 "0.1.0"。 */
    public static native String version();

    /** 清空解码库内部的呼号哈希表。 */
    public static native void hashClear();

    // ------------------------------------------------------------------
    // 解码
    // ------------------------------------------------------------------

    /** 返回默认的解码配置 float[]（下标见下方 DC_* 常量）。 */
    public static native float[] decodeConfigDefault();

    /**
     * 对整时隙音频执行一次性解码（阻塞）。
     *
     * @param samples 12kHz 单声道浮点音频，取值约 [-1, 1]
     * @param cfg     解码配置，长度 {@link #DC_COUNT}
     * @param myCall  本台呼号（AP 用，可空）
     * @param hisCall 对方呼号（AP 用，可空）
     * @param hisGrid 对方网格（AP 用，可空）
     * @return 每条为 "{@code text|snr|dt|freq|score|ldpc_errors|pass|ap_type|crc|i3|n3|call_to|call_de|extra|grid}"
     */
    public static native String[] decodeSlot(float[] samples, float[] cfg,
                                             String myCall, String hisCall, String hisGrid);

    // ------------------------------------------------------------------
    // 流式会话（预留；当前 FT8CN 使用整时隙 decodeSlot）
    // ------------------------------------------------------------------

    public static native long sessionCreate(float[] cfg, String myCall, String hisCall, String hisGrid);

    public static native int sessionFeed(long handle, float[] samples);

    public static native String[] sessionFinalize(long handle);

    public static native void sessionReset(long handle);

    public static native void sessionDestroy(long handle);

    // ------------------------------------------------------------------
    // 解码配置 float[] 下标（与 native/jni/ft8_jni.cpp 的 DC_* 一致）
    // ------------------------------------------------------------------

    public static final int DC_FMIN = 0;
    public static final int DC_FMAX = 1;
    public static final int DC_SAMPLE_RATE = 2;
    public static final int DC_TIME_OSR = 3;
    public static final int DC_FREQ_OSR = 4;
    public static final int DC_MAX_CANDIDATES = 5;
    public static final int DC_MIN_SYNC_SCORE = 6;
    public static final int DC_LDPC_ITERATIONS = 7;
    public static final int DC_DECODE_DEPTH = 8;
    public static final int DC_AP_MODE = 9;
    public static final int DC_ENABLE_SUBTRACT = 10;
    public static final int DC_NUM_THREADS = 11;
    public static final int DC_RETURN_DUPLICATES = 12;
    public static final int DC_OSD_DEPTH = 13;
    public static final int DC_ENABLE_LLR_REFINE = 14;
    public static final int DC_COUNT = 15;

    /** 单条解码结果（对应 C 层的 ft8_message_result_t）。 */
    public static final class Message {
        public String text = "";
        public float snr;
        public float dt;
        public float freq;
        public int score;
        public int ldpcErrors;
        public int pass;
        public int apType;
        public int crc;
        public int i3;
        public int n3;
        public String callTo = "";
        public String callDe = "";
        public String extra = "";
        public String grid = "";
    }

    /**
     * 解析结果串：
     * {@code text|snr|dt|freq|score|ldpc_errors|pass|ap_type|crc|i3|n3|call_to|call_de|extra|grid}
     */
    public static Message parse(String record) {
        Message m = new Message();
        if (record == null) {
            return m;
        }
        // -1 保留尾部空字段，避免字段错位
        String[] p = record.split("\\|", -1);
        m.text = get(p, 0, "");
        m.snr = parseFloat(get(p, 1, "0"));
        m.dt = parseFloat(get(p, 2, "0"));
        m.freq = parseFloat(get(p, 3, "0"));
        m.score = parseInt(get(p, 4, "0"));
        m.ldpcErrors = parseInt(get(p, 5, "0"));
        m.pass = parseInt(get(p, 6, "0"));
        m.apType = parseInt(get(p, 7, "0"));
        m.crc = parseInt(get(p, 8, "0"));
        m.i3 = parseInt(get(p, 9, "0"));
        m.n3 = parseInt(get(p, 10, "0"));
        m.callTo = get(p, 11, "");
        m.callDe = get(p, 12, "");
        m.extra = get(p, 13, "");
        m.grid = get(p, 14, "");
        return m;
    }

    private static String get(String[] a, int i, String def) {
        return (a != null && i < a.length && a[i] != null) ? a[i] : def;
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static float parseFloat(String s) {
        try {
            return Float.parseFloat(s.trim());
        } catch (Exception e) {
            return 0f;
        }
    }
}
