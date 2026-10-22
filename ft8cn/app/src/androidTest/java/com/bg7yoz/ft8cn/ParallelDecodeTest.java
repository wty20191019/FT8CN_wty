package com.bg7yoz.ft8cn;

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import androidx.lifecycle.MutableLiveData;

import com.bg7yoz.ft8cn.ft8listener.Ft8LibJni;
import com.bg7yoz.ft8cn.ft8listener.OnFt8Listen;
import com.bg7yoz.ft8cn.ft8listener.ParallelDecodeEngine;
import com.bg7yoz.ft8cn.ft8transmit.GenerateFT8;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 在设备/模拟器上验证原版 ft8_lib 并行核心的完整 JNI 路径：
 * 生成 -> 瀑布图 -> 分段候选 -> 解码 -> 填充 Ft8Message -> 抵消。
 */
@RunWith(AndroidJUnit4.class)
public class ParallelDecodeTest {

    @Test
    public void decodeSyntheticCqMessage() {
        Ft8Message tx = new Ft8Message("CQ", "BG7YOZ", "OL32");
        tx.i3 = 1;
        tx.n3 = 0;
        float[] audio = GenerateFT8.generateFt8(tx, 1000f, FT8Common.SAMPLE_RATE);
        assertNotNull("生成音频失败", audio);
        assertTrue("音频长度异常", audio.length > 100000);

        long decoder = Ft8LibJni.nativeCreate(FT8Common.SAMPLE_RATE, audio.length,
                0f, 5950f, 200, 25);
        assertNotEquals("nativeCreate 失败", 0, decoder);

        long worker = 0;
        try {
            Ft8LibJni.nativePress(decoder, audio);
            worker = Ft8LibJni.nativeCreateWorker(decoder);
            assertNotEquals("nativeCreateWorker 失败", 0, worker);

            int count = Ft8LibJni.nativeFindRange(worker, 0, Integer.MAX_VALUE);
            assertTrue("没有找到候选", count > 0);

            Ft8Message decoded = null;
            for (int i = 0; i < count; i++) {
                if (!Ft8LibJni.nativeDecode(worker, i, 25)) continue;
                Ft8Message candidate = new Ft8Message(FT8Common.FT8_MODE);
                Ft8LibJni.nativeFillMessage(worker, candidate);
                if (candidate.isValid && candidate.getMessageText().contains("BG7YOZ")) {
                    decoded = candidate;
                    break;
                }
            }
            assertNotNull("没有解出 CQ BG7YOZ", decoded);
            assertTrue("频率偏差过大", Math.abs(decoded.freq_hz - 1000f) < 20f);
            assertNotNull("payload 为空", decoded.payload);

            // 抵消路径：不应崩溃，且之后仍能重新搜索
            Ft8LibJni.nativeSubtract(decoder, decoded.payload, decoded.freq_hz, decoded.time_sec);
            int after = Ft8LibJni.nativeFindRange(worker, 0, Integer.MAX_VALUE);
            assertTrue("抵消后候选数异常", after >= 0);
        } finally {
            if (worker != 0) Ft8LibJni.nativeFreeWorker(worker);
            Ft8LibJni.nativeFree(decoder);
        }
    }

    @Test
    public void decodeNonStandardCallsignDoesNotCrash() {
        Ft8Message tx = new Ft8Message("CQ", "BG7YOZ/P", "OL32");
        tx.i3 = 4;
        tx.n3 = 0;
        float[] audio = GenerateFT8.generateFt8(tx, 800f, FT8Common.SAMPLE_RATE);
        assertNotNull(audio);

        long decoder = Ft8LibJni.nativeCreate(FT8Common.SAMPLE_RATE, audio.length,
                0f, 5950f, 200, 25);
        assertNotEquals(0, decoder);
        long worker = 0;
        try {
            Ft8LibJni.nativePress(decoder, audio);
            worker = Ft8LibJni.nativeCreateWorker(decoder);
            int count = Ft8LibJni.nativeFindRange(worker, 0, Integer.MAX_VALUE);
            boolean decoded = false;
            for (int i = 0; i < count; i++) {
                if (!Ft8LibJni.nativeDecode(worker, i, 25)) continue;
                Ft8Message candidate = new Ft8Message(FT8Common.FT8_MODE);
                Ft8LibJni.nativeFillMessage(worker, candidate);
                if (candidate.isValid && candidate.getMessageText() != null
                        && candidate.getMessageText().length() > 0) {
                    decoded = true;
                    break;
                }
            }
            assertTrue("非标准呼号消息未解出", decoded);
        } finally {
            if (worker != 0) Ft8LibJni.nativeFreeWorker(worker);
            Ft8LibJni.nativeFree(decoder);
        }
    }

    @Test
    public void engineDecodesAndCallsBack() {
        Ft8Message tx = new Ft8Message("CQ", "BG7YOZ", "OL32");
        tx.i3 = 1;
        tx.n3 = 0;
        float[] audio = GenerateFT8.generateFt8(tx, 1200f, FT8Common.SAMPLE_RATE);
        assertNotNull(audio);

        GeneralVariables.parallelSlices = 2;
        GeneralVariables.parallelThreads = 2;
        GeneralVariables.parallelTimeout = 3000;

        List<Ft8Message> fast = Collections.synchronizedList(new ArrayList<>());
        List<Ft8Message> deep = Collections.synchronizedList(new ArrayList<>());
        OnFt8Listen listener = new OnFt8Listen() {
            @Override
            public void beforeListen(long utc) {
            }

            @Override
            public void afterDecode(long utc, float time_sec, int sequential,
                                    ArrayList<Ft8Message> messages, boolean isDeep) {
                if (isDeep) deep.addAll(messages);
                else fast.addAll(messages);
            }
        };

        ParallelDecodeEngine engine = new ParallelDecodeEngine(listener, new MutableLiveData<>(),
                millis -> {
                });
        assertTrue("并行引擎执行失败", engine.decode(System.currentTimeMillis(), audio));

        boolean found = false;
        for (Ft8Message m : fast) {
            if (m.getMessageText().contains("BG7YOZ")) found = true;
        }
        for (Ft8Message m : deep) {
            if (m.getMessageText().contains("BG7YOZ")) found = true;
        }
        assertTrue("并行引擎没有回调 CQ BG7YOZ", found);
    }
}
