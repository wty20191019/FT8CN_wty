package com.bg7yoz.ft8cn.ui;
/**
 * 录音音源列表。
 * @author BG7YOZ
 */

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import com.bg7yoz.ft8cn.GeneralVariables;
import com.bg7yoz.ft8cn.R;
import com.bg7yoz.ft8cn.wave.AudioInputSource;

public class AudioInputSpinnerAdapter extends BaseAdapter {
    private final Context mContext;

    public AudioInputSpinnerAdapter(Context context) {
        mContext = context;
    }

    @Override
    public int getCount() {
        return AudioInputSource.getCount();
    }

    @Override
    public Object getItem(int i) {
        return AudioInputSource.getSource(i);
    }

    @Override
    public long getItemId(int i) {
        return i;
    }

    /**
     * 获取指定下标的音源值
     */
    public int getSource(int i) {
        return AudioInputSource.getSource(i);
    }

    /**
     * 获取指定音源在列表中的下标
     */
    public int getPosition(int source) {
        return AudioInputSource.getIndex(source);
    }

    @SuppressLint({"DefaultLocale", "ViewHolder", "InflateParams"})
    @Override
    public View getView(int i, View view, ViewGroup viewGroup) {
        LayoutInflater _LayoutInflater = LayoutInflater.from(mContext);
        view = _LayoutInflater.inflate(R.layout.audio_input_spinner_item, null);
        if (view != null) {
            TextView textView = view.findViewById(R.id.audioInputItemTextView);
            textView.setText(GeneralVariables.getStringFromResource(AudioInputSource.getNameRes(i)));
        }
        return view;
    }
}
