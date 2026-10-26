// Ft8Native.java —— FT8 C 核心的 Java/JNI 绑定
//
// 包名与方法签名必须与 native/jni/ft8_jni.cpp 中的
// Java_com_ft8_nativecore_Ft8Native_* 严格一致，不可随意修改。
//
// 接入说明见同目录 ft8_w2/移植手册.md。

package com.ft8.nativecore;

import java.nio.FloatBuffer;

/**
 * FT8 编解码原生库入口（对应 libft8.so）。
 *
 * <p>全部方法均为阻塞调用，解码请放在后台线程执行。</p>
 */
public final class Ft8Native {

    static {
        System.loadLibrary("ft8");
    }

    private Ft8Native() {
    }

    /** 返回 C 库版本号，例如 "0.1.0"。 */
    public static native String version();

    /** 返回默认解码配置（float[]，下标见移植手册）。 */
    public static native float[] decodeConfigDefault();

    /** 返回默认编码配置（float[]）。 */
    public static native float[] encodeConfigDefault();

    /** 计算编码后的采样点数（不含 lead/tail 之外的部分）。 */
    public static native int encodeLength(String message, float[] cfg);

    /** 把消息编码为 12 kHz 单声道浮点波形，返回写入的采样点数。 */
    public static native int encode(String message, float[] cfg, float[] out);

    /** 把消息编码为 79 个 8-FSK 音调。 */
    public static native byte[] encodeTones(String message);

    /**
     * 解码一个整时隙。
     *
     * @param samples 12 kHz 单声道浮点音频（约 -1.0 ~ 1.0）
     * @param cfg     解码配置 float[]，可为 null 使用默认值
     * @param myCall  本台呼号（AP 预留，可传 null）
     * @param hisCall 对方呼号（AP 预留，可传 null）
     * @param hisGrid 对方网格（AP 预留，可传 null）
     * @return 每条结果一个字符串，字段以 '|' 分隔
     */
    public static native String[] decodeSlot(float[] samples, float[] cfg,
                                             String myCall, String hisCall, String hisGrid);

    /**
     * 解码一个整时隙（DirectBuffer 零拷贝版本）。
     *
     * @param samples 必须是 allocateDirect 的 FloatBuffer
     * @param length  有效采样点数
     */
    public static native String[] decodeSlotDirect(FloatBuffer samples, int length, float[] cfg,
                                                   String myCall, String hisCall, String hisGrid);

    /** 创建流式解码会话，返回句柄；失败返回 0。 */
    public static native long sessionCreate(float[] cfg,
                                            String myCall, String hisCall, String hisGrid);

    /** 向流式会话送入任意长度音频，返回已累积采样点数。 */
    public static native int sessionFeed(long handle, float[] samples);

    /** 时隙结束，取出解码结果（不会自动复位）。 */
    public static native String[] sessionFinalize(long handle);

    /** 复位会话，准备下一个时隙。 */
    public static native void sessionReset(long handle);

    /** 释放会话。 */
    public static native void sessionDestroy(long handle);

    /** 清理全局哈希表（可选，长时间运行时可调用）。 */
    public static native void hashClear();
}
