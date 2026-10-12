package com.bg7yoz.ft8cn.ft8listener;
/**
 * 用于监听音频的类。监听通过时钟UtcTimer来控制周期，通过OnWaveDataListener接口来读取音频数据。
 *
 * <p>解码由 ft8_w 新解码库（libft8.so，见 {@link com.ft8.nativecore.Ft8Native}）完成：
 * 瀑布 → 候选 → LDPC(BP) → OSD 兜底 → 多遍信号消除（SIC） → 文本 + 频谱噪声底 SNR。
 * 多遍消除与 OSD 都在库内部完成，因此 Java 侧只需把整时隙音频一次性交给库。
 *
 * @author BGY70Z
 * @date 2023-03-20
 */

import android.util.Log;

import androidx.lifecycle.MutableLiveData;

import com.bg7yoz.ft8cn.FT8Common;
import com.bg7yoz.ft8cn.Ft8Message;
import com.bg7yoz.ft8cn.GeneralVariables;
import com.bg7yoz.ft8cn.database.DatabaseOpr;
import com.bg7yoz.ft8cn.ft8signal.FT8Package;
import com.bg7yoz.ft8cn.timer.OnUtcTimer;
import com.bg7yoz.ft8cn.timer.UtcTimer;
import com.bg7yoz.ft8cn.wave.OnGetVoiceDataDone;
import com.ft8.nativecore.Ft8Native;

import java.util.ArrayList;

public class FT8SignalListener {
    private static final String TAG = "FT8SignalListener";
    private final UtcTimer utcTimer;
    private final OnFt8Listen onFt8Listen;//当开始监听，解码结束后触发的事件
    public MutableLiveData<Long> decodeTimeSec = new MutableLiveData<>();//解码的时长
    public long timeSec = 0;//解码的时长

    private OnWaveDataListener onWaveDataListener;

    private DatabaseOpr db;


    public interface OnWaveDataListener {
        void getVoiceData(int duration, boolean afterDoneRemove, OnGetVoiceDataDone getVoiceDataDone);
    }

    public FT8SignalListener(DatabaseOpr db, OnFt8Listen onFt8Listen) {
        this.onFt8Listen = onFt8Listen;
        this.db = db;

        //创建动作触发器，与UTC时间同步，以15秒一个周期，DoOnSecTimer是在周期起始时触发的事件。150是15秒
        utcTimer = new UtcTimer(FT8Common.FT8_SLOT_TIME_M, false, new OnUtcTimer() {
            @Override
            public void doHeartBeatTimer(long utc) {//不触发时的时钟信息
            }

            @Override
            public void doOnSecTimer(long utc) {//当指定间隔时触发时
                Log.d(TAG, String.format("触发录音,%d", utc));
                runRecorde(utc);
            }
        });
    }

    public void startListen() {
        utcTimer.start();
    }

    public void stopListen() {
        utcTimer.stop();
    }

    public boolean isListening() {
        return utcTimer.isRunning();
    }

    /**
     * 获取当前时间的偏移量，这里包括总的时钟偏移，也包括本实例的偏移
     *
     * @return int
     */
    public int time_Offset() {
        return utcTimer.getTime_sec() + UtcTimer.delay;
    }

    /**
     * 录音。在后台以多线程的方式录音，录音自动生成一个临时的Wav格式文件。
     * 有两个回调函数，用于开始录音时和结束录音时。当结束录音时，激活解码程序。
     *
     * @param utc 当前解码的UTC时间
     */
    private void runRecorde(long utc) {
        Log.d(TAG, "开始录音...");

        if (onWaveDataListener != null) {
            onWaveDataListener.getVoiceData(FT8Common.FT8_SLOT_TIME_MILLISECOND, true
                    , new OnGetVoiceDataDone() {
                        @Override
                        public void onGetDone(float[] data) {
                            Log.d(TAG, String.format("开始解码...###,数据长度：%d", data.length));
                            decodeFt8(utc, data);
                        }
                    });
        }
    }

    /**
     * 解码。把整时隙音频交给新解码库一次性解码。
     *
     * <p>新库内部完成多遍信号消除（SIC）与 OSD 兜底，因此这里只回调一次 afterDecode
     * （isDeep 恒为 false）；原先由 Java 驱动的多遍消除已移除。
     *
     * @param utc       当前解码的 UTC 时间
     * @param voiceData 整时隙音频（12kHz 单声道，约 180000 个样本）
     */
    public void decodeFt8(long utc, float[] voiceData) {

        new Thread(new Runnable() {
            @Override
            public void run() {
                long time = System.currentTimeMillis();
                if (onFt8Listen != null) {
                    onFt8Listen.beforeListen(utc);
                }

                ArrayList<Ft8Message> msgs = new ArrayList<>();
                try {
                    String[] records = Ft8Native.decodeSlot(voiceData, buildDecodeConfig()
                            , null, null, null);
                    if (records != null) {
                        for (String record : records) {
                            Ft8Message msg = convertMessage(utc, Ft8Native.parse(record));
                            if (msg != null) {
                                msgs.add(msg);
                            }
                        }
                    }
                } catch (Throwable e) {
                    Log.e(TAG, "新解码库调用失败：" + e.getMessage());
                }

                timeSec = System.currentTimeMillis() - time;
                decodeTimeSec.postValue(timeSec);//解码耗时
                if (onFt8Listen != null) {
                    onFt8Listen.afterDecode(utc, averageOffset(msgs)
                            , UtcTimer.sequential(utc), msgs, false);
                }
                Log.d(TAG, String.format("解码耗时:%d毫秒，共 %d 条", timeSec, msgs.size()));
            }
        }).start();
    }

