package com.bg7yoz.ft8cn.ft8listener;

import android.util.Log;

import androidx.lifecycle.MutableLiveData;

import com.bg7yoz.ft8cn.FT8Common;
import com.bg7yoz.ft8cn.Ft8Message;
import com.bg7yoz.ft8cn.GeneralVariables;
import com.bg7yoz.ft8cn.ft8signal.FT8Package;
import com.bg7yoz.ft8cn.timer.UtcTimer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 并行解码编排（D 轨：原版 ft8_lib 共享瀑布图）。
 *
 * 旧快速/深度模式完全不经过本类；只有 GeneralVariables.decodeMode == PARALLEL
 * 时，FT8SignalListener 才会在入口处分发到这里。
 */
public final class ParallelDecodeEngine {
    private static final String TAG = "ParallelDecodeEngine";

    private static final int FAST_ITERATIONS = 25;
    private static final int DEEP_ITERATIONS = 200;
    private static final int MAX_CANDIDATES_PER_SLICE = 200;
    private static final float BIN_HZ = 6.25f;

    private final OnFt8Listen onFt8Listen;
    private final MutableLiveData<Long> decodeTimeSec;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public ParallelDecodeEngine(OnFt8Listen onFt8Listen, MutableLiveData<Long> decodeTimeSec) {
        this.onFt8Listen = onFt8Listen;
        this.decodeTimeSec = decodeTimeSec;
    }

