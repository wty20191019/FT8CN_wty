package com.bg7yoz.ft8cn;

import static org.junit.Assert.assertTrue;

import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.bg7yoz.ft8cn.ft8listener.FT8SignalListener;
import com.bg7yoz.ft8cn.ft8listener.Ft8LibJni;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用 D:\Desktop\ft8_w\test 的真实 WAV 对比：
 *   旧快速解码（libft8cn.so） vs 新并行核心的快速轮（原版 ft8_lib）。
 *
 * WAV 已通过 adb 复制到 target context 的 cache/ft8test 目录。
 */
@RunWith(AndroidJUnit4.class)
public class RealAudioParityTest {
    private static final String TAG = "RealAudioParity";
    private static final String[] FILES = {
            "test_01.wav",
            "websdr_test1.wav",
            "191111_110115.wav",
            "test_20.wav",
            "websdr_test5.wav",
    };

    @Test
    public void compareOldFastAndNewFast() throws Exception {
        File dir = new File(InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getCacheDir(), "ft8test");
        assertTrue("缺少测试目录: " + dir, dir.isDirectory());

        FT8SignalListener old = new FT8SignalListener(null, null);
        for (String name : FILES) {
            File f = new File(dir, name);
            if (!f.isFile()) {
                Log.w(TAG, "skip missing " + name);
                continue;
            }
            float[] audio = readWav(f);
            List<Ft8Message> oldMessages = decodeOldFast(old, audio);
            List<Ft8Message> newMessages = decodeNewFast(audio);

            Map<String, Ft8Message> oldMap = byText(oldMessages);
            Map<String, Ft8Message> newMap = byText(newMessages);
            int common = 0;
            StringBuilder detail = new StringBuilder();
            for (Map.Entry<String, Ft8Message> e : oldMap.entrySet()) {
                Ft8Message n = newMap.get(e.getKey());
                if (n != null) {
                    ++common;
                    Ft8Message o = e.getValue();
                    detail.append(String.format("\n  %s | old snr=%d dt=%.2f f=%.0f | new snr=%d dt=%.2f f=%.0f",
                            e.getKey(), o.snr, o.time_sec, o.freq_hz,
                            n.snr, n.time_sec, n.freq_hz));
                }
            }
            StringBuilder newOnly = new StringBuilder();
            for (String text : newMap.keySet()) {
                if (!oldMap.containsKey(text)) {
                    if (newOnly.length() < 8) newOnly.append("\n  + ").append(text);
                }
            }
            StringBuilder oldOnly = new StringBuilder();
            for (Map.Entry<String, Ft8Message> e : oldMap.entrySet()) {
                if (!newMap.containsKey(e.getKey())) {
                    Ft8Message o = e.getValue();
                    oldOnly.append(String.format("\n  - %s | snr=%d dt=%.2f f=%.0f",
                            e.getKey(), o.snr, o.time_sec, o.freq_hz));
                }
            }
            Log.i(TAG, String.format("%s: oldRaw=%d oldUnique=%d newRaw=%d newUnique=%d common=%d%s%s%s",
                    name, oldMessages.size(), oldMap.size(), newMessages.size(), newMap.size(),
                    common, oldOnly, newOnly, detail));
            assertTrue(name + " 没有共同消息", common > 0 || oldMessages.isEmpty());
        }
    }

    private Map<String, Ft8Message> byText(List<Ft8Message> list) {
        Map<String, Ft8Message> map = new LinkedHashMap<>();
        for (Ft8Message m : list) {
            String text = m.getMessageText();
            if (text == null || text.length() == 0) continue;
            Ft8Message old = map.get(text);
            if (old == null || m.snr > old.snr) {
                map.put(text, m);
            }
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
                if (listener.DecoderFt8Analysis(i, decoder, m) && m.isValid) {
                    m.isWeakSignal = false;
                    result.add(m);
                }
            }
        } finally {
            listener.DeleteDecoder(decoder);
        }
        return result;
    }

    private List<Ft8Message> decodeNewFast(float[] audio) {
        List<Ft8Message> result = new ArrayList<>();
        long decoder = Ft8LibJni.nativeCreate(FT8Common.SAMPLE_RATE, audio.length,
                0f, 5950f, 500, 200);
        long worker = 0;
        try {
            Ft8LibJni.nativePress(decoder, audio);
            worker = Ft8LibJni.nativeCreateWorker(decoder);
            int n = Ft8LibJni.nativeFindRange(worker, 0, Integer.MAX_VALUE);
            for (int i = 0; i < n; i++) {
                if (!Ft8LibJni.nativeDecode(worker, i, 200)) continue;
                Ft8Message m = new Ft8Message(FT8Common.FT8_MODE);
                Ft8LibJni.nativeFillMessage(worker, m);
                if (m.isValid) {
                    m.isWeakSignal = false;
                    result.add(m);
                }
            }
        } finally {
            if (worker != 0) Ft8LibJni.nativeFreeWorker(worker);
            Ft8LibJni.nativeFree(decoder);
        }
        return result;
    }

    /**
     * 只支持本项目测试用的 12 kHz / 16-bit / 单声道 WAV。
     */
    private float[] readWav(File file) throws IOException {
        byte[] all = new byte[(int) file.length()];
        try (DataInputStream in = new DataInputStream(new FileInputStream(file))) {
            in.readFully(all);
        }
        int pos = 12;
        int dataOffset = -1;
        int dataSize = 0;
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
        if (dataOffset < 0) throw new IOException("no data chunk");
        int samples = Math.min(dataSize, all.length - dataOffset) / 2;
        float[] out = new float[samples];
        for (int i = 0; i < samples; i++) {
            int lo = all[dataOffset + i * 2] & 0xFF;
            int hi = all[dataOffset + i * 2 + 1];
            short s = (short) ((hi << 8) | lo);
            out[i] = s / 32768.0f;
        }
        return out;
    }

    private int littleInt(byte[] b, int p) {
        return (b[p] & 0xFF) | ((b[p + 1] & 0xFF) << 8)
                | ((b[p + 2] & 0xFF) << 16) | ((b[p + 3] & 0xFF) << 24);
    }
}
