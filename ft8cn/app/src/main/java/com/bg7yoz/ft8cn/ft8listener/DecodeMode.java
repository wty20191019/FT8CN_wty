package com.bg7yoz.ft8cn.ft8listener;

/**
 * 解码模式。旧快速/深度模式保持原值，新增并行模式。
 */
public final class DecodeMode {
    public static final int FAST = 0;
    public static final int DEEP = 1;
    public static final int PARALLEL = 2;

    private DecodeMode() {
    }
}
