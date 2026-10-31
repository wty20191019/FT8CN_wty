package com.bg7yoz.ft8cn.ui;

/**
 * 瀑布图配色渐变表（蓝单色相）。
 *
 * <p>用 3 个色标线性插值成 256 项：黑 → 深蓝 → 亮蓝，
 * 只改变亮度，不引入青、绿、红等其他色相。</p>
 */
public final class WaterfallPalette {
    private static final int[] STOPS = {
            0x000000, // 黑
            0x000060, // 深蓝
            0x0000ff, // 亮蓝
    };

    /** 0（低强度）到 255（高强度）的 ARGB 颜色表。 */
    public static final int[] COLORS = new int[256];

    static {
        int segments = STOPS.length - 1;
        for (int i = 0; i < COLORS.length; i++) {
            float pos = i / (float) (COLORS.length - 1) * segments;
            int index = (int) pos;
            if (index >= segments) index = segments - 1;
            float t = pos - index;
            COLORS[i] = blend(STOPS[index], STOPS[index + 1], t);
        }
    }

    private WaterfallPalette() {
    }

    private static int blend(int from, int to, float t) {
        int r = (int) (((from >> 16) & 0xff) + (((to >> 16) & 0xff) - ((from >> 16) & 0xff)) * t);
        int g = (int) (((from >> 8) & 0xff) + (((to >> 8) & 0xff) - ((from >> 8) & 0xff)) * t);
        int b = (int) ((from & 0xff) + ((to & 0xff) - (from & 0xff)) * t);
        return 0xff000000 | (r << 16) | (g << 8) | b;
    }
}
