package com.bg7yoz.ft8cn.wsjtx;

import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * wsjtx（mfsk-core）的频谱分段并行解码器。
 *
 * <p>把 100~3000 Hz 按 CPU 核心数切成若干段，段间保留 100 Hz 重叠，
 * 每段用独立线程、独立 session 只搜索本频段。重叠 100 Hz（左右各 50 Hz）
 * 保证落在段边界的 FT8 信号至少被一段完整覆盖。</p>
 *
 * <p>每段输出的频率就是绝对音频频率（没有移频），因此不需要做频率修正。
 * 唯一代价是每段都会各自做一次整时隙 FFT；段数超过核心数不会更快，
 * 所以段数上限取 CPU 核心数，并限制为最多 8 段。</p>
 */
public final class WsjtxBandDecoder implements AutoCloseable {
    private static final String TAG = "WsjtxBandDecoder";

    /** default FT8 search range of mfsk-core. */
    public static final float FREQ_MIN_HZ = 100f;
    public static final float FREQ_MAX_HZ = 3000f;
    /** 相邻频段的重叠宽度，左右各一半。 */
    public static final float OVERLAP_HZ = 100f;

    /** 段数上限，避免在超多核设备上把整时隙 FFT 重复太多次。 */
    private static final int MAX_BANDS = 8;
    /** 每段核心宽度下限（不含重叠），保证一段至少能完整容纳多个 FT8 信号。 */
    private static final float MIN_CORE_WIDTH_HZ = 200f;

    /** 仅基准测试用：大于 0 时强制使用该段数。 */
    private static volatile int testBandCount = 0;

    /** 仅基准测试用：强制段数，0 表示恢复自动。 */
    public static void setBandCountForTest(int bandCount) {
        testBandCount = Math.max(0, bandCount);
    }

    private final ExecutorService pool;
    private final float[] bandLow;
    private final float[] bandHigh;
    private final int bandCount;

    public WsjtxBandDecoder() {
        this(FREQ_MIN_HZ, FREQ_MAX_HZ, OVERLAP_HZ);
    }

    public WsjtxBandDecoder(float freqMinHz, float freqMaxHz, float overlapHz) {
        float freqMin = Math.max(0f, freqMinHz);
        float freqMax = Math.max(freqMin + MIN_CORE_WIDTH_HZ, freqMaxHz);
        float overlap = Math.max(0f, overlapHz);

        int cores = Runtime.getRuntime().availableProcessors();
        int n = testBandCount > 0 ? testBandCount
                : Math.max(1, Math.min(cores, MAX_BANDS));
        n = Math.max(1, Math.min(n, MAX_BANDS));
        n = Math.min(n, Math.max(1,
                (int) ((freqMax - freqMin) / MIN_CORE_WIDTH_HZ)));
        bandCount = n;

        bandLow = new float[n];
        bandHigh = new float[n];
        float coreWidth = (freqMax - freqMin) / n;
        for (int i = 0; i < n; i++) {
            float lo = freqMin + i * coreWidth - overlap / 2f;
            float hi = freqMin + (i + 1) * coreWidth + overlap / 2f;
            bandLow[i] = Math.max(freqMin, lo);
            bandHigh[i] = Math.min(freqMax, hi);
        }

        final AtomicInteger threadNo = new AtomicInteger(1);
        pool = Executors.newFixedThreadPool(n, new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "wsjtx-band-" + threadNo.getAndIncrement());
                t.setDaemon(true);
                return t;
            }
        });

        Log.i(TAG, String.format(Locale.US,
                "频谱分段解码：%d 段（CPU %d 核），范围 %.0f~%.0f Hz，重叠 %.0f Hz",
                n, cores, freqMin, freqMax, overlap));
    }

    public int getBandCount() {
        return bandCount;
    }

    /**
     * 并行解码一个时隙。
     *
     * @return 合并、去重后的解码行；至少一个频段成功才返回，全部失败时抛异常
     */
    public WsjtxDecoder.Row[] decode(final float[] audio, final int sampleRate,
                                     final boolean deep) throws Exception {
        if (bandCount <= 1) {
            return WsjtxDecoder.decodeOnce(audio, sampleRate, deep,
                    bandLow[0], bandHigh[0]);
        }

        List<Future<WsjtxDecoder.Row[]>> futures =
                new ArrayList<>(bandCount);
        for (int i = 0; i < bandCount; i++) {
            final float lo = bandLow[i];
            final float hi = bandHigh[i];
            futures.add(pool.submit(new Callable<WsjtxDecoder.Row[]>() {
                @Override
                public WsjtxDecoder.Row[] call() {
                    return WsjtxDecoder.decodeOnce(audio, sampleRate, deep, lo, hi);
                }
            }));
        }

        List<WsjtxDecoder.Row> all = new ArrayList<>();
        int success = 0;
        Throwable firstError = null;
        for (int i = 0; i < futures.size(); i++) {
            try {
                WsjtxDecoder.Row[] rows = futures.get(i).get();
                if (rows != null) {
                    all.addAll(Arrays.asList(rows));
                    success++;
                }
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                Log.e(TAG, String.format(Locale.US,
                        "频段 %.0f~%.0f Hz 解码失败：%s",
                        bandLow[i], bandHigh[i], cause.getMessage()), cause);
                if (firstError == null) firstError = cause;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                for (Future<WsjtxDecoder.Row[]> f : futures) f.cancel(true);
                throw e;
            }
        }

        if (success == 0) {
            if (firstError instanceof Exception) throw (Exception) firstError;
            throw new IllegalStateException("所有频段解码失败", firstError);
        }
        return dedupe(all);
    }

    /**
     * 重叠区同一报文会被相邻两段各解出一次，这里按文本去重并保留 SNR 较高的一条。
     * 与 FT8CN 原来的 checkMessageSame 语义一致。
     */
    private static WsjtxDecoder.Row[] dedupe(List<WsjtxDecoder.Row> rows) {
        LinkedHashMap<String, WsjtxDecoder.Row> map = new LinkedHashMap<>();
        for (WsjtxDecoder.Row row : rows) {
            String key = row.text == null ? ""
                    : row.text.trim().toUpperCase(Locale.US);
            WsjtxDecoder.Row old = map.get(key);
            if (old == null || row.snr > old.snr) {
                map.put(key, row);
            }
        }

        WsjtxDecoder.Row[] out = map.values().toArray(new WsjtxDecoder.Row[0]);
        Arrays.sort(out, new Comparator<WsjtxDecoder.Row>() {
            @Override
            public int compare(WsjtxDecoder.Row a, WsjtxDecoder.Row b) {
                return Float.compare(a.freq, b.freq);
            }
        });
        return out;
    }

    @Override
    public void close() {
        pool.shutdownNow();
    }
}
