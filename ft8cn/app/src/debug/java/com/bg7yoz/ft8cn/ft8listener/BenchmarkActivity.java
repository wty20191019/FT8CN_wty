package com.bg7yoz.ft8cn.ft8listener;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

import com.bg7yoz.ft8cn.FT8Common;
import com.bg7yoz.ft8cn.Ft8Message;
import com.bg7yoz.ft8cn.wsjtx.WsjtxBandDecoder;
import com.bg7yoz.ft8cn.wsjtx.WsjtxDecoder;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * 仅 debug 包使用的解码召回率基准 Activity（不进入 release）。
 *
 * <p>对指定目录下的每一组 wav/txt，依次用四个解码挡位解码，按 ft8_w 的
 * 基准口径（取报文前 3 个 token、统一大写、&lt;...&gt; 归一化）统计召回率。
 * 使用方式：</p>
 *
 * <pre>
 * adb shell am start -n com.bg7yoz.ft8cn/.ft8listener.BenchmarkActivity \
 *   --es dir /sdcard/Android/data/com.bg7yoz.ft8cn/files/ft8test \
 *   --es modes fast,deep,wsjtx,wsjtx_deep
 * </pre>
 *
 * 结果同时写 logcat（TAG=FT8Bench）和外部文件目录下的 bench_*.txt。
 */
public class BenchmarkActivity extends Activity {
    private static final String TAG = "FT8Bench";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private static final String MODE_FAST = "fast";
    private static final String MODE_DEEP = "deep";
    private static final String MODE_WSJTX = "wsjtx";
    private static final String MODE_WSJTX_DEEP = "wsjtx_deep";
    private static final String MODE_WSJTX_PARALLEL = "wsjtx_parallel";
    private static final String MODE_WSJTX_DEEP_PARALLEL = "wsjtx_deep_parallel";
    private static final String MODE_BAND = "band";

    //固定一个15秒对齐的UTC，保证每次基准输入一致
    private static final long BENCH_UTC = 1750000000000L - (1750000000000L % 15000L);

    private TextView statusView;
    private boolean dump;
    private boolean dumpRaw;
    private boolean checkText;
    private String fileFilter;
    private float bandLo;
    private float bandHi;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        statusView = new TextView(this);
        statusView.setTextSize(10);
        statusView.setText("FT8 benchmark starting...");
        setContentView(statusView);

        String defaultDir = new File(getExternalFilesDir(null), "ft8test").getAbsolutePath();
        String dir = getIntent().getStringExtra("dir");
        if (dir == null || dir.isEmpty()) dir = defaultDir;

        String modesArg = getIntent().getStringExtra("modes");
        final List<String> modes = parseModes(modesArg);

        dump = getIntent().getBooleanExtra("dump", false);
        dumpRaw = getIntent().getBooleanExtra("raw", false);
        checkText = getIntent().getBooleanExtra("checktext", false);
        fileFilter = getIntent().getStringExtra("file");
        bandLo = getIntent().getFloatExtra("fmin", 0f);
        bandHi = getIntent().getFloatExtra("fmax", 0f);

