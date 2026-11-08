// Ft8WDecoder.java —— ft8_w2 解码器（FT8 C 核心）的 FT8CN 适配层
//
// 只做一件事：把 12 kHz 整时隙音频交给 libft8.so，返回结构化解码行。
// 默认使用 precise 档：osr=2x2、cand=100、iters=20、depth=0、
// osd=3、snr_mode=2、bands=1、threads=1（不做信号消除）。

package com.bg7yoz.ft8cn.ft8w;

import com.ft8.nativecore.Ft8Native;

/**
 * ft8_w2 解码器入口。
 *
 * <p>线程安全：{@link #decodeOnce} 只依赖局部变量，可并发调用；
 * 但建议与 wsjtx 一致，由后台单线程串行使用。</p>
 */
public final class Ft8WDecoder {

    /** 一条解码结果。字段与 JNI 结果串一一对应。 */
    public static final class Row {
        public String text = "";
        public float snr;
        public float dt;
        public float freq;
        public int score;
        public int ldpcErrors;
        public int pass;
        public int apType;
        public long crc;
        public int i3;
        public int n3;
        public String callTo = "";
        public String callDe = "";
        public String extra = "";
        public String grid = "";
        /** true 表示库提供了 i3/n3/呼号/附加信息等结构化字段。 */
        public boolean structured;
    }

    private Ft8WDecoder() {
    }

    /** 返回 C 库版本号（如 "0.1.0"）。 */
    public static String version() {
        return Ft8Native.version();
    }

    /**
     * 解码一个整时隙（precise 档）。
     *
     * @param audio      12 kHz、单声道、约 -1.0 ~ 1.0 的浮点音频
     * @param sampleRate 采样率，FT8CN 固定 12000
     * @return 解码行；无结果时返回长度为 0 的数组
     */
    public static Row[] decodeOnce(float[] audio, int sampleRate) {
        if (audio == null || audio.length == 0) {
            return new Row[0];
        }
        String[] lines = Ft8Native.decodeSlot(
                audio, preciseConfig(sampleRate), null, null, null);
        return parse(lines);
    }

    /** 实例形式，便于和现有解码器保持同样的调用风格。 */
    public Row[] decode(float[] audio, int sampleRate) {
        return decodeOnce(audio, sampleRate);
    }

    /**
     * 生成 precise 档配置。
     *
     * <p>下标定义见 native/jni/ft8_jni.cpp 的 DC_ 枚举：
     * 0 fmin, 1 fmax, 2 sample_rate, 3 time_osr, 4 freq_osr,
     * 5 max_candidates, 6 min_sync_score, 7 ldpc_iterations,
     * 8 decode_depth, 9 ap_mode, 10 enable_subtract, 11 num_threads,
     * 12 return_duplicates, 13 osd_depth, 14 enable_llr_refine,
     * 15 snr_mode, 16 subtract_max_signals。</p>
     */
    public static float[] preciseConfig(int sampleRate) {
        float[] cfg = new float[17];
        cfg[0] = 200f;                 // fmin
        cfg[1] = 3000f;                // fmax
        cfg[2] = sampleRate > 0 ? sampleRate : 12000;
        cfg[3] = 2f;                   // time_osr
        cfg[4] = 2f;                   // freq_osr
        cfg[5] = 100f;                 // max_candidates
        cfg[6] = 10f;                  // min_sync_score
        cfg[7] = 20f;                  // ldpc_iterations
        cfg[8] = 0f;                   // decode_depth（不做多遍消除）
        cfg[9] = 0f;                   // ap_mode
        cfg[10] = 0f;                  // enable_subtract
        cfg[11] = 1f;                  // num_threads
        cfg[12] = 0f;                  // return_duplicates
        cfg[13] = 3f;                  // osd_depth
        cfg[14] = 0f;                  // enable_llr_refine
        cfg[15] = 2f;                  // snr_mode（抽取式快速 SNR）
        cfg[16] = 0f;                  // subtract_max_signals
        return cfg;
    }

    private static Row[] parse(String[] lines) {
        if (lines == null || lines.length == 0) {
            return new Row[0];
        }
        java.util.ArrayList<Row> out = new java.util.ArrayList<Row>(lines.length);
        for (String line : lines) {
            Row row = parseLine(line);
            if (row != null && row.text.length() > 0) {
                out.add(row);
            }
        }
        return out.toArray(new Row[0]);
    }

    /**
     * ft8_w 返回的 dt 相对音频缓冲起点，实测比 FT8CN 的时隙偏移恒定多约 0.66 s
     * （953 条匹配报文，σ=0.04）。这里统一归一到时隙口径。
     */
    private static final float DT_BIAS_SEC = 0.66f;

    /**
     * 结果串格式：
     * text|snr|dt|freq|score|ldpc_errors|pass|ap_type|crc|i3|n3|call_to|call_de|extra|grid
     */
    private static Row parseLine(String line) {
        if (line == null) {
            return null;
        }
        String[] p = line.split("\\|", -1);
        if (p.length < 9) {
            return null;
        }
        try {
            Row r = new Row();
            r.text = p[0];
            r.snr = Float.parseFloat(p[1]);
            r.dt = Float.parseFloat(p[2]) - DT_BIAS_SEC;
            r.freq = Float.parseFloat(p[3]);
            r.score = Integer.parseInt(p[4]);
            r.ldpcErrors = Integer.parseInt(p[5]);
            r.pass = Integer.parseInt(p[6]);
            r.apType = Integer.parseInt(p[7]);
            r.crc = Long.parseLong(p[8]);
            if (p.length >= 15) {
                r.i3 = Integer.parseInt(p[9]);
                r.n3 = Integer.parseInt(p[10]);
                r.callTo = p[11];
                r.callDe = p[12];
                r.extra = p[13];
                r.grid = p[14];
                r.structured = true;
            }
            return r;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
