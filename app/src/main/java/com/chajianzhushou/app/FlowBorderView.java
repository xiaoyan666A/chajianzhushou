package com.chajianzhushou.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * 流水灯边框：黄色光点沿圆角矩形边框持续环绕流动（跑马灯风格），
 * 用于超时件"出库时间"文字的外圈标注。边框基线为半透明黄，光点为亮黄。
 */
public class FlowBorderView extends View {
    private final Paint basePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rectF = new RectF();
    private final Path borderPath = new Path();
    private final PathMeasure pathMeasure = new PathMeasure();
    private ValueAnimator animator;
    private float phase = 0f;
    private float radius = 0f;

    public FlowBorderView(Context context) {
        this(context, null);
    }

    public FlowBorderView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public FlowBorderView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        basePaint.setStyle(Paint.Style.STROKE);
        basePaint.setStrokeWidth(dp(1.5f));
        lightPaint.setStyle(Paint.Style.STROKE);
        lightPaint.setStrokeCap(Paint.Cap.ROUND);
        lightPaint.setStrokeWidth(dp(3f));
        try {
            basePaint.setColor(getResources().getColor(R.color.warning_35, context.getTheme()));
            lightPaint.setColor(getResources().getColor(R.color.warning, context.getTheme()));
        } catch (Throwable t) {
            basePaint.setColor(0x59FFA726);
            lightPaint.setColor(0xFFFFA726);
        }
        // 与时间 chip 背景（bg_btn_back）圆角一致，保证流水灯贴合出库时间框
        radius = dp(8);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // FrameLayout 为 wrap_content 时，MATCH_PARENT 子视图会被测量成整行宽度；
        // 这里改为跟随同一 FrameLayout 内第一个子视图（时间 chip）的实际尺寸，保证流水灯贴合时间框。
        if (getParent() instanceof android.view.ViewGroup) {
            android.view.ViewGroup parent = (android.view.ViewGroup) getParent();
            for (int i = 0; i < parent.getChildCount(); i++) {
                android.view.View c = parent.getChildAt(i);
                if (c != this && c.getVisibility() != GONE) {
                    setMeasuredDimension(c.getMeasuredWidth(), c.getMeasuredHeight());
                    return;
                }
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float sw = Math.max(basePaint.getStrokeWidth(), lightPaint.getStrokeWidth());
        rectF.set(sw, sw, w - sw, h - sw);
        borderPath.reset();
        borderPath.addRoundRect(rectF, radius, radius, Path.Direction.CW);
        pathMeasure.setPath(borderPath, false);
        startFlow();
    }

    /** 启动流水动画（与卡片边框闪烁、标签闪烁互相独立，互不干扰） */
    public void startFlow() {
        if (animator != null) return;
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(2200);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(null);
        animator.addUpdateListener(a -> {
            phase = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    public void stopFlow() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopFlow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float len = pathMeasure.getLength();
        if (len <= 0) return;
        // 基线边框（半透明黄）
        canvas.drawPath(borderPath, basePaint);
        // 流动光点：沿边框循环移动的一段亮黄线段
        float seg = Math.max(dp(30), len * 0.10f);
        float start = phase * len;
        Path segPath = new Path();
        pathMeasure.getSegment(start, Math.min(start + seg, len), segPath, true);
        if (start + seg > len) {
            // 首尾衔接：溢出部分从起点继续
            pathMeasure.getSegment(0, start + seg - len, segPath, true);
        }
        canvas.drawPath(segPath, lightPaint);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
