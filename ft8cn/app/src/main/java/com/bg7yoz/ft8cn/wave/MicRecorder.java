package com.bg7yoz.ft8cn.wave;
/**
 * 使用Mic录音的操作。
 * @author BGY70Z
 * @date 2023-03-20
 */

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import com.bg7yoz.ft8cn.GeneralVariables;
import com.bg7yoz.ft8cn.R;
import com.bg7yoz.ft8cn.ui.ToastMessage;

public class MicRecorder {
    private static final String TAG = "MicRecorder";
    private int bufferSize = 0;//最小缓冲区大小
    private static final int sampleRateInHz = 12000;//采样率
    private static final int channelConfig = AudioFormat.CHANNEL_IN_MONO; //单声道
    //private static final int audioFormat = AudioFormat.ENCODING_PCM_16BIT; //量化位数
    private static final int audioFormat = AudioFormat.ENCODING_PCM_FLOAT; //量化位数

    private AudioRecord audioRecord = null;//AudioRecord对象
    private boolean isRunning = false;//是否处于录音的状态。
    private Thread recordThread = null;//录音的读取线程，用于停止时等待其释放资源
    private OnDataListener onDataListener;
    private final int audioSource;//录音的音源（MediaRecorder.AudioSource）

    public interface OnDataListener{
        void onDataReceived(float[] data,int len);
    }

    /**
     * 构建录音对象
     *
     * @param audioSource 录音的音源，取值见 {@link AudioInputSource}
     */
    @SuppressLint("MissingPermission")
    public MicRecorder(int audioSource){
        this.audioSource = audioSource;
        //计算最小缓冲区
        bufferSize = AudioRecord.getMinBufferSize(sampleRateInHz, channelConfig, audioFormat);
        if (bufferSize <= 0) {
            //个别设备可能返回非法值，给一个兜底大小，避免缓冲区创建失败
            bufferSize = sampleRateInHz;
        }
        audioRecord = createAudioRecord(audioSource);
    }

    /**
     * 创建AudioRecord对象。如果指定的音源不被设备支持，则依次回退到默认音源、
     * 麦克风音源，尽量避免因音源不兼容导致完全无法录音。
     *
     * @param source 期望使用的音源
     * @return 创建成功的AudioRecord，全部失败时返回null
     */
    @SuppressLint("MissingPermission")
    private AudioRecord createAudioRecord(int source) {
        int[] fallbackSources = {source, AudioInputSource.DEFAULT, AudioInputSource.MIC};
        for (int i = 0; i < fallbackSources.length; i++) {
            try {
                AudioRecord record = new AudioRecord(fallbackSources[i], sampleRateInHz
                        , channelConfig, audioFormat, bufferSize);
                if (record.getState() == AudioRecord.STATE_INITIALIZED) {
                    if (fallbackSources[i] != source) {
                        Log.w(TAG, String.format("音源%d不被支持，已回退到音源%d", source, fallbackSources[i]));
                    }
                    return record;
                }
                //状态不是初始化完成，释放后尝试下一个
                record.release();
            } catch (Exception e) {
                Log.d(TAG, String.format("创建AudioRecord失败，音源：%d，%s", fallbackSources[i], e.getMessage()));
            }
        }
        return null;
    }

    public void start(){
        if (isRunning) return;

        //上一次录音结束后AudioRecord已被释放，这里重新创建
        if (audioRecord == null || audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            audioRecord = createAudioRecord(audioSource);
        }
        if (audioRecord == null) {
            ToastMessage.show(GeneralVariables.getStringFromResource(R.string.recorder_create_error));
            Log.d(TAG, "start: 创建AudioRecord失败，无法录音");
            return;
        }

        final float[] buffer = new float[bufferSize];
        try {
            audioRecord.startRecording();//开始录音
        }catch (Exception e){
            ToastMessage.show(String.format(GeneralVariables.getStringFromResource(
                    R.string.recorder_cannot_record),e.getMessage()));
            Log.d(TAG, "startRecord: "+e.getMessage() );
        }

        isRunning = true;

        recordThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (isRunning) {
                    //判断是否处于录音状态，state!=3，说明没有处于录音的状态
                    if (audioRecord == null
                            || audioRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                        isRunning = false;
                        Log.d(TAG, String.format("录音失败，状态码：%d"
                                , audioRecord == null ? -1 : audioRecord.getRecordingState()));
                        break;
                    }

                    //读录音的数据
                    int bufferReadResult = audioRecord.read(buffer, 0, bufferSize,AudioRecord.READ_BLOCKING);

                    if (bufferReadResult > 0 && onDataListener!=null){
                        onDataListener.onDataReceived(buffer,bufferReadResult);
                    }
                }
                releaseAudioRecord();//退出读取循环后释放录音资源
            }
        });
        recordThread.start();
    }

    /**
     * 释放AudioRecord资源。录音停止后必须释放，否则重新创建录音对象时
     * 可能因为上一实例仍占用音频输入而创建失败。
     */
    private void releaseAudioRecord() {
        try {
            if (audioRecord != null) {
                if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop();//停止录音
                }
                audioRecord.release();
            }
        }catch (Exception e){
            ToastMessage.show(String.format(GeneralVariables.getStringFromResource(
                    R.string.recorder_stop_record_error),e.getMessage()));
            Log.d(TAG, "releaseAudioRecord: "+e.getMessage() );
        } finally {
            audioRecord = null;
        }
    }

    /**
     * 停止录音。等待读取线程退出并释放录音资源，保证可以立即用新的音源重新录音。
     */
    public void stopRecord() {
        isRunning = false;
        Thread thread = recordThread;
        if (thread != null && thread != Thread.currentThread()) {
            try {
                thread.join(500);//等待读取线程结束释放资源，最多等待500毫秒
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        recordThread = null;
    }

    public OnDataListener getOnDataListener() {
        return onDataListener;
    }

    public void setOnDataListener(OnDataListener onDataListener) {
        this.onDataListener = onDataListener;
    }
}