    /**
     * 依据 FT8CN 的全局设置构造新解码库的解码配置数组。
     *
     * <p>深度解码模式开启多遍信号消除（SIC）；OSD 兜底固定开启（阶数 2）；
     * 解码线程数按设备 CPU 核数匹配，上限 4，兼顾速度与功耗。
     */
    private float[] buildDecodeConfig() {
        float[] cfg = new float[Ft8Native.DC_COUNT];
        cfg[Ft8Native.DC_FMIN] = 200f;
        cfg[Ft8Native.DC_FMAX] = 3000f;
        cfg[Ft8Native.DC_SAMPLE_RATE] = FT8Common.SAMPLE_RATE;
        cfg[Ft8Native.DC_TIME_OSR] = 2;
        cfg[Ft8Native.DC_FREQ_OSR] = 2;
        cfg[Ft8Native.DC_MAX_CANDIDATES] = 140;
        cfg[Ft8Native.DC_MIN_SYNC_SCORE] = 10;
        cfg[Ft8Native.DC_LDPC_ITERATIONS] = 25;
        cfg[Ft8Native.DC_DECODE_DEPTH] = GeneralVariables.deepDecodeMode ? 3 : 0;
        cfg[Ft8Native.DC_AP_MODE] = 0;
        cfg[Ft8Native.DC_ENABLE_SUBTRACT] = GeneralVariables.deepDecodeMode ? 1f : 0f;
        cfg[Ft8Native.DC_NUM_THREADS] = Math.min(Runtime.getRuntime().availableProcessors(), 4);
        cfg[Ft8Native.DC_RETURN_DUPLICATES] = 0f;
        cfg[Ft8Native.DC_OSD_DEPTH] = 2;
        cfg[Ft8Native.DC_ENABLE_LLR_REFINE] = 0f;
        return cfg;
    }

    /**
     * 把新解码库的结果转换成 FT8CN 的 Ft8Message。
     *
     * @param utc 当前解码的 UTC 时间
     * @param r   新库的单条结果
     */
    private Ft8Message convertMessage(long utc, Ft8Native.Message r) {
        Ft8Message msg = new Ft8Message(FT8Common.FT8_MODE);
        msg.utcTime = utc;
        msg.band = GeneralVariables.band;
        msg.isValid = true;
        msg.snr = Math.round(r.snr);
        msg.time_sec = r.dt;
        msg.freq_hz = r.freq;
        msg.score = r.score;
        msg.messageHash = r.crc;
        msg.i3 = r.i3;
        msg.n3 = r.n3;
        msg.isWeakSignal = r.pass > 0;//多遍消除解出的记为弱信号

        //保存原始解码文本，供界面直接显示（见 Ft8Message.getMessageText）
        msg.decodedText = r.text;

        //呼号与扩展信息：字段缺失时置空串，避免上层空指针
        msg.callsignTo = (r.callTo == null) ? "" : r.callTo;
        msg.callsignFrom = (r.callDe == null) ? "" : r.callDe;
        if (r.i3 == 0 && r.n3 == 0) {
            //自由文本：整条文本放在 extraInfo
            msg.extraInfo = (r.text == null) ? "" : r.text;
        } else {
            msg.extraInfo = (r.extra == null) ? "" : r.extra;
        }
        msg.maidenGrid = (r.grid == null || r.grid.length() == 0) ? null : r.grid;
        msg.report = parseReport(msg.extraInfo);

        //非标准呼号：沿用原库的哈希函数登记呼号，保持历史行为
        if (r.i3 == 4 && msg.callsignFrom.length() > 0
                && !msg.callsignFrom.startsWith("<")) {
            Ft8Message.hashList.addHash(FT8Package.getHash22(msg.callsignFrom), msg.callsignFrom);
            Ft8Message.hashList.addHash(FT8Package.getHash12(msg.callsignFrom), msg.callsignFrom);
            Ft8Message.hashList.addHash(FT8Package.getHash10(msg.callsignFrom), msg.callsignFrom);
        }
        return msg;
    }

    /**
     * 从扩展信息中解析信号报告，非报告时返回 -100（与 Ft8Message 的约定一致）。
     *
     * @param extra 扩展信息，如 "-10"、"R-10"、"73"、"OL50"
     */
    private int parseReport(String extra) {
        if (extra == null) return -100;
        String s = extra.trim();
        if (s.length() > 0 && (s.charAt(0) == 'R' || s.charAt(0) == 'r')) {
            s = s.substring(1).trim();
        }
        if (s.matches("^[+-]?\\d{1,2}$")) {
            try {
                return Integer.parseInt(s);
            } catch (Exception e) {
                return -100;
            }
        }
        return -100;
    }

    /**
     * 计算平均时间偏移值
     *
     * @param messages 消息列表
     * @return 偏移值
     */
    private float averageOffset(ArrayList<Ft8Message> messages) {
        if (messages.size() == 0) return 0f;
        float dt = 0;
        for (Ft8Message msg : messages) {
            dt += msg.time_sec;
        }
        return dt / messages.size();
    }

    public OnWaveDataListener getOnWaveDataListener() {
        return onWaveDataListener;
    }

    public void setOnWaveDataListener(OnWaveDataListener onWaveDataListener) {
        this.onWaveDataListener = onWaveDataListener;
    }


    public String getCacheFileName(String fileName) {
        return GeneralVariables.getMainContext().getCacheDir() + "/" + fileName;
    }
}
