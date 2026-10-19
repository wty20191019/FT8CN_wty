package com.bg7yoz.ft8cn.wave;

import android.media.MediaRecorder;

import com.bg7yoz.ft8cn.R;

/**
 * 录音音源（AudioSource）定义。
 * <p>
 * 部分机型（例如 vivo V2338A）在默认音源（DEFAULT）下无法采集到声音，
 * 需要在设置中选择其它音源（如 MIC、VOICE_RECOGNITION）才能正常录音。
 * 这里集中定义可选的音源及其中文/英文名称资源。
 *
 * @author BG7YOZ
 */
public class AudioInputSource {
    //音源的取值与 MediaRecorder.AudioSource 保持一致，方便存库后直接使用
    public static final int DEFAULT = MediaRecorder.AudioSource.DEFAULT;
    public static final int MIC = MediaRecorder.AudioSource.MIC;
    public static final int VOICE_RECOGNITION = MediaRecorder.AudioSource.VOICE_RECOGNITION;
    public static final int VOICE_COMMUNICATION = MediaRecorder.AudioSource.VOICE_COMMUNICATION;
    public static final int UNPROCESSED = MediaRecorder.AudioSource.UNPROCESSED;
    public static final int CAMCORDER = MediaRecorder.AudioSource.CAMCORDER;

    //可选音源列表（下拉框的显示顺序）
    public static final int[] SOURCE_VALUES = {
            DEFAULT, MIC, VOICE_RECOGNITION, VOICE_COMMUNICATION, UNPROCESSED, CAMCORDER};

    //每个音源对应的名称字符串资源
    public static final int[] SOURCE_NAME_RES = {
            R.string.audio_input_default,
            R.string.audio_input_mic,
            R.string.audio_input_voice_recognition,
            R.string.audio_input_voice_communication,
            R.string.audio_input_unprocessed,
            R.string.audio_input_camcorder};

    /**
     * 音源在列表中的个数
     */
    public static int getCount() {
        return SOURCE_VALUES.length;
    }

    /**
     * 根据音源值获取列表中的下标
     *
     * @param source 音源值
     * @return 列表下标，找不到时返回默认音源（0）
     */
    public static int getIndex(int source) {
        for (int i = 0; i < SOURCE_VALUES.length; i++) {
            if (SOURCE_VALUES[i] == source) return i;
        }
        return 0;
    }

    /**
     * 根据列表下标获取音源值
     *
     * @param index 列表下标
     * @return 音源值，越界时返回默认音源
     */
    public static int getSource(int index) {
        if (index < 0 || index >= SOURCE_VALUES.length) return DEFAULT;
        return SOURCE_VALUES[index];
    }

    /**
     * 根据列表下标获取音源名称的资源ID
     *
     * @param index 列表下标
     * @return 名称字符串资源ID
     */
    public static int getNameRes(int index) {
        if (index < 0 || index >= SOURCE_NAME_RES.length) return SOURCE_NAME_RES[0];
        return SOURCE_NAME_RES[index];
    }
}
