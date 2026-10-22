package com.bg7yoz.ft8cn.ft8listener;
/**
 * 用于监听音频的类。监听通过时钟UtcTimer来控制周期，通过OnWaveDataListener接口来读取音频数据。
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
import com.bg7yoz.ft8cn.ft8transmit.GenerateFT8;
import com.bg7yoz.ft8cn.timer.OnUtcTimer;
import com.bg7yoz.ft8cn.timer.UtcTimer;
import com.bg7yoz.ft8cn.wave.OnGetVoiceDataDone;
import com.bg7yoz.ft8cn.wave.WaveFileReader;
import com.bg7yoz.ft8cn.wave.WaveFileWriter;
import com.bg7yoz.ft8cn.wsjtx.WsjtxDecoder;

import java.util.ArrayList;

public class FT8SignalListener {
    private static final String TAG = "FT8SignalListener";
    private final UtcTimer utcTimer;
    //private HamRecorder hamRecorder;
    private final OnFt8Listen onFt8Listen;//当开始监听，解码结束后触发的事件
    //private long band;
    public MutableLiveData<Long> decodeTimeSec = new MutableLiveData<>();//解码的时长
    public long timeSec=0;//解码的时长

    private OnWaveDataListener onWaveDataListener;


    private DatabaseOpr db;

    private final A91List a91List = new A91List();//a91列表

    //wsjtx解码库是否可用。armeabi-v7a/x86没有对应的so，第一次失败后不再尝试
    private volatile boolean wsjtxAvailable = true;


    static {
        System.loadLibrary("ft8cn");
    }

    public interface OnWaveDataListener {
        void getVoiceData(int duration, boolean afterDoneRemove, OnGetVoiceDataDone getVoiceDataDone);
    }

    public FT8SignalListener(DatabaseOpr db, OnFt8Listen onFt8Listen) {
        //this.hamRecorder = hamRecorder;
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
                            Log.d(TAG, String.format("开始解码...###,数据长度：%d",data.length));
                            decodeFt8(utc, data);
                        }
                    });
        }
    }

    public void decodeFt8(long utc, float[] voiceData) {

        //此处是测试用代码-------------------------
//        String fileName = getCacheFileName("test_01.wav");
//        Log.e(TAG, "onClick: fileName:" + fileName);
//        WaveFileReader reader = new WaveFileReader(fileName);
//        int data[][] = reader.getData();
        //----------------------------------------------------------

        new Thread(new Runnable() {
            @Override
            public void run() {
                long time = System.currentTimeMillis();
                if (onFt8Listen != null) {
                    onFt8Listen.beforeListen(utc);
                }

                int mode = GeneralVariables.decodeMode;
                boolean handled = false;

                //wsjtx挡位：使用mfsk-core（WSJT-X算法）解码
                if (GeneralVariables.isWsjtxDecodeMode() && wsjtxAvailable) {
                    handled = decodeByWsjtx(utc, voiceData
                            , mode == GeneralVariables.DECODE_MODE_WSJTX_DEEP, time);
                    if (!handled) {
                        //当前设备ABI没有对应so（如armeabi-v7a/x86），后续直接回退
                        wsjtxAvailable = false;
                    }
                }

                if (!handled) {
                    //wsjtx普通回退到快速解码，wsjtx深度回退到多次解码
                    boolean deep = mode == GeneralVariables.DECODE_MODE_DEEP
                            || mode == GeneralVariables.DECODE_MODE_WSJTX_DEEP;
                    decodeByLegacy(utc, voiceData, deep, time);
                }

                Log.d(TAG, String.format("解码耗时:%d毫秒", System.currentTimeMillis() - time));
            }
        }).start();
    }

    /**
     * 使用mfsk-core（WSJT-X算法）解码一个时隙。
     *
     * @param utc       当前时隙的UTC时间
     * @param voiceData 12kHz、float音频
     * @param deep      true=BP_ALL_OSD + DEEP门限
     * @param startTime 本轮解码的开始时间
     * @return true=解码流程完成（包括没有解出消息）；false=库不可用或出错，需要回退
     */
    private boolean decodeByWsjtx(long utc, float[] voiceData, boolean deep, long startTime) {
        try {
            ArrayList<Ft8Message> allMsg = new ArrayList<>();
            WsjtxDecoder.Row[] rows = WsjtxDecoder.decodeOnce(
                    voiceData, FT8Common.SAMPLE_RATE, deep);
            ArrayList<Ft8Message> msgs = toFt8Messages(rows, utc, deep);
            addMsgToList(allMsg, msgs);
            timeSec = System.currentTimeMillis() - startTime;
            decodeTimeSec.postValue(timeSec);//解码耗时
            if (onFt8Listen != null) {
                //wsjtx一个时隙只解码一次，回调按普通解码处理，
                //保证自动通联逻辑对wsjtx深度的结果也生效
                onFt8Listen.afterDecode(utc, averageOffset(allMsg)
                        , UtcTimer.sequential(utc), msgs, false);
            }
            Log.d(TAG, String.format("wsjtx解码完成，共%d条", allMsg.size()));
            return true;
        } catch (Throwable e) {
            //UnsatisfiedLinkError、ExceptionInInitializerError等都从这里回退
            Log.e(TAG, "wsjtx解码不可用，回退到原解码器：" + e.getMessage(), e);
            return false;
        }
    }

    /**
     * 原来的FT8CN解码流程（快速/多次解码）。
     *
     * @param utc       当前时隙的UTC时间
     * @param voiceData 12kHz、float音频
     * @param deep      true=多次解码
     * @param startTime 本轮解码的开始时间
     */
    private void decodeByLegacy(long utc, float[] voiceData, boolean deep, long startTime) {
        ///读入音频数据，并做预处理
        //其实这种方式要注意一个问题，在一个周期之内，必须解码完毕，否则新的解码又要开始了
        long ft8Decoder = InitDecoder(utc, FT8Common.SAMPLE_RATE
                , voiceData.length, true);
        DecoderMonitorPressFloat(voiceData, ft8Decoder);//读入音频数据

        ArrayList<Ft8Message> allMsg = new ArrayList<>();
        ArrayList<Ft8Message> msgs = runDecode(ft8Decoder, utc, false);
        addMsgToList(allMsg, msgs);
        timeSec = System.currentTimeMillis() - startTime;
        decodeTimeSec.postValue(timeSec);//解码耗时
        if (onFt8Listen != null) {
            onFt8Listen.afterDecode(utc, averageOffset(allMsg), UtcTimer.sequential(utc), msgs, false);
        }

        if (deep) {//进入多次解码模式
            msgs = runDecode(ft8Decoder, utc, true);
            addMsgToList(allMsg, msgs);
            timeSec = System.currentTimeMillis() - startTime;
            decodeTimeSec.postValue(timeSec);//解码耗时
            if (onFt8Listen != null) {
                onFt8Listen.afterDecode(utc, averageOffset(allMsg), UtcTimer.sequential(utc), msgs, true);
            }

            do {
                if (timeSec > FT8Common.DEEP_DECODE_TIMEOUT) break;//此处做超时检测，超过一定时间(7秒)，就不做减码操作了
                //减去解码的信号
                ReBuildSignal.subtractSignal(ft8Decoder, a91List);

                //再做一次解码
                msgs = runDecode(ft8Decoder, utc, true);
                addMsgToList(allMsg, msgs);
                timeSec = System.currentTimeMillis() - startTime;
                decodeTimeSec.postValue(timeSec);//解码耗时
                if (onFt8Listen != null) {
                    onFt8Listen.afterDecode(utc, averageOffset(allMsg), UtcTimer.sequential(utc), msgs, true);
                }

            } while (msgs.size() > 0);

        }
        //移到finalize() 方法中调用了
        DeleteDecoder(ft8Decoder);
    }


    private ArrayList<Ft8Message> runDecode(long ft8Decoder, long utc, boolean isDeep) {
        ArrayList<Ft8Message> ft8Messages = new ArrayList<>();
        Ft8Message ft8Message = new Ft8Message(FT8Common.FT8_MODE);

        ft8Message.utcTime = utc;
        ft8Message.band = GeneralVariables.band;
        a91List.clear();

        setDecodeMode(ft8Decoder, isDeep);//设置迭代次数,isDeep==true，迭代次数增加

        int num_candidates = DecoderFt8FindSync(ft8Decoder);//最多120个
        //long startTime = System.currentTimeMillis();
        for (int idx = 0; idx < num_candidates; ++idx) {
            //todo 应当做一下超时计算
            try {//做一下解码失败保护
                if (DecoderFt8Analysis(idx, ft8Decoder, ft8Message)) {

                    if (ft8Message.isValid) {
                        Ft8Message msg = new Ft8Message(ft8Message);//此处使用msg，是因为有的哈希呼号会把<...>替换掉
                        byte[] a91 = DecoderGetA91(ft8Decoder);
                        a91List.add(a91, ft8Message.freq_hz, ft8Message.time_sec);

                        if (checkMessageSame(ft8Messages, msg)) {
                            continue;
                        }

                        msg.isWeakSignal = isDeep;//是不是弱信号
                        ft8Messages.add(msg);

                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "run: " + e.getMessage());
            }

        }


        return ft8Messages;
    }

    /**
     * 把mfsk-core的解码行转换成Ft8Message列表。
     *
     * @param rows   解码行
     * @param utc    当前时隙的UTC时间
     * @param isDeep 是否深度解码
     * @return Ft8Message列表
     */
    private ArrayList<Ft8Message> toFt8Messages(WsjtxDecoder.Row[] rows,
                                                long utc, boolean isDeep) {
        ArrayList<Ft8Message> out = new ArrayList<>();
        if (rows == null) return out;

        for (WsjtxDecoder.Row row : rows) {
            Ft8Message msg = toFt8Message(row, utc, isDeep);
            if (msg != null) out.add(msg);
        }
        return out;
    }

    /**
     * 把一条mfsk-core解码行转换成Ft8Message。
     *
     * @param row    解码行
     * @param utc    当前时隙的UTC时间
     * @param isDeep 是否深度解码
     * @return Ft8Message，无法转换时返回null
     */
    private Ft8Message toFt8Message(WsjtxDecoder.Row row, long utc, boolean isDeep) {
        if (row == null) return null;

        String text = row.text == null ? "" : row.text.trim();
        if (text.isEmpty()) return null;

        Ft8Message msg = new Ft8Message(FT8Common.FT8_MODE);
        msg.utcTime = utc;
        msg.isValid = true;
        msg.snr = Math.round(row.snr);
        msg.time_sec = row.dt;
        msg.freq_hz = row.freq;
        msg.band = GeneralVariables.band;
        msg.signalFormat = FT8Common.FT8_MODE;
        msg.isWeakSignal = isDeep;

        parseWsjtxText(msg, text);
        parseWsjtxExtra(msg);
        return msg;
    }

    /**
     * 解析mfsk-core返回的可读报文，尽量还原callsignTo/callsignFrom/extraInfo，
     * 保证getMessageText()显示的内容与原报文一致。
     *
     * @param msg  Ft8Message
     * @param text 可读报文
     */
    private void parseWsjtxText(Ft8Message msg, String text) {
        String[] tokens = text.split("\\s+");
        msg.callsignTo = "";
        msg.callsignFrom = "";
        msg.extraInfo = text;
        msg.i3 = 0;
        msg.n3 = 0;

        if (tokens.length >= 2 && isCqPrefix(tokens[0])) {
            //CQ [修饰符] 呼号 [附加信息]，例如：CQ POTA BG7YOZ OL50
            msg.callsignTo = tokens[0];
            if (tokens.length >= 3 && looksLikeCallsign(tokens[1])
                    && looksLikeCallsign(tokens[2])) {
                msg.modifier = tokens[1];
                msg.callsignFrom = tokens[2];
                msg.extraInfo = joinTokens(tokens, 3);
            } else if (looksLikeCallsign(tokens[1])) {
                msg.callsignFrom = tokens[1];
                msg.extraInfo = joinTokens(tokens, 2);
            } else {
                return;//不认识的内容按自由文本显示
            }
            msg.i3 = isNonStandardCallsign(msg.callsignFrom) ? 4 : 1;
            msg.n3 = 0;
            return;
        }

        if (tokens.length >= 2 && looksLikeCallsign(tokens[0])
                && looksLikeCallsign(tokens[1])) {
            //标准通联报文，例如：JA1ABC DL1ABC -12
            msg.callsignTo = tokens[0];
            msg.callsignFrom = tokens[1];
            msg.extraInfo = joinTokens(tokens, 2);
            msg.i3 = (isNonStandardCallsign(tokens[0])
                    || isNonStandardCallsign(tokens[1])) ? 4 : 1;
            msg.n3 = 0;
            return;
        }

        //自由文本：FT8自由文本最多13个字符；更长的内容（DXpedition、
        //Field Day等特殊报文）放进i3=1的附加信息，避免显示时被截断。
        if (text.length() > 13) {
            msg.i3 = 1;
            msg.n3 = 0;
        }
    }

    /**
     * 从附加信息中提取信号报告和梅登海德网格。
     *
     * @param msg Ft8Message
     */
    private void parseWsjtxExtra(Ft8Message msg) {
        if (msg.extraInfo == null || msg.extraInfo.isEmpty()) return;

        String[] tokens = msg.extraInfo.split("\\s+");
        for (String token : tokens) {
            String s = token.toUpperCase();
            //FT8的信号报告一定带正负号，避免把73、RR73当成报告
            if (msg.report == -100 && s.matches("R?[+-]\\d{1,2}")) {
                try {
                    msg.report = Integer.parseInt(s.startsWith("R")
                            ? s.substring(1) : s);
                } catch (NumberFormatException ignored) {
                }
            } else if (msg.maidenGrid == null
                    && s.matches("[A-R]{2}[0-9]{2}([A-X]{2})?")) {
                msg.maidenGrid = s;
            }
        }
    }

    private boolean isCqPrefix(String token) {
        return "CQ".equals(token) || "DE".equals(token) || "QRZ".equals(token);
    }

    private boolean looksLikeCallsign(String token) {
        if (token == null || token.isEmpty() || token.length() > 13) return false;

        boolean hasLetter = false;
        for (int i = 0; i < token.length(); i++) {
            char c = Character.toUpperCase(token.charAt(i));
            if (c >= 'A' && c <= 'Z') {
                hasLetter = true;
            } else if (!(c >= '0' && c <= '9')
                    && c != '/' && c != '<' && c != '>') {
                return false;
            }
        }
        return hasLetter;
    }

    private boolean isNonStandardCallsign(String callsign) {
        return callsign != null && (callsign.indexOf('/') >= 0
                || callsign.indexOf('<') >= 0 || callsign.indexOf('>') >= 0);
    }

    private String joinTokens(String[] tokens, int from) {
        if (tokens.length <= from) return "";

        StringBuilder sb = new StringBuilder();
        for (int i = from; i < tokens.length; i++) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(tokens[i]);
        }
        return sb.toString();
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
        //int dtAverage = 0;
        for (Ft8Message msg : messages) {
            dt += msg.time_sec;
        }
        return dt / messages.size();
    }

    /**
     * 把消息添加到列表中
     *
     * @param allMsg 消息列表
     * @param newMsg 新的消息
     */
    private void addMsgToList(ArrayList<Ft8Message> allMsg, ArrayList<Ft8Message> newMsg) {
        for (int i = newMsg.size() - 1; i >= 0; i--) {
            if (checkMessageSame(allMsg, newMsg.get(i))) {
                newMsg.remove(i);
            } else {
                allMsg.add(newMsg.get(i));
            }
        }
    }

    /**
     * 检查消息列表里同样的内容是否存在
     *
     * @param ft8Messages 消息列表
     * @param ft8Message  消息
     * @return boolean
     */
    private boolean checkMessageSame(ArrayList<Ft8Message> ft8Messages, Ft8Message ft8Message) {
        for (Ft8Message msg : ft8Messages) {
            if (msg.getMessageText().equals(ft8Message.getMessageText())) {
                if (msg.snr < ft8Message.snr) {
                    msg.snr = ft8Message.snr;
                }
                return true;
            }
        }
        return false;
    }

    @Override
    protected void finalize() throws Throwable {
        //DeleteDecoder(ft8Decoder);
        super.finalize();
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

    public float[] ints2floats(int data[][]) {
        float temp[] = new float[data[0].length];
        for (int i = 0; i < data[0].length; i++) {
            temp[i] = data[0][i] / 32768.0f;
        }
        return temp;
    }

    public int[] floats2ints(float data[]) {
        int temp[] = new int[data.length];
        for (int i = 0; i < data.length; i++) {
            temp[i] = (int) (data[i] * 32767.0f);
        }
        return temp;
    }

    /**
     * 解码的第一步，初始化解码器，获取解码器的地址。
     *
     * @param utcTime     UTC时间
     * @param sampleRat   采样率，12000
     * @param num_samples 缓冲区数据的长度
     * @param isFt8       是否是FT8信号
     * @return 返回解码器的地址
     */
    public native long InitDecoder(long utcTime, int sampleRat, int num_samples, boolean isFt8);

    /**
     * 解码的第二步，读取Wav数据。
     *
     * @param buffer  Wav数据缓冲区
     * @param decoder 解码器数据的地址
     */
    public native void DecoderMonitorPress(int[] buffer, long decoder);

    public native void DecoderMonitorPressFloat(float[] buffer, long decoder);


    /**
     * 解码的第三步，同步数据。
     *
     * @param decoder 解码器地址
     * @return 中标信号的数量
     */
    public native int DecoderFt8FindSync(long decoder);

    /**
     * 解码的第四步，分析出消息。（需要在一个循环里）
     *
     * @param idx        中标信号的序号
     * @param decoder    解码器的地址
     * @param ft8Message 解出来的消息
     * @return boolean
     */
    public native boolean DecoderFt8Analysis(int idx, long decoder, Ft8Message ft8Message);

    /**
     * 解码的最后一步，删除解码器数据
     *
     * @param decoder 解码器数据的地址
     */
    public native void DeleteDecoder(long decoder);

    public native void DecoderFt8Reset(long decoder, long utcTime, int num_samples);

    public native byte[] DecoderGetA91(long decoder);//获取当前message的a91数据

    public native void setDecodeMode(long decoder, boolean isDeep);//设置解码的模式，isDeep=true是多次迭代，=false是快速迭代
}
