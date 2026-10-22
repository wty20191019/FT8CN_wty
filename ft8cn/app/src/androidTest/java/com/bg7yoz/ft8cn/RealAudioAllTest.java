package com.bg7yoz.ft8cn;

import static org.junit.Assert.assertTrue;

import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.bg7yoz.ft8cn.ft8listener.FT8SignalListener;
import com.bg7yoz.ft8cn.ft8listener.Ft8LibJni;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 全量真实 WAV：旧快�?vs 新核心快速（25 次迭代）�? * 统计召回率、SNR 偏差，作为并行模式的上线前基线�? */
@RunWith(AndroidJUnit4.class)
public class RealAudioAllTest {
    private static final String TAG = "RealAudioAll";

    @Test
    public void compareAll() throws Exception {
        File dir = new File(InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getCacheDir(), "ft8test");
        Assume.assumeTrue("缺少真实 WAV 测试目录: " + dir, dir.isDirectory());
        File[] files = dir.listFiles((d, n) -> n.endsWith(".wav"));
        Assume.assumeTrue("没有 WAV", files != null && files.length > 0);

        FT8SignalListener old = new FT8SignalListener(null, null);
        int filesCompared = 0;
        int oldTotal = 0, newTotal = 0, commonTotal = 0;
        int snrAbsSum = 0, snrCount = 0, snrMax = 0;
        int missingTotal = 0, extraTotal = 0;

        for (File f : files) {
            if (f.length() == 192044 && !f.getName().contains("12k")) continue; // �?12k 测试文件
            float[] audio = readWav(f);
            List<Ft8Message> oldList = decodeOldFast(old, audio);
            List<Ft8Message> newList = decodeNewFast(audio, 500, 200);
            Map<String, Ft8Message> oldMap = byText(oldList);
            Map<String, Ft8Message> newMap = byText(newList);

            int common = 0;
            int missing = 0, extra = 0;
            for (Map.Entry<String, Ft8Message> e : oldMap.entrySet()) {
                Ft8Message n = newMap.get(e.getKey());
                if (n == null) {
                    missing++;
                } else {
                    common++;
                    int diff = Math.abs(e.getValue().snr - n.snr);
                    snrAbsSum += diff;
                    snrCount++;
                    if (diff > snrMax) snrMax = diff;
                }
            }
            for (String text : newMap.keySet()) {
                if (!oldMap.containsKey(text)) extra++;
            }

            filesCompared++;
            oldTotal += oldMap.size();
            newTotal += newMap.size();
            commonTotal += common;
            missingTotal += missing;
            extraTotal += extra;

            if (missing > 0 || extra > 0) {
                StringBuilder sb = new StringBuilder();
                for (String text : oldMap.keySet()) {
                    if (!newMap.containsKey(text)) sb.append("\n  - ").append(text);
                }
                for (String text : newMap.keySet()) {
                    if (!oldMap.containsKey(text)) sb.append("\n  + ").append(text);
                }
                Log.i(TAG, String.format("%s: old=%d new=%d common=%d%s",
                        f.getName(), oldMap.size(), newMap.size(), common, sb));
            }
        }

        Log.i(TAG, String.format(
                "SUMMARY files=%d old=%d new=%d common=%d missing=%d extra=%d snrAbsAvg=%.2f snrMax=%d",
                filesCompared, oldTotal, newTotal, commonTotal, missingTotal, extraTotal,
                snrCount == 0 ? 0 : (snrAbsSum / (float) snrCount), snrMax));
    }

    private Map<String, Ft8Message> byText(List<Ft8Message> list) {
        Map<String, Ft8Message> map = new HashMap<>();
        for (Ft8Message m : list) {
            String text = m.getMessageText();
            if (text == null || text.length() == 0) continue;
            Ft8Message old = map.get(text);
            if (old == null || m.snr > old.snr) map.put(text, m);
        }
        return map;
    }

    private List<Ft8Message> decodeOldFast(FT8SignalListener listener, float[] audio) {
        List<Ft8Message> result = new ArrayList<>();
        long decoder = listener.InitDecoder(0, FT8Common.SAMPLE_RATE, audio.length, true);
        try {
            listener.DecoderMonitorPressFloat(audio, decoder);
            listener.setDecodeMode(decoder, false);
            int n = listener.DecoderFt8FindSync(decoder);
            for (int i = 0; i < n; i++) {
                Ft8Message m = new Ft8Message(FT8Common.FT8_MODE);
                if (listener.DecoderFt8Analysis(i, decoder, m) && m.isValid) result.add(m);
            }
        } finally {
            listener.DeleteDecoder(decoder);
        }
        return result;
    }

    private List<Ft8Message> decodeNewFast(float[] audio, int maxCandidates, int iterations) {
        List<Ft8Message> result = new ArrayList<>();
        long decoder = Ft8LibJni.nativeCreate(FT8Common.SAMPLE_RATE, audio.length,
                0f, 5950f, maxCandidates, iterations);
        long worker = 0;
        try {
            Ft8LibJni.nativePress(decoder, audio);
            worker = Ft8LibJni.nativeCreateWorker(decoder);
            int n = Ft8LibJni.nativeFindRange(worker, 0, Integer.MAX_VALUE);
            for (int i = 0; i < n; i++) {
                if (!Ft8LibJni.nativeDecode(worker, i, iterations)) continue;
                Ft8Message m = new Ft8Message(FT8Common.FT8_MODE);
                Ft8LibJni.nativeFillMessage(worker, m);
                if (m.isValid) result.add(m);
            }
        } finally {
            if (worker != 0) Ft8LibJni.nativeFreeWorker(worker);
            Ft8LibJni.nativeFree(decoder);
        }
        return result;
    }

    private float[] readWav(File file) throws Exception {
        byte[] all = new byte[(int) file.length()];
        try (DataInputStream in = new DataInputStream(new FileInputStream(file))) {
            in.readFully(all);
        }
        int pos = 12, dataOffset = -1, dataSize = 0;
        while (pos + 8 <= all.length) {
            String id = new String(all, pos, 4, "US-ASCII");
            int size = littleInt(all, pos + 4);
            pos += 8;
            if ("data".equals(id)) {
                dataOffset = pos;
                dataSize = size;
                break;
            }
            pos += size + (size & 1);
        }
        int samples = Math.min(dataSize, all.length - dataOffset) / 2;
        float[] out = new float[samples];
        for (int i = 0; i < samples; i++) {
            int lo = all[dataOffset + i * 2] & 0xFF;
            int hi = all[dataOffset + i * 2 + 1];
            out[i] = (short) ((hi << 8) | lo) / 32768.0f;
        }
        return out;
    }

    private int littleInt(byte[] b, int p) {
        return (b[p] & 0xFF) | ((b[p + 1] & 0xFF) << 8)
                | ((b[p + 2] & 0xFF) << 16) | ((b[p + 3] & 0xFF) << 24);
    }
}
