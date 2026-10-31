package com.bg7yoz.ft8cn.ui;

/**
 * Turbo 配色的 256 项查表。
 *
 * <p>使用 Google Turbo colormap 的多项式近似（Anton Mikhailov），
 * 在类加载时生成一次，绘制时只做一次数组访问。</p>
 */
public final class TurboPalette {
    /** 0（低强度）到 255（高强度）的 ARGB 颜色表。 */
    public static final int[] COLORS = new int[256];

    static {
        for (int i = 0; i < COLORS.length; i++) {
            double x = i / 255.0;
            double x2 = x * x;
            double x3 = x2 * x;
            double x4 = x3 * x;
            double x5 = x4 * x;

            double r = 0.13572138 + 4.61539260 * x - 42.66032258 * x2
                    + 132.13108234 * x3 - 152.94239396 * x4 + 59.28637943 * x5;
            double g = 0.09140261 + 2.19418839 * x + 4.84296658 * x2
                    - 14.18503333 * x3 + 4.27729857 * x4 + 2.82956604 * x5;
            double b = 0.10667330 + 12.64194608 * x - 60.58204836 * x2
                    + 110.36276771 * x3 - 89.90310912 * x4 + 27.34824973 * x5;

            COLORS[i] = 0xff000000
                    | (toByte(r) << 16)
                    | (toByte(g) << 8)
                    | toByte(b);
        }
    }

    private TurboPalette() {
    }

    private static int toByte(double v) {
        int n = (int) Math.round(v * 255.0);
        if (n < 0) return 0;
        if (n > 255) return 255;
        return n;
    }
}
