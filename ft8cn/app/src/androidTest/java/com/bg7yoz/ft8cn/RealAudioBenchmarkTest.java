package com.bg7yoz.ft8cn;

import android.util.Log;

import androidx.lifecycle.MutableLiveData;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.bg7yoz.ft8cn.ft8listener.FT8SignalListener;
import com.bg7yoz.ft8cn.ft8listener.Ft8LibJni;
import com.bg7yoz.ft8cn.ft8listener.OnFt8Listen;
import com.bg7yoz.ft8cn.ft8listener.ParallelDecodeEngine;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 真实 WAV 基准：旧快�?vs 新核心快�?并行，用于选择切片数、线程数默认值�? * 结果只写 logcat，不做严格断言�? */
@RunWith(AndroidJUnit4.class)
public class RealAudioBenchmarkTest {
    private static final String TAG = "RealAudioBenchmark";
    private static final String[] FILES = {"test_01.wav", "websdr_test1.wav", "test_20.wav"};

    @Test
    public void benchmark() throws Exception {
        File dir = new File(InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getCacheDir(), "ft8test");
        FT8SignalListener old = new FT8SignalListener(null, null);

        for (String name : FILES) {
            File f = new File(dir, name);
            if (!f.isFile()) continue;
            float[] audio = readWav(f);

            long t0 = System.currentTimeMillis();
            List<Ft8Message> oldMessages = decodeOldFast(old, audio);
            long tOld = System.currentTimeMillis() - t0;

            t0 = System.currentTimeMillis();
            List<Ft8Message> new25 = decodeNewFast(audio, 500, 25);
            long tNew25 = System.currentTimeMillis() - t0;

            t0 = System.currentTimeMillis();
            List<Ft8Message> new200 = decodeNewFast(audio, 500, 200);
            long tNew200 = System.currentTimeMillis() - t0;

            Log.i(TAG, String.format("%s: old=%d/%dms new25=%d/%dms new200=%d/%dms",
                    name, unique(oldMessages), tOld, unique(new25), tNew25, unique(new200), tNew200));

            int[] slices = {6, 8};
            int[] threads = {0, 4};
            for (int s : slices) {
                for (int th : threads) {
                    GeneralVariables.parallelSlices = s;
                    GeneralVariables.parallelThreads = th;
                    GeneralVariables.parallelTimeout = 7000;
                    final Set<String> texts = Collections.synchronizedSet(new HashSet<>());
                    OnFt8Listen listener = new OnFt8Listen() {
                        @Override
                        public void beforeListen(long utc) {
                        }

                        @Override
                        public void afterDecode(long utc, float time_sec, int sequential,
                                                ArrayList<Ft8Message> messages, boolean isDeep) {
                            for (Ft8Message m : messages) {
                                String text = m.getMessageText();
                                if (text != null) texts.add(text);
                            }
                        }
                    };
                    ParallelDecodeEngine engine = new ParallelDecodeEngine(listener,
                            new MutableLiveData<>(), millis -> {
                    });
                    t0 = System.currentTimeMillis();
                    engine.decode(System.currentTimeMillis(), audio);
                    long elapsed = System.currentTimeMillis() - t0;
                    Log.i(TAG, String.format("  %s slices=%d threads=%d -> %d unique, %dms",
                            name, s, th, texts.size(), elapsed));
                }
            }
        }
    }

    private int unique(List<Ft8Message> list) {
        Set<String> set = new HashSet<>();
        for (Ft8Message m : list) {
            String text = m.getMessageText();
            if (text != null) set.add(text);
        }
        return set.size();
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