        String benchDir = dir;
        final int bands = getIntent().getIntExtra("bands", 0);
        WsjtxBandDecoder.setBandCountForTest(bands);
        WsjtxBandDecoder.setProfileForTest(getIntent().getIntExtra("profile", -1));
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    runBench(benchDir, modes);
                } catch (Throwable e) {
                    Log.e(TAG, "基准执行失败", e);
                    logLine("BENCH_FAIL " + e);
                }
            }
        }).start();
    }

    private List<String> parseModes(String modesArg) {
        List<String> modes = new ArrayList<>();
        if (modesArg == null || modesArg.isEmpty()) {
            modes.add(MODE_FAST);
            modes.add(MODE_DEEP);
            modes.add(MODE_WSJTX);
            modes.add(MODE_WSJTX_DEEP);
            modes.add(MODE_WSJTX_PARALLEL);
            modes.add(MODE_WSJTX_DEEP_PARALLEL);
            return modes;
        }
        for (String m : modesArg.split(",")) {
            String s = m.trim().toLowerCase(Locale.US);
            if (s.equals(MODE_FAST) || s.equals(MODE_DEEP)
                    || s.equals(MODE_WSJTX) || s.equals(MODE_WSJTX_DEEP)
                    || s.equals(MODE_WSJTX_PARALLEL)
                    || s.equals(MODE_WSJTX_DEEP_PARALLEL)
                    || s.equals(MODE_BAND)) {
                modes.add(s);
            }
        }
        return modes;
    }

    private void runBench(String dirPath, List<String> modes) throws IOException {
        File dir = new File(dirPath);
        if (!dir.isDirectory()) {
            logLine("目录不存在: " + dirPath);
            return;
        }

        List<File> wavs = new ArrayList<>();
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (!f.isFile() || !f.getName().toLowerCase(Locale.US).endsWith(".wav")) {
                    continue;
                }
                if (fileFilter != null && !fileFilter.isEmpty()
                        && !f.getName().equalsIgnoreCase(fileFilter)) {
                    continue;
                }
                String base = f.getName().substring(0, f.getName().length() - 4);
                if (new File(dir, base + ".txt").isFile()) {
                    wavs.add(f);
                }
            }
        }
        Collections.sort(wavs, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });

        logLine("目录: " + dirPath);
        logLine("文件数: " + wavs.size() + "，挡位: " + modes);

        FT8SignalListener listener = new FT8SignalListener(null, null);

        for (String mode : modes) {
            Stats total = new Stats();
            StringBuilder detail = new StringBuilder();
            detail.append("mode=").append(mode).append('\n');

            // 逐条报文导出，供 JTDX 等外部解码器交叉验证
            File msgFile = new File(getExternalFilesDir(null), "decoded_" + mode + ".tsv");
            BufferedWriter msgWriter = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(msgFile), UTF8));
            logLine("报文导出: " + msgFile.getAbsolutePath());

            for (File wav : wavs) {
                String base = wav.getName().substring(0, wav.getName().length() - 4);
                File txt = new File(dir, base + ".txt");

                List<String> expected = parseExpected(txt);
                float[] audio;
                try {
                    audio = loadWav(wav);
                } catch (Exception e) {
                    logLine(mode + " " + wav.getName() + " 读取失败: " + e.getMessage());
                    continue;
                }
                if (audio.length < FT8Common.SAMPLE_RATE * 5) {
                    logLine(mode + " " + wav.getName() + " 音频过短，跳过");
                    continue;
                }

                long start = System.currentTimeMillis();
                List<Ft8Message> messages = decodeOne(listener, mode, audio, start);
                long elapsed = System.currentTimeMillis() - start;

                if (messages == null) {
                    total.unsupported = true;
                    detail.append("UNSUPPORTED\n");
                    logLine(mode + " " + wav.getName() + " 解码库不可用");
                    break;
                }

                List<String> got = new ArrayList<>();
                for (Ft8Message msg : messages) {
                    try {
                        String text = msg.getMessageText();
                        String key = makeKey(text);
                        if (!key.isEmpty()) got.add(key);
                        msgWriter.write(wav.getName());
                        msgWriter.write('\t');
                        msgWriter.write(text == null ? "" : text.replace('\t', ' '));
                        msgWriter.write('\t');
                        msgWriter.write(Integer.toString(msg.snr));
                        msgWriter.write('\t');
                        msgWriter.write(String.format(Locale.US, "%.2f", msg.time_sec));
                        msgWriter.write('\t');
                        msgWriter.write(String.format(Locale.US, "%.0f", msg.freq_hz));
                        msgWriter.write('\n');
                    } catch (Throwable e) {
                        Log.w(TAG, "getMessageText失败: " + e.getMessage());
                    }
                }
                msgWriter.flush();

                List<String> uniqueGot = new ArrayList<>(new LinkedHashSet<>(got));
                int duplicates = got.size() - uniqueGot.size();

                int hits = 0;
                for (String e : expected) {
                    if (uniqueGot.contains(e)) hits++;
                }
                int extra = 0;
                for (String g : uniqueGot) {
                    if (!expected.contains(g)) extra++;
                }

                if (dump) {
                    logLine("--- " + wav.getName() + " ---");
                    for (String e : expected) logLine("EXP " + e);
                    for (String g : uniqueGot) {
                        logLine("GOT " + g + (expected.contains(g) ? "" : "  [EXTRA]"));
                    }
                    for (String e : expected) {
                        if (!uniqueGot.contains(e)) logLine("MISS " + e);
                    }
                    if (dumpRaw && mode.startsWith(MODE_WSJTX)) {
                        dumpWsjtxRaw(audio, mode.equals(MODE_WSJTX_DEEP)
                                || mode.equals(MODE_WSJTX_DEEP_PARALLEL));
                    }
                }
                if (checkText && (mode.equals(MODE_WSJTX) || mode.equals(MODE_WSJTX_DEEP))) {
                    checkWsjtxText(wav.getName(), audio, mode.equals(MODE_WSJTX_DEEP), uniqueGot);
                }

                total.files++;
                total.expected += expected.size();
                total.hits += hits;
                total.extra += extra;
                total.duplicates += duplicates;
                total.times.add(elapsed);

                String line = String.format(Locale.US,
                        "%-9s %-24s %3d/%-3d  missed %2d  extra %2d  %7.1f ms",
                        mode, wav.getName(), hits, expected.size()
                        , expected.size() - hits, extra, (double) elapsed);
                detail.append(line).append('\n');
                logLine(line);
            }

            msgWriter.flush();
            msgWriter.close();

            String summary = total.summary(mode);
            detail.append(summary).append('\n');
            logLine(summary);

            File out = new File(getExternalFilesDir(null), "bench_" + mode + ".txt");
            writeText(out, detail.toString());
            logLine("结果已写入: " + out.getAbsolutePath());
        }

        logLine("BENCH_DONE");
    }

    /**
     * 按挡位调用正式的解码路径。
     *
     * @return null 表示 wsjtx 原生库不可用
     */
    private List<Ft8Message> decodeOne(FT8SignalListener listener, String mode,
                                      float[] audio, long start) {
        switch (mode) {
            case MODE_FAST:
                return listener.decodeByLegacy(BENCH_UTC, audio, false, start);
            case MODE_DEEP:
                return listener.decodeByLegacy(BENCH_UTC, audio, true, start);
            case MODE_WSJTX:
                return listener.decodeByWsjtx(BENCH_UTC, audio, false);
            case MODE_WSJTX_DEEP:
                return listener.decodeByWsjtx(BENCH_UTC, audio, true);
            case MODE_WSJTX_PARALLEL:
                return listener.decodeByWsjtxParallel(BENCH_UTC, audio, false);
            case MODE_WSJTX_DEEP_PARALLEL:
                return listener.decodeByWsjtxParallel(BENCH_UTC, audio, true);
            case MODE_BAND:
                List<Ft8Message> band = new ArrayList<>();
                WsjtxDecoder.Row[] rows = WsjtxDecoder.decodeOnce(
                        audio, FT8Common.SAMPLE_RATE, false,
                        getIntent().getIntExtra("profile", 0), bandLo, bandHi);
                for (WsjtxDecoder.Row row : rows) {
                    Ft8Message msg = new Ft8Message(FT8Common.FT8_MODE);
                    msg.isValid = true;
                    msg.snr = Math.round(row.snr);
                    msg.time_sec = row.dt;
                    msg.freq_hz = row.freq;
                    msg.extraInfo = row.text;
                    msg.callsignTo = "";
                    msg.callsignFrom = "";
                    msg.maidenGrid = "";
                    band.add(msg);
                }
                return band;
            default:
                return new ArrayList<>();
        }
    }

    /** 校验 mfsk 原始报文与 Ft8Message 还原后的文本是否一致。 */
    private void checkWsjtxText(String name, float[] audio, boolean deep,
                                List<String> converted) {
        try {
            WsjtxDecoder.Row[] rows = WsjtxDecoder.decodeOnce(
                    audio, FT8Common.SAMPLE_RATE, deep);
            LinkedHashSet<String> raw = new LinkedHashSet<>();
            if (rows != null) {
                for (WsjtxDecoder.Row row : rows) {
                    String key = makeKey(row.text);
                    if (!key.isEmpty()) raw.add(key);
                }
            }
            LinkedHashSet<String> conv = new LinkedHashSet<>(converted);
            if (raw.equals(conv)) {
                logLine("TEXT_OK " + name + " (" + raw.size() + ")");
                return;
            }
            logLine("TEXT_DIFF " + name);
            for (String k : raw) {
                if (!conv.contains(k)) logLine("  RAW_ONLY " + k);
            }
            for (String k : conv) {
                if (!raw.contains(k)) logLine("  CONV_ONLY " + k);
            }
        } catch (Throwable e) {
            logLine("TEXT_FAIL " + name + " " + e);
        }
    }

    /** dump 模式：输出 mfsk-core 的原始报文，便于排查文本格式差异。 */
    private void dumpWsjtxRaw(float[] audio, boolean deep) {
        try {
            WsjtxDecoder.Row[] rows = WsjtxDecoder.decodeOnce(
                    audio, FT8Common.SAMPLE_RATE, deep);
            if (rows == null) return;
            for (WsjtxDecoder.Row row : rows) {
                logLine("RAW " + row.text);
            }
        } catch (Throwable e) {
            logLine("RAW_FAIL " + e);
        }
    }

    /** 与 ft8_w ft8_cli 的 make_key 保持一致的比较口径。 */
    private static String makeKey(String text) {
        if (text == null) return "";
        String[] tokens = text.toUpperCase(Locale.US).trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (String token : tokens) {
            if (token.isEmpty()) continue;
            String t = token;
            if (t.length() >= 2 && t.startsWith("<") && t.endsWith(">")) {
                t = "<...>";
            }
            if (sb.length() > 0) sb.append(' ');
            sb.append(t);
            n++;
            if (n >= 3) break;
        }
        return sb.toString();
    }

    private static List<String> parseExpected(File txt) throws IOException {
        //与 ft8_w 的基准口径一致：保留重复的期望行，不做去重
        List<String> keys = new ArrayList<>();
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(txt), UTF8));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                int tilde = line.indexOf('~');
                if (tilde < 0) continue;
                String key = makeKey(line.substring(tilde + 1));
                if (!key.isEmpty()) keys.add(key);
            }
        } finally {
            reader.close();
        }
        return keys;
    }

    /** 读取 12kHz 单声道 WAV（16bit PCM 或 32bit float），归一化到 -1~1。 */
    private static float[] loadWav(File file) throws IOException {
        byte[] data = readAll(file);
        if (data.length < 44
                || data[0] != 'R' || data[1] != 'I' || data[2] != 'F' || data[3] != 'F') {
            throw new IOException("不是有效的WAV文件");
        }

        int pos = 12;
        int audioFormat = -1;
        int channels = 0;
        int bits = 0;
        long rate = 0;
        int dataOffset = -1;
        int dataSize = 0;

        while (pos + 8 <= data.length) {
            String id = new String(data, pos, 4, "US-ASCII");
            int size = le32(data, pos + 4);
            int body = pos + 8;
            if (id.equals("fmt ")) {
                audioFormat = le16(data, body);
                channels = le16(data, body + 2);
                rate = le32u(data, body + 4);
                bits = le16(data, body + 14);
            } else if (id.equals("data")) {
                dataOffset = body;
                dataSize = Math.min(size, data.length - body);
                break;
            }
            pos = body + size + (size & 1);
        }

        if (dataOffset < 0 || channels != 1) {
            throw new IOException("仅支持单声道WAV");
        }
        if (rate != FT8Common.SAMPLE_RATE) {
            throw new IOException("采样率不是12000: " + rate);
        }

        if (audioFormat == 3 && bits == 32) {
            float[] out = new float[dataSize / 4];
            for (int i = 0; i < out.length; i++) {
                out[i] = Float.intBitsToFloat(le32(data, dataOffset + i * 4));
            }
            return out;
        }
        if (audioFormat != 1 || bits != 16) {
            throw new IOException("仅支持16bit PCM或32bit float");
        }

        float[] out = new float[dataSize / 2];
        for (int i = 0; i < out.length; i++) {
            short s = (short) le16(data, dataOffset + i * 2);
            out[i] = s / 32768.0f;
        }
        return out;
    }

    private static byte[] readAll(File file) throws IOException {
        FileInputStream in = new FileInputStream(file);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) file.length());
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    private static int le16(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8);
    }

    private static int le32(byte[] b, int off) {
        return (b[off] & 0xff)
                | ((b[off + 1] & 0xff) << 8)
                | ((b[off + 2] & 0xff) << 16)
                | ((b[off + 3] & 0xff) << 24);
    }

    private static long le32u(byte[] b, int off) {
        return le32(b, off) & 0xffffffffL;
    }

    private static void writeText(File file, String text) {
        try {
            FileOutputStream out = new FileOutputStream(file);
            OutputStreamWriter writer = new OutputStreamWriter(out, UTF8);
            try {
                writer.write(text);
            } finally {
                writer.close();
            }
        } catch (IOException e) {
            Log.e(TAG, "写入结果失败: " + e.getMessage());
        }
    }

    private void logLine(final String line) {
        Log.i(TAG, line);
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                statusView.append(line + "\n");
            }
        });
    }

    private static final class Stats {
        int files;
        long expected;
        long hits;
        long extra;
        long duplicates;
        boolean unsupported;
        final List<Long> times = new ArrayList<>();

        String summary(String mode) {
            if (unsupported) {
                return "===== " + mode + " =====\n解码库不可用";
            }
            if (expected == 0) {
                return "===== " + mode + " =====\n没有可用的对照消息";
            }
            Collections.sort(times);
            double avg = 0;
            for (long t : times) avg += t;
            avg = times.isEmpty() ? 0 : avg / times.size();
            long p50 = percentile(50);
            long p95 = percentile(95);

            return String.format(Locale.US,
                    "===== %s =====%n"
                            + "文件数        : %d%n"
                            + "期望消息数    : %d%n"
                            + "命中          : %d%n"
                            + "漏解 (missed) : %d (%.1f%%)%n"
                            + "多余 (extra)  : %d (%.1f%%)%n"
                            + "重复 (dup)    : %d%n"
                            + "召回率 Recall : %.1f%%%n"
                            + "解码耗时      : avg %.1f ms, P50 %d ms, P95 %d ms, max %d ms",
                    mode, files, expected, hits,
                    expected - hits, 100.0 * (expected - hits) / expected,
                    extra, 100.0 * extra / expected,
                    duplicates,
                    100.0 * hits / expected,
                    avg, p50, p95, times.isEmpty() ? 0 : times.get(times.size() - 1));
        }

        private long percentile(int p) {
            if (times.isEmpty()) return 0;
            int idx = (int) Math.ceil(times.size() * p / 100.0) - 1;
            if (idx < 0) idx = 0;
            return times.get(Math.min(idx, times.size() - 1));
        }
    }
}
