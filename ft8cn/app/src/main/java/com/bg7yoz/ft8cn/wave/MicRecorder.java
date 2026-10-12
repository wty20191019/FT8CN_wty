package com.bg7yoz.ft8cn.wave;
/**
 * 使用Mic录音的操作。
 *
 * <p>M2：采集配置能力探测 + 自动降级。
 * 部分厂商 ROM（如 vivo V2338A）虽然能接受 {@code ENCODING_PCM_FLOAT} 并进入录音态，
 * 但实际返回的是错位字节（样本峰值可达 1e35），导致后续频谱/解码拿到的全是垃圾。
 * 因此这里按「兼容性从高到低」依次尝试候选配置，取第一个可初始化者：
 * <pre>
 *   1. 12000Hz + PCM_16BIT   （最通用，首选）
 *   2. 12000Hz + PCM_FLOAT
 *   3. 48000Hz + PCM_16BIT   （采集后重采样回 12kHz）
 *   4. 44100Hz + PCM_16BIT
 * </pre>
 * 对外始终输出「12kHz 单声道 float[]」，因此 {@link HamRecorder} 与解码链路无需改动。
 *
 * @author BGY70Z
 * @date 2023-03-20
 */

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.util.Log;

import com.bg7yoz.ft8cn.GeneralVariables;
import com.bg7yoz.ft8cn.R;
import com.bg7yoz.ft8cn.ui.ToastMessage;

import java.util.Arrays;

public class MicRecorder {
    private static final String TAG = "MicRecorder";

    /** 对外统一的目标采样率。 */
    private static final int TARGET_RATE = 12000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    /** 每次读取的样本数（帧）≈160ms@12kHz。 */
    private static final int READ_SAMPLES = 1920;

    /** 候选采集配置：{采样率, 编码}，按兼容性从高到低。 */
    private static final int[][] CANDIDATES = {
            {12000, AudioFormat.ENCODING_PCM_16BIT},
            {12000, AudioFormat.ENCODING_PCM_FLOAT},
            {48000, AudioFormat.ENCODING_PCM_16BIT},
            {44100, AudioFormat.ENCODING_PCM_16BIT},
    };

    private AudioRecord audioRecord = null;//AudioRecord对象
    private boolean isRunning = false;//是否处于录音的状态。
    private OnDataListener onDataListener;

    private int nativeRate = TARGET_RATE;//实际采集采样率
    private int encoding = AudioFormat.ENCODING_PCM_16BIT;//实际采集编码
    private int minBufferBytes = 0;//AudioRecord 内部缓冲（字节）

    //读取缓冲（按实际编码复用）
    private final short[] shortBuffer = new short[READ_SAMPLES];
    private final float[] floatBuffer = new float[READ_SAMPLES + 64];//留余量给重采样


    public interface OnDataListener{
        void onDataReceived(float[] data,int len);
    }

    @SuppressLint("MissingPermission")
    public MicRecorder(){
        openBestConfig();
    }

