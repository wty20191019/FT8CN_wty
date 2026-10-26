package com.bg7yoz.ft8cn.ui;
/**
 * 瀑布图自定义控件。
 *
 * @author BGY70Z
 * @date 2023-03-20
 */

import static android.graphics.Bitmap.Config.ARGB_8888;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bg7yoz.ft8cn.Ft8Message;
import com.bg7yoz.ft8cn.GeneralVariables;
import com.bg7yoz.ft8cn.timer.UtcTimer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;

public class WaterfallView extends View {
    private int blockHeight = 2;//色块高度
    private float freq_width = 1;//频率的宽度
    private final int cycle = 2;
    private final int symbols = 93;
    private int lastSequential = 0;
    private Bitmap lastBitMap = null;
    private Canvas _canvas;
    private final Paint linePaint = new Paint();
    private Paint touchPaint = new Paint();
    private final Paint fontPaint = new Paint();
    private final Paint messagePaint = new Paint();
    private final Paint textLinePaint = new Paint();
//    private final Paint messagePaintBack = new Paint();//消息背景
    private final Paint utcPaint = new Paint();
    Paint linearPaint = new Paint();
    private final Paint utcPainBack = new Paint();
    private float pathStart = 0;
    private float pathEnd = 0;

    private int touch_x = -1;
    private int freq_hz = -1;
    private boolean drawMessage = false;//是否画消息内容

    //自动动态范围：每帧强度直方图与平滑后的范围
    private final int[] dynHist = new int[256];
    private float dynLow = 0f;
    private float dynHigh = 255f;
    private boolean dynReady = false;

    public WaterfallView(Context context) {
        super(context);
    }

    public WaterfallView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public WaterfallView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }
    ArrayList<Ft8Message> messages= new ArrayList<>();


    /**
     * 把dp值转换为像素点
     *
     * @param dp dp值
     * @return 像素点
     */
    private int dpToPixel(int dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp
                , getResources().getDisplayMetrics());
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        setClickable(true);
        blockHeight = getHeight() / (symbols * cycle);
        freq_width = (float) getWidth() / 3000f;
        if (lastBitMap != null && !lastBitMap.isRecycled()) {
            lastBitMap.recycle();
        }
        lastBitMap = Bitmap.createBitmap(w, h, ARGB_8888);
        _canvas = new Canvas(lastBitMap);
        Paint blackPaint = new Paint();
        blackPaint.setColor(0xFF000000);
        _canvas.drawRect(0, 0, w, h, blackPaint);//先把背景画黑，防止文字重叠

        //linePaint = new Paint();
        linePaint.setColor(0xff990000);
        touchPaint = new Paint();
        touchPaint.setColor(0xff00ffff);
        touchPaint.setStrokeWidth(getResources().getDisplayMetrics().density);


        //fontPaint = new Paint();
        fontPaint.setTextSize(dpToPixel(10));
        fontPaint.setColor(0xff00ffff);
        fontPaint.setAntiAlias(true);
        fontPaint.setDither(true);
        fontPaint.setTextAlign(Paint.Align.LEFT);


        textLinePaint.setColor(0xff00ffff);
        textLinePaint.setAntiAlias(true);
        textLinePaint.setDither(true);
        textLinePaint.setStrokeWidth(2);
        textLinePaint.setStyle(Paint.Style.FILL_AND_STROKE);


        // messagePaint = new Paint();
        messagePaint.setTextSize(dpToPixel(11));
        messagePaint.setColor(0xff00ffff);
        messagePaint.setAntiAlias(true);
        messagePaint.setDither(true);
        messagePaint.setStrokeWidth(0);
        messagePaint.setStyle(Paint.Style.FILL_AND_STROKE);
        messagePaint.setTextAlign(Paint.Align.CENTER);
        messagePaint.setShadowLayer(10,5,5,Color.BLACK);

        //messagePaintBack = new Paint();