    /**
     * @return true 表示本次已由并行核心处理（包括“上一周期未结束而跳过”）；
     * false 表示新核心失败，调用方可记录日志。
     */
    public boolean decode(long utc, float[] audio) {
        if (!running.compareAndSet(false, true)) {
            Log.w(TAG, "上一周期并行解码尚未结束，跳过本周期");
            return true;
        }

        long decoder = 0;
        long[] workers = null;
        ExecutorService pool = null;
        try {
            final long started = System.currentTimeMillis();
            final long deadline = started + clampTimeout(GeneralVariables.parallelTimeout);

            if (onFt8Listen != null) {
                onFt8Listen.beforeListen(utc);
            }

            int slices = clampSlices(GeneralVariables.parallelSlices);
            int threads = resolveThreads(GeneralVariables.parallelThreads, slices);
            float fLow = clampFreqLow(GeneralVariables.parallelFreqLow);
            float fHigh = clampFreqHigh(GeneralVariables.parallelFreqHigh);
            if (fHigh <= fLow) fHigh = 5950f;
            final float bandLow = fLow;
            final float bandHigh = fHigh;

            decoder = Ft8LibJni.nativeCreate(FT8Common.SAMPLE_RATE, audio.length,
                    fLow, fHigh, MAX_CANDIDATES_PER_SLICE, DEEP_ITERATIONS);
            if (decoder == 0) {
                Log.e(TAG, "nativeCreate 失败");
                return false;
            }

            Ft8LibJni.nativePress(decoder, audio);

            // 快速首轮：使用新核心的低迭代轮，不触碰旧解码器
            long fastWorker = Ft8LibJni.nativeCreateWorker(decoder);
            List<Ft8Message> fastMessages = new ArrayList<>();
            if (fastWorker != 0) {
                try {
                    int n = Ft8LibJni.nativeFindRange(fastWorker, 0, Integer.MAX_VALUE);
                    fastMessages = decodeAll(fastWorker, n, FAST_ITERATIONS, utc,
                            false, deadline);
                } finally {
                    Ft8LibJni.nativeFreeWorker(fastWorker);
                }
            }

            LinkedHashMap<String, Ft8Message> allUnique = new LinkedHashMap<>();
            if (!fastMessages.isEmpty()) {
                ArrayList<Ft8Message> newFast = mergeNew(allUnique, fastMessages);
                if (!newFast.isEmpty()) {
                    publishTime(started);
                    if (onFt8Listen != null) {
                        onFt8Listen.afterDecode(utc, averageOffset(newFast),
                                UtcTimer.sequential(utc), newFast, false);
                    }
                }
            }

            // 深度轮次：固定切片 + 线程池 + 串行抵消
            pool = Executors.newFixedThreadPool(threads);
            workers = new long[slices];
            for (int i = 0; i < slices; ++i) {
                workers[i] = Ft8LibJni.nativeCreateWorker(decoder);
                if (workers[i] == 0) {
                    throw new IllegalStateException("nativeCreateWorker 失败: " + i);
                }
            }

            boolean changed = true;
            int round = 0;
            while (changed && System.currentTimeMillis() < deadline) {
                List<Future<List<Ft8Message>>> futures = new ArrayList<>();
                for (int i = 0; i < slices; ++i) {
                    final int slice = i;
                    final long worker = workers[i];
                    futures.add(pool.submit(() -> decodeSlice(worker, slice, slices,
                            bandLow, bandHigh, DEEP_ITERATIONS, utc, true, deadline)));
                }

                List<Ft8Message> roundMessages = new ArrayList<>();
                for (Future<List<Ft8Message>> f : futures) {
                    try {
                        roundMessages.addAll(f.get());
                    } catch (Exception e) {
                        Log.e(TAG, "切片解码异常: " + e.getMessage());
                    }
                }

                ArrayList<Ft8Message> newMessages = mergeNew(allUnique, roundMessages);
                changed = !newMessages.isEmpty();
                if (changed) {
                    // 串行抵消，避免多线程同时写共享瀑布图
                    for (Ft8Message m : newMessages) {
                        if (m.payload != null && m.payload.length >= 10) {
                            Ft8LibJni.nativeSubtract(decoder, m.payload, m.freq_hz, m.time_sec);
                        }
                    }
                    publishTime(started);
                    if (onFt8Listen != null) {
                        onFt8Listen.afterDecode(utc, averageOffset(newMessages),
                                UtcTimer.sequential(utc), newMessages, true);
                    }
                }
                ++round;
                Log.d(TAG, "第 " + round + " 轮，新增 " + newMessages.size()
                        + "，累计 " + allUnique.size()
                        + "，耗时 " + (System.currentTimeMillis() - started) + "ms");
            }

            publishTime(started);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "并行解码失败: " + Log.getStackTraceString(t));
            return false;
        } finally {
            if (workers != null) {
                for (long w : workers) {
                    if (w != 0) Ft8LibJni.nativeFreeWorker(w);
                }
            }
            if (pool != null) {
                pool.shutdownNow();
                try {
                    pool.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            if (decoder != 0) {
                Ft8LibJni.nativeFree(decoder);
            }
            running.set(false);
        }
    }

    private List<Ft8Message> decodeSlice(long worker, int slice, int slices,
                                         float fLow, float fHigh, int iterations,
                                         long utc, boolean weak, long deadline) {
        List<Ft8Message> result = new ArrayList<>();
        float width = (fHigh - fLow) / slices;
        int binLo = Math.round((fLow + slice * width) / BIN_HZ);
        int binHi = Math.round((fLow + (slice + 1) * width) / BIN_HZ);
        int n = Ft8LibJni.nativeFindRange(worker, binLo, binHi);
        for (int i = 0; i < n; ++i) {
            if (System.currentTimeMillis() > deadline) break;
            if (!Ft8LibJni.nativeDecode(worker, i, iterations)) continue;
            Ft8Message message = new Ft8Message(FT8Common.FT8_MODE);
            Ft8LibJni.nativeFillMessage(worker, message);
            if (!message.isValid) continue;
            message.utcTime = utc;
            message.band = GeneralVariables.band;
            message.isWeakSignal = weak;
            fillHashes(message);
            result.add(message);
        }
        return result;
    }

    private List<Ft8Message> decodeAll(long worker, int n, int iterations,
                                       long utc, boolean weak, long deadline) {
        List<Ft8Message> result = new ArrayList<>();
        for (int i = 0; i < n; ++i) {
            if (System.currentTimeMillis() > deadline) break;
            if (!Ft8LibJni.nativeDecode(worker, i, iterations)) continue;
            Ft8Message message = new Ft8Message(FT8Common.FT8_MODE);
            Ft8LibJni.nativeFillMessage(worker, message);
            if (!message.isValid) continue;
            message.utcTime = utc;
            message.band = GeneralVariables.band;
            message.isWeakSignal = weak;
            fillHashes(message);
            result.add(message);
        }
        return result;
    }

    /**
     * 按消息文本去重（与旧模式 checkMessageSame 的语义一致）。
     */
    private ArrayList<Ft8Message> mergeNew(Map<String, Ft8Message> known, List<Ft8Message> incoming) {
        ArrayList<Ft8Message> added = new ArrayList<>();
        for (Ft8Message m : incoming) {
            String key = m.getMessageText();
            if (key == null || key.length() == 0) {
                key = String.format("%.0f", m.freq_hz);
            }
            Ft8Message old = known.get(key);
            if (old == null) {
                known.put(key, m);
                added.add(m);
            } else if (m.snr > old.snr) {
                old.snr = m.snr;
            }
        }
        return added;
    }

    private void fillHashes(Ft8Message m) {
        try {
            if (isPlainCallsign(m.callsignFrom)) {
                m.callFromHash10 = FT8Package.getHash10(m.callsignFrom);
                m.callFromHash12 = FT8Package.getHash12(m.callsignFrom);
                m.callFromHash22 = FT8Package.getHash22(m.callsignFrom);
            }
            if (isPlainCallsign(m.callsignTo)) {
                m.callToHash10 = FT8Package.getHash10(m.callsignTo);
                m.callToHash12 = FT8Package.getHash12(m.callsignTo);
                m.callToHash22 = FT8Package.getHash22(m.callsignTo);
            }
        } catch (Throwable t) {
            Log.w(TAG, "计算呼号哈希失败: " + t.getMessage());
        }
    }

    private boolean isPlainCallsign(String call) {
        return call != null && call.length() > 2
                && !call.startsWith("<")
                && !"CQ".equals(call)
                && !"QRZ".equals(call)
                && !"DE".equals(call);
    }

    private void publishTime(long started) {
        if (decodeTimeSec != null) {
            decodeTimeSec.postValue(System.currentTimeMillis() - started);
        }
    }

    private float averageOffset(List<Ft8Message> messages) {
        if (messages.isEmpty()) return 0f;
        float sum = 0;
        for (Ft8Message m : messages) sum += m.time_sec;
        return sum / messages.size();
    }

    private int resolveThreads(int configured, int slices) {
        int threads = configured;
        if (threads <= 0) {
            threads = Math.min(4, Math.max(2,
                    Runtime.getRuntime().availableProcessors() - 1));
        }
        threads = Math.max(1, Math.min(threads, slices));
        return threads;
    }

    private int clampSlices(int value) {
        return Math.max(2, Math.min(8, value));
    }

    private long clampTimeout(int value) {
        return Math.max(3000, Math.min(12000, value));
    }

    private float clampFreqLow(float value) {
        return Math.max(0f, Math.min(1000f, value));
    }

    private float clampFreqHigh(float value) {
        return Math.max(1000f, Math.min(5950f, value));
    }
}
