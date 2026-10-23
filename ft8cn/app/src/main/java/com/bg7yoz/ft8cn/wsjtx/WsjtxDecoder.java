package com.bg7yoz.ft8cn.wsjtx;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * mfsk-core（WSJT-X 算法的 Rust 实现）的 Java 适配层。
 *
 * <p>只暴露 FT8/FT4 的“整槽解码”接口，不模拟 FT8CN 原来的
 * InitDecoder/FindSync/Analysis 候选式 API。原始音频直接以
 * float[]（约 -1.0 ~ 1.0，12 kHz）传入，底层会完成同步、LLR、
 * LDPC/BP、OSD 和减法。</p>
 *
 * <p>线程安全：一个实例只应在一个线程里调用 decode()。FT8CN 目前
 * 在后台线程解码，符合这个约束。</p>
 */
public final class WsjtxDecoder implements AutoCloseable {

    static {
        System.loadLibrary("wsjtx_Android");
    }

    public static final int MODE_FT8 = 0;
    public static final int MODE_FT4 = 1;

    private long handle;

    public WsjtxDecoder(int mode) {
        handle = nativeOpen(mode);
        if (handle == 0L) {
            throw new IllegalStateException("open mfsk decoder failed");
        }
    }

    /**
     * 解码一个时隙。
     *
     * @param audio      12 kHz、float、范围约 -1.0 ~ 1.0 的音频
     * @param sampleRate 采样率，FT8CN 固定 12000
     * @param deep       true = BP_ALL_OSD + DEEP 门限（更慢，弱信号更多）
     * @return 解码行；没有结果时返回长度为 0 的数组
     */
    public Row[] decode(float[] audio, int sampleRate, boolean deep) {
        return decode(audio, sampleRate, deep, 0.0f, 0.0f);
    }

    /**
     * 解码一个时隙，只在指定频段内搜索。
     *
     * <p>用于频谱分段并行：每段传入自己的 [freqMin, freqMax]，段间留重叠。
     * 传 0, 0 表示使用模式默认的完整搜索范围。</p>
     *
     * @param audio      12 kHz、float、范围约 -1.0 ~ 1.0 的音频
     * @param sampleRate 采样率，FT8CN 固定 12000
     * @param deep       true = BP_ALL_OSD + DEEP 门限
     * @param freqMin    搜索下限（Hz），&lt;= 0 表示不限制
     * @param freqMax    搜索上限（Hz），必须大于 freqMin 才生效
     * @return 解码行；没有结果时返回长度为 0 的数组
     */
    public Row[] decode(float[] audio, int sampleRate, boolean deep,
                        float freqMin, float freqMax) {
        if (handle == 0L) {
            throw new IllegalStateException("decoder is closed");
        }
        String[] lines = nativeDecodeBand(handle, audio, sampleRate, deep,
                freqMin, freqMax);
        return toRows(lines);
    }

    /**
     * 解码一个时隙，只搜索指定频段，并使用指定的速度档位。
     *
     * @param profile 0=默认，1=关闭OSD，2=关闭OSD+单遍，3=单遍，4=严格门限+单遍
     */
    public Row[] decode(float[] audio, int sampleRate, boolean deep,
                        float freqMin, float freqMax, int profile) {
        if (handle == 0L) {
            throw new IllegalStateException("decoder is closed");
        }
        String[] lines = nativeDecodeBandEx(handle, audio, sampleRate, deep,
                freqMin, freqMax, profile);
        return toRows(lines);
    }

    public Row[] decode(float[] audio, int sampleRate) {
        return decode(audio, sampleRate, false);
    }

    private static Row[] toRows(String[] lines) {
        if (lines == null || lines.length == 0) {
            return new Row[0];
        }

        List<Row> rows = new ArrayList<>(lines.length);
        for (String line : lines) {
            Row row = Row.parse(line);
            if (row != null) {
                rows.add(row);
            }
        }
        return rows.toArray(new Row[0]);
    }

    /** 一次性便捷方法：内部 open/decode/close，适合测试或低频调用。 */
    public static Row[] decodeOnce(float[] audio, int sampleRate, boolean deep) {
        return decodeOnce(audio, sampleRate, deep, 0.0f, 0.0f);
    }

    /** 一次性便捷方法，只解码指定频段。 */
    public static Row[] decodeOnce(float[] audio, int sampleRate, boolean deep,
                                   float freqMin, float freqMax) {
        return decodeOnce(audio, sampleRate, deep, 0, freqMin, freqMax);
    }

    /** 一次性便捷方法，只解码指定频段并指定速度档位。 */
    public static Row[] decodeOnce(float[] audio, int sampleRate, boolean deep,
                                   int profile, float freqMin, float freqMax) {
        try (WsjtxDecoder decoder = new WsjtxDecoder(MODE_FT8)) {
            return decoder.decode(audio, sampleRate, deep, freqMin, freqMax, profile);
        }
    }

    public boolean isOpen() {
        return handle != 0L;
    }

    @Override
    public void close() {
        if (handle != 0L) {
            nativeClose(handle);
            handle = 0L;
        }
    }

    /**
     * mfsk-ffi 的版本号（uint32_t）。例如 0x000C0000 表示 0.12.0。
     */
    public static int version() {
        return nativeVersion();
    }

    public static String modeName(int mode) {
        return nativeModeName(mode);
    }

    /**
     * 一条解码结果。字符串字段是 mfsk-core 已经解好的可读报文，
     * 例如 "CQ JA1ABC PM95" 或 "JA1ABC DL1ABC -12"。
     */
    public static final class Row {
        public final String text;
        public final float snr;
        public final float dt;
        public final float freq;
        /** MFSK_MODE_FT8=0 / MFSK_MODE_FT4=1 */
        public final int mode;
        /** bit0 = 用到呼号哈希表；其他位保留。 */
        public final int flags;

        private Row(String text, float snr, float dt, float freq, int mode, int flags) {
            this.text = text;
            this.snr = snr;
            this.dt = dt;
            this.freq = freq;
            this.mode = mode;
            this.flags = flags;
        }

        static Row parse(String line) {
            if (line == null) {
                return null;
            }
            String[] f = line.split("\t", -1);
            if (f.length < 6) {
                return null;
            }
            try {
                return new Row(
                        f[0],
                        Float.parseFloat(f[1]),
                        Float.parseFloat(f[2]),
                        Float.parseFloat(f[3]),
                        Integer.parseInt(f[4]),
                        Integer.parseInt(f[5]));
            } catch (NumberFormatException e) {
                return null;
            }
        }

        @Override
        public String toString() {
            return String.format(Locale.US,
                    "%s  snr=%.1f dt=%.2f freq=%.0f mode=%d flags=%d",
                    text, snr, dt, freq, mode, flags);
        }
    }

    private static native long nativeOpen(int mode);

    private static native String[] nativeDecode(long handle, float[] audio,
                                                int sampleRate, boolean deep);

    private static native String[] nativeDecodeBand(long handle, float[] audio,
                                                    int sampleRate, boolean deep,
                                                    float freqMin, float freqMax);

    private static native String[] nativeDecodeBandEx(long handle, float[] audio,
                                                      int sampleRate, boolean deep,
                                                      float freqMin, float freqMax,
                                                      int profile);

    private static native void nativeClose(long handle);

    private static native int nativeVersion();

    private static native String nativeModeName(int mode);
}