    /**
     * 依次尝试候选采集配置，采用第一个能成功初始化的组合。
     */
    @SuppressLint("MissingPermission")
    private void openBestConfig() {
        //诊断：系统的原生采样率
        try {
            AudioManager am = (AudioManager) GeneralVariables.getMainContext()
                    .getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                Log.e(TAG, "系统原生采样率=" + am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
                        + " framesPerBuffer=" + am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER));
            }
        } catch (Throwable t) {
            Log.e(TAG, "查询系统音频属性失败：" + t.getMessage());
        }

        for (int[] c : CANDIDATES) {
            int rate = c[0];
            int enc = c[1];
            int minBuf = AudioRecord.getMinBufferSize(rate, CHANNEL_CONFIG, enc);
            Log.e(TAG, String.format("尝试 rate=%d enc=%d -> getMinBufferSize=%d", rate, enc, minBuf));
            if (minBuf <= 0) {
                continue;//该"采样率/编码"组合不被支持
            }

            AudioRecord rec;
            try {
                rec = new AudioRecord(MediaRecorder.AudioSource.DEFAULT, rate
                        , CHANNEL_CONFIG, enc, minBuf);
            } catch (Throwable t) {
                Log.e(TAG, "构造 AudioRecord 异常：" + t.getMessage());
                continue;
            }

            int state = rec.getState();
            if (state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "state=" + state + " 未初始化，放弃该配置");
                rec.release();
                continue;
            }

            audioRecord = rec;
            nativeRate = rate;
            encoding = enc;
            minBufferBytes = minBuf;
            Log.e(TAG, String.format("采用配置：rate=%d enc=%d bufferBytes=%d state=%d",
                    rate, enc, minBuf, state));

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    AudioDeviceInfo dev = rec.getRoutedDevice();
                    Log.e(TAG, "routedDevice=" + (dev == null ? "null"
                            : dev.getProductName() + ", type=" + dev.getType()));
                } catch (Throwable t) {
                    Log.e(TAG, "查询路由设备失败：" + t.getMessage());
                }
            }
            return;
        }

        Log.e(TAG, "所有候选采集配置都不可用，无法录音！");
        ToastMessage.show(GeneralVariables.getStringFromResource(R.string.recorder_cannot_record));
    }

    public void start(){
        if (isRunning) return;
        if (audioRecord == null || audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord 不可用，无法开始录音！");
            ToastMessage.show(GeneralVariables.getStringFromResource(R.string.recorder_cannot_record));
            return;
        }

        try {
            audioRecord.startRecording();//开始录音
        }catch (Exception e){
            ToastMessage.show(String.format(GeneralVariables.getStringFromResource(
                    R.string.recorder_cannot_record),e.getMessage()));
            Log.e(TAG, "startRecording 异常: "+e.getMessage() );
            return;
        }
        Log.e(TAG, "startRecording 已调用，recordingState=" + audioRecord.getRecordingState()
                + " (3=RECORDING)");

        isRunning = true;

        new Thread(new Runnable() {
            @Override
            public void run() {
                int readCount = 0;
                while (isRunning) {
                    //判断是否处于录音状态
                    if (audioRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                        isRunning = false;
                        Log.e(TAG, String.format("录音失败，状态码：%d", audioRecord.getRecordingState()));
                        break;
                    }

                    int frames = readNative();//实际读到的帧数
                    if (frames <= 0) {
                        if (readCount % 50 == 0) {
                            Log.e(TAG, "read 返回异常值：" + frames
                                    + " (负数=错误：-2 BAD_VALUE, -3 INVALID_OPERATION, -6 DEAD_OBJECT)");
                        }
                        readCount++;
                        continue;
                    }

                    //统一成 12kHz 单声道 float[]
                    float[] data = toTargetRateFloat(frames);

                    if (readCount % 50 == 0) {
                        float peak = 0f;
                        for (int i = 0; i < data.length; i++) {
                            float v = Math.abs(data[i]);
                            if (v > peak) peak = v;
                        }
                        Log.e(TAG, String.format("read=%d peak=%.5f rate=%d enc=%d",
                                data.length, peak, nativeRate, encoding));
                    }
                    readCount++;

                    if (onDataListener != null) {
                        onDataListener.onDataReceived(data, data.length);
                    }
                }
                try {
                    if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                        audioRecord.stop();//停止录音
                    }
                }catch (Exception e){
                    ToastMessage.show(String.format(GeneralVariables.getStringFromResource(
                            R.string.recorder_stop_record_error),e.getMessage()));
                    Log.e(TAG, "stop 异常: "+e.getMessage() );
                }
            }
        }).start();
    }

    /** 按实际编码读取一次音频，返回读到的帧数。 */
    private int readNative() {
        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
            return audioRecord.read(floatBuffer, 0, READ_SAMPLES, AudioRecord.READ_BLOCKING);
        } else {
            return audioRecord.read(shortBuffer, 0, READ_SAMPLES, AudioRecord.READ_BLOCKING);
        }
    }

    /**
     * 把刚读取的数据统一成「12kHz 单声道 float[]」。
     * 16 位整数按 1/32768 归一化；采样率不是 12kHz 时用 FT8Resample 重采样。
     */
    private float[] toTargetRateFloat(int frames) {
        int len = Math.min(frames, floatBuffer.length);

        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
            //浮点采样：钳位异常设备可能产生的越界值
            for (int i = 0; i < len; i++) {
                float v = floatBuffer[i];
                if (Float.isNaN(v) || v > 1f) v = 1f;
                else if (v < -1f) v = -1f;
                floatBuffer[i] = v;
            }
        } else {
            //16 位整数采样：归一化到 [-1,1]
            for (int i = 0; i < len; i++) {
                floatBuffer[i] = shortBuffer[i] / 32768.0f;
            }
        }

        //返回独立数组，避免上层拿到被下一次读取覆盖的复用缓冲区
        float[] samples = Arrays.copyOf(floatBuffer, len);
        if (nativeRate == TARGET_RATE) {
            return samples;
        }
        return FT8Resample.get32Resample32(samples, nativeRate, TARGET_RATE, 1);
    }

    /**
     * 停止录音。当录音停止后，监听列表中的监听器全部删除。
     */
    public void stopRecord() {
        isRunning = false;
    }

    public OnDataListener getOnDataListener() {
        return onDataListener;
    }

    public void setOnDataListener(OnDataListener onDataListener) {
        this.onDataListener = onDataListener;
    }
}
