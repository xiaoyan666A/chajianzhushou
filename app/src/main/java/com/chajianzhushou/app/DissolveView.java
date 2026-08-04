package com.chajianzhushou.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 颗粒化渐隐视图：播放一组"逐帧随机抹除像素块"的位图序列，
 * 让一张卡片看起来像被打散成颗粒慢慢消失。
 * 帧由 buildFrames 在后台线程生成；本视图只负责按进度绘制。
 */
public class DissolveView extends View {

    /** 渐隐帧数 */
    public static final int FRAME_COUNT = 10;
    /** 帧图最大边（缩小后生成，节约内存；绘制时按视图尺寸拉伸） */
    private static final int MAX_DIM = 240;
    /** 颗粒块大小（像素），越大颗粒越粗 */
    private static final int BLOCK = 4;

    private List<Bitmap> frames;
    private float progress = 0f;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final RectF dst = new RectF();

    public DissolveView(Context context) {
        super(context);
    }

    public DissolveView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setFrames(List<Bitmap> frames) {
        this.frames = frames;
        invalidate();
    }

    /** 0~1：0 为完整卡片，1 为全部消失 */
    public void setProgress(float progress) {
        this.progress = Math.max(0f, Math.min(1f, progress));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (frames == null || frames.isEmpty()) return;
        int idx = Math.min((int) (progress * frames.size()), frames.size() - 1);
        Bitmap bmp = frames.get(idx);
        if (bmp == null || bmp.isRecycled()) return;
        dst.set(0, 0, getWidth(), getHeight());
        canvas.drawBitmap(bmp, null, dst, paint);
    }

    /** 释放帧位图（动画结束调用，避免内存占用） */
    public void recycleFrames() {
        if (frames != null) {
            for (Bitmap b : frames) {
                if (b != null && !b.isRecycled()) b.recycle();
            }
            frames = null;
        }
    }

    /**
     * 生成颗粒化渐隐帧序列（后台线程调用）。
     * 思路：把原图缩小，按小块预置随机噪点；第 f 帧保留噪点值 >= f/帧数的块，
     * 并给保留像素叠加轻微整体变淡，让结尾更柔和。
     */
    public static List<Bitmap> buildFrames(Bitmap source) {
        List<Bitmap> out = new ArrayList<>();
        if (source == null || source.isRecycled() || source.getWidth() <= 0 || source.getHeight() <= 0) {
            return out;
        }
        try {
            int sw = source.getWidth();
            int sh = source.getHeight();
            float scale = Math.min(1f, MAX_DIM / (float) Math.max(sw, sh));
            int w = Math.max(1, Math.round(sw * scale));
            int h = Math.max(1, Math.round(sh * scale));
            Bitmap scaled = Bitmap.createScaledBitmap(source, w, h, true);
            int[] pixels = new int[w * h];
            scaled.getPixels(pixels, 0, w, 0, 0, w, h);
            if (scaled != source) {
                scaled.recycle();
            }
            int bw = (w + BLOCK - 1) / BLOCK;
            int bh = (h + BLOCK - 1) / BLOCK;
            float[] noise = new float[bw * bh];
            Random rnd = new Random(System.nanoTime());
            for (int i = 0; i < noise.length; i++) {
                noise[i] = rnd.nextFloat();
            }
            for (int f = 0; f < FRAME_COUNT; f++) {
                float t = f / (float) (FRAME_COUNT - 1);
                int[] fp = new int[w * h];
                for (int y = 0; y < h; y++) {
                    int by = y / BLOCK;
                    for (int x = 0; x < w; x++) {
                        int idx = y * w + x;
                        int c = pixels[idx];
                        float keep = noise[by * bw + x / BLOCK];
                        if (keep >= t) {
                            int a = (int) ((c >>> 24) * (1f - t * 0.35f));
                            fp[idx] = (a << 24) | (c & 0x00FFFFFF);
                        } else {
                            fp[idx] = 0;
                        }
                    }
                }
                out.add(Bitmap.createBitmap(fp, w, h, Bitmap.Config.ARGB_8888));
            }
        } catch (Throwable ignore) {
            // 生成失败：返回空列表，调用方回退为直接移除
        }
        return out;
    }
}
