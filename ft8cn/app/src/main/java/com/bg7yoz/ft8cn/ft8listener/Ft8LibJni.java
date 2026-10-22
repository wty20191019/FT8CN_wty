package com.bg7yoz.ft8cn.ft8listener;

import com.bg7yoz.ft8cn.Ft8Message;

/**
 * 原版 ft8_lib 并行解码核心的 JNI 接口。
 *
 * 该库只服务“并行解码”模式，旧快速/深度模式仍使用 libft8cn.so。
 */
public final class Ft8LibJni {
    static {
        System.loadLibrary("ft8parallel");
    }

    private Ft8LibJni() {
    }

    /**
     * 创建共享瀑布图解码器。
     *
     * @param sampleRate    采样率，当前固定 12000
     * @param numSamples    样本数，当前 180000
     * @param fMin          瀑布图最低频率，默认 0
     * @param fMax          瀑布图最高频率，默认 5950
     * @param maxCandidates 每片最大候选数
     * @param maxIterations 默认 LDPC 迭代次数
     * @return native 句柄，0 表示失败
     */
    public static native long nativeCreate(int sampleRate, int numSamples,
                                           float fMin, float fMax,
                                           int maxCandidates, int maxIterations);

    /**
     * 把一整段音频压入共享瀑布图。
     */
    public static native void nativePress(long decoder, float[] audio);

    /**
     * 为一个切片创建 worker（独立候选表和结果缓冲）。
     */
    public static native long nativeCreateWorker(long decoder);

    /**
     * 在共享瀑布图上搜索指定 bin 范围的候选。
     *
     * @param worker 切片 worker
     * @param binLo  起始 bin（包含）
     * @param binHi  结束 bin（不包含）
     * @return 候选数量
     */
    public static native int nativeFindRange(long worker, int binLo, int binHi);

    /**
     * 解码 worker 候选表中的第 idx 个候选。
     */
    public static native boolean nativeDecode(long worker, int idx, int maxIterations);

    /**
     * 把最近一次成功解码的结果填到 Ft8Message。
     */
    public static native void nativeFillMessage(long worker, Ft8Message message);

    /**
     * 在共享瀑布图上抵消一个已解信号（深解使用）。
     *
     * @param payload     77 bit payload（10 字节）
     * @param timeOffset  候选时间块
     * @param timeSub     时间子采样
     * @param freqOffset  候选频率 bin
     * @param freqSub     频率子采样
     */
    public static native void nativeSubtract(long decoder, byte[] payload,
                                             int timeOffset, int timeSub,
                                             int freqOffset, int freqSub);

    public static native void nativeFreeWorker(long worker);

    public static native void nativeFree(long decoder);
}
