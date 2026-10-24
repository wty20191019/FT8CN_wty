package com.bg7yoz.ft8cn.ft8listener;

import java.util.ArrayList;
import java.util.List;

/**
 * 原快速解码器的频谱分段工具。
 *
 * <p>原 native 解码器没有“只搜索某个频段”的参数，因此这里对音频做
 * 带通滤波，再交给多个独立解码器并行搜索。滤波不改变采样率和时间轴，
 * 所以解码结果里的音频频率仍然是绝对频率。</p>
 */
public final class FastBandSplitter {
    public static final float FREQ_MIN_HZ = 100f;
    public static final float FREQ_MAX_HZ = 3000f;
    public static final float OVERLAP_HZ = 100f;

    // 4 阶 Butterworth 的 Q 值；每个 Q 对应一个双二阶节。
    private static final double[] BUTTERWORTH_Q = {0.54119610, 1.30656296};

    private FastBandSplitter() {
    }

    public static final class Band {
        public final float lowHz;
        public final float highHz;
        public float[] audio;

        Band(float lowHz, float highHz) {
            this.lowHz = lowHz;
            this.highHz = highHz;
        }
    }

    /**
     * 规划频段。第 i 段核心宽度 W/N，左右各扩展 overlap/2，
     * 保证落在段边界的 FT8 信号至少被一段完整覆盖。
     */
    public static List<Band> plan(int bandCount) {
        int n = Math.max(1, bandCount);
        float width = FREQ_MAX_HZ - FREQ_MIN_HZ;
        float core = width / n;
        List<Band> bands = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            float low = FREQ_MIN_HZ + i * core - OVERLAP_HZ / 2f;
            float high = FREQ_MIN_HZ + (i + 1) * core + OVERLAP_HZ / 2f;
            bands.add(new Band(Math.max(FREQ_MIN_HZ, low),
                    Math.min(FREQ_MAX_HZ, high)));
        }
        return bands;
    }

    /**
     * 4 阶 Butterworth 带通，输入/输出都是 12 kHz float 音频，长度不变。
     * 相比 129 抽头 FIR，单样本只需少量浮点运算，适合每个时隙实时处理。
     */
    public static float[] bandpass(float[] input, int sampleRate,
                                   float lowHz, float highHz) {
        if (input == null || input.length == 0) return new float[0];

        float[] out = input.clone();
        for (double q : BUTTERWORTH_Q) {
            Biquad hp = Biquad.highPass(sampleRate, lowHz, q);
            for (int i = 0; i < out.length; i++) out[i] = hp.process(out[i]);
        }
        for (double q : BUTTERWORTH_Q) {
            Biquad lp = Biquad.lowPass(sampleRate, highHz, q);
            for (int i = 0; i < out.length; i++) out[i] = lp.process(out[i]);
        }
        return out;
    }

    private static final class Biquad {
        private final float b0, b1, b2, a1, a2;
        private float z1, z2;

        private Biquad(double b0, double b1, double b2, double a0,
                       double a1, double a2) {
            this.b0 = (float) (b0 / a0);
            this.b1 = (float) (b1 / a0);
            this.b2 = (float) (b2 / a0);
            this.a1 = (float) (a1 / a0);
            this.a2 = (float) (a2 / a0);
        }

        static Biquad lowPass(int sampleRate, double freqHz, double q) {
            double w0 = 2.0 * Math.PI * freqHz / sampleRate;
            double cos = Math.cos(w0);
            double alpha = Math.sin(w0) / (2.0 * q);
            double b0 = (1.0 - cos) / 2.0;
            double b1 = 1.0 - cos;
            double b2 = b0;
            double a0 = 1.0 + alpha;
            double a1 = -2.0 * cos;
            double a2 = 1.0 - alpha;
            return new Biquad(b0, b1, b2, a0, a1, a2);
        }

        static Biquad highPass(int sampleRate, double freqHz, double q) {
            double w0 = 2.0 * Math.PI * freqHz / sampleRate;
            double cos = Math.cos(w0);
            double alpha = Math.sin(w0) / (2.0 * q);
            double b0 = (1.0 + cos) / 2.0;
            double b1 = -(1.0 + cos);
            double b2 = b0;
            double a0 = 1.0 + alpha;
            double a1 = -2.0 * cos;
            double a2 = 1.0 - alpha;
            return new Biquad(b0, b1, b2, a0, a1, a2);
        }

        float process(float x) {
            float y = b0 * x + z1;
            z1 = b1 * x - a1 * y + z2;
            z2 = b2 * x - a2 * y;
            return y;
        }
    }
}