//        messagePaintBack.setTextSize(dpToPixel(11));
//        messagePaintBack.setColor(0xff000000);//背景不透明
//        messagePaintBack.setAntiAlias(true);
//        messagePaintBack.setDither(true);
//        messagePaintBack.setStrokeWidth(dpToPixel(3));
//        messagePaintBack.setFakeBoldText(true);
//        messagePaintBack.setStyle(Paint.Style.FILL_AND_STROKE);
//        messagePaintBack.setTextAlign(Paint.Align.CENTER);

        //utcPaint = new Paint();
        utcPaint.setTextSize(dpToPixel(10));
        utcPaint.setColor(0xff00ffff);//
        utcPaint.setAntiAlias(true);
        utcPaint.setDither(true);
        utcPaint.setStrokeWidth(0);
        utcPaint.setStyle(Paint.Style.FILL_AND_STROKE);
        utcPaint.setTextAlign(Paint.Align.LEFT);


        //utcPainBack = new Paint();
        utcPainBack.setTextSize(dpToPixel(10));
        utcPainBack.setColor(0xff000000);//背景不透明
        utcPainBack.setAntiAlias(true);
        utcPainBack.setDither(true);
        utcPainBack.setStrokeWidth(dpToPixel(4));
        utcPainBack.setStyle(Paint.Style.FILL_AND_STROKE);
        utcPainBack.setTextAlign(Paint.Align.LEFT);


        pathStart = blockHeight * 2;
        pathEnd = blockHeight * 90;
        if (pathEnd < 130 * getResources().getDisplayMetrics().density) {//为了保证能写的下
            pathEnd = 130 * getResources().getDisplayMetrics().density;
        }

        super.onSizeChanged(w, h, oldw, oldh);

    }

    @SuppressLint("DefaultLocale")
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (lastBitMap == null || lastBitMap.isRecycled()) return;
        canvas.drawBitmap(lastBitMap, 0, 0, null);

        //计算频率
        if (touch_x > 0) {//画触摸线
            freq_hz = Math.round(3000f * (float) touch_x / (float) getWidth());
            if (freq_hz > 2900) {
                freq_hz = 2900;
            }
            if (freq_hz < 100) {
                freq_hz = 100;
            }

            if (touch_x > getWidth() / 2) {
                fontPaint.setTextAlign(Paint.Align.RIGHT);
                canvas.drawText(String.format("%dHz", freq_hz)
                        , touch_x - 10, 250, fontPaint);
            } else {
                fontPaint.setTextAlign(Paint.Align.LEFT);
                canvas.drawText(String.format("%dHz", freq_hz)
                        , touch_x + 10, 250, fontPaint);
            }
            canvas.drawLine(touch_x, 0, touch_x, getHeight(), touchPaint);

        }
        invalidate();
    }

    /**
     * 多段渐变配色 + 自动动态范围：先把当前帧 p2~p99 拉伸到 0~255，再查 8 段渐变表。
     */
    private void fillGradientColors(int[] colors, int[] data) {
        updateDynamicRange(data);
        float scale = 255f / (dynHigh - dynLow);
        for (int i = 0; i < data.length; i++) {
            int v = (int) ((data[i] - dynLow) * scale);
            if (v < 0) v = 0;
            if (v > 255) v = 255;
            colors[i] = WaterfallPalette.COLORS[v];
        }
    }

    /**
     * 按当前帧的 p2~p99 更新动态范围，并做时间平滑，避免画面闪烁。
     */
    private void updateDynamicRange(int[] data) {
        Arrays.fill(dynHist, 0);
        for (int i = 0; i < data.length; i++) {
            dynHist[data[i] & 0xff]++;
        }
        float p2 = percentile(0.02f, data.length);
        float p99 = percentile(0.99f, data.length);

        if (!dynReady) {
            dynLow = p2;
            dynHigh = p99;
            dynReady = true;
        } else {
            dynLow += (p2 - dynLow) * 0.30f;
            dynHigh += (p99 - dynHigh) * 0.30f;
        }

        //范围太小会把噪声放大成雪花，强制至少 12 级
        if (dynHigh - dynLow < 12f) {
            float center = (dynLow + dynHigh) * 0.5f;
            dynLow = center - 6f;
            dynHigh = center + 6f;
        }
        if (dynLow < 0f) dynLow = 0f;
        if (dynHigh > 255f) dynHigh = 255f;
    }

    /**
     * 从当前帧直方图取分位值。
     */
    private float percentile(float p, int total) {
        int target = (int) (total * p);
        int cum = 0;
        for (int v = 0; v < 256; v++) {
            cum += dynHist[v];
            if (cum >= target) return v;
        }
        return 255f;
    }

    public void setWaveData(int[] data, int sequential, List<Ft8Message> msgs) {        if (drawMessage&& msgs!=null){//把需要画的消息复制出来防止多线程访问冲突
            messages=new ArrayList<>(msgs);
        }else {
            messages.clear();//当设定不标记消息时，要清空原来的消息
        }

        if (data == null) {
            return;
        }
        if (data.length <= 0) {
            return;
        }
        if (lastBitMap == null) {
            return;
        }

        int[] colors = new int[data.length];

        //画分割线
        if (sequential != lastSequential) {
            Bitmap bitmap = Bitmap.createBitmap(lastBitMap, 0, 0, getWidth(), getHeight() - blockHeight);
            _canvas.drawBitmap(bitmap, 0, blockHeight, linePaint);
            bitmap.recycle();
            _canvas.drawRect(0, 0, getWidth(), getResources().getDisplayMetrics().density
                    , linePaint);
            _canvas.drawText(UtcTimer.getTimeStr(UtcTimer.getSystemTime()), 50
                    , 15 * getResources().getDisplayMetrics().density, utcPainBack);
            _canvas.drawText(UtcTimer.getTimeStr(UtcTimer.getSystemTime()), 50
                    , 15 * getResources().getDisplayMetrics().density, utcPaint);
        }
        lastSequential = sequential;

        //色块分布：8 段渐变 + 自动动态范围
        fillGradientColors(colors, data);
        LinearGradient linearGradient = new LinearGradient(0, 0, getWidth() * 2, 0, colors
                , null, Shader.TileMode.CLAMP);
        //Paint linearPaint = new Paint();
        linearPaint.setShader(linearGradient);
        Bitmap bitmap = Bitmap.createBitmap(lastBitMap, 0, 0, getWidth(), getHeight() - blockHeight);
        _canvas.drawBitmap(bitmap, 0, blockHeight, linearPaint);
        bitmap.recycle();
        _canvas.drawRect(0, 0, getWidth(), blockHeight, linearPaint);

        //消息有3种：普通、CQ、有我
        if (drawMessage && messages != null) {
            drawMessage = false;//只画一遍
            //fontPaint.setTextAlign(Paint.Align.LEFT);
            //fontPaint.setStrikeThruText(true);
            for (Ft8Message msg : messages) {

                if (msg.inMyCall()) {//与我有关
                    messagePaint.setColor(0xffffb2b2);
                    textLinePaint.setColor(0xffffb2b2);
                } else if (msg.checkIsCQ()) {//CQ
                    messagePaint.setColor(0xffeeee00);
                    textLinePaint.setColor(0xffeeee00);
                } else {
                    messagePaint.setColor(0xff00ffff);
                    textLinePaint.setColor(0xff00ffff);
                }

                Path path = new Path();

                path.moveTo(msg.freq_hz * freq_width, pathStart);
                path.lineTo(msg.freq_hz * freq_width, pathEnd);



//                _canvas.drawTextOnPath(msg.getMessageText(true), path
//                        , 0, 0, messagePaintBack);//消息背景
                _canvas.drawTextOnPath(msg.getMessageText(true), path
                        , 0, 0, messagePaint);//消息
                if (GeneralVariables.checkQSLCallsign(msg.getCallsignFrom())) {//画删除线
                    float text_len = messagePaint.measureText(msg.getMessageText(true));
                    float text_start = ((pathEnd- pathStart)-text_len)/2;
                    float text_high =dpToPixel(4);//messagePaint.getFontSpacing()/2;
                    _canvas.drawLine(msg.freq_hz * freq_width + text_high , text_start
                            , msg.freq_hz * freq_width + text_high, text_len + text_start, textLinePaint);
                }
            }
        }


    }

    /**
     * 页面销毁时主动释放整屏位图，避免等待 GC 期间再次进入频谱页造成 OOM。
     */
    public void releaseBitmap() {
        if (lastBitMap != null && !lastBitMap.isRecycled()) {
            lastBitMap.recycle();
        }
        lastBitMap = null;
        _canvas = null;
    }

    public void setTouch_x(int touch_x) {
        this.touch_x = touch_x;
    }

    public void setDrawMessage(boolean drawMessage) {
        this.drawMessage = drawMessage;
    }

    public int getFreq_hz() {
        return freq_hz;
    }
}
