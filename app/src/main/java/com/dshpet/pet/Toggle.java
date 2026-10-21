package com.dshpet.pet;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.animation.ValueAnimator;
import android.view.animation.DecelerateInterpolator;

/** 自绘开关（系统 Switch 太丑，且不想为它引入 AndroidX Material）。 */
public class Toggle extends View {
    public interface OnChange {
        void onChanged(boolean on);
    }

    private static final int TRACK_W_DP = 44;
    private static final int TRACK_H_DP = 26;
    private static final int KNOB_DP = 20;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private float progress;
    private boolean checked;
    private OnChange listener;
    private ValueAnimator anim;

    public Toggle(Context c) {
        super(c);
        init();
    }

    public Toggle(Context c, AttributeSet a) {
        super(c, a);
        init();
    }

    private void init() {
        setClickable(true);
        setFocusable(true);
        paint.setStyle(Paint.Style.FILL);
    }

    public void setOnChange(OnChange l) {
        listener = l;
    }

    public void setChecked(boolean on) {
        setChecked(on, false);
    }

    public void setChecked(boolean on, boolean animate) {
        if (checked == on && progress == (on ? 1f : 0f)) return;
        checked = on;
        float target = on ? 1f : 0f;
        if (anim == null) {
            anim = ValueAnimator.ofFloat(progress, target);
            anim.setDuration(170);
            anim.setInterpolator(new DecelerateInterpolator());
            anim.addUpdateListener(a -> {
                progress = (Float) a.getAnimatedValue();
                invalidate();
            });
        }
        anim.cancel();
        if (!animate) {
            progress = target;
            invalidate();
            return;
        }
        anim.setFloatValues(progress, target);
        anim.start();
    }

    public boolean isChecked() {
        return checked;
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(dp(TRACK_W_DP) + dp(6), dp(TRACK_H_DP) + dp(6));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float d = getResources().getDisplayMetrics().density;
        float trackW = TRACK_W_DP * d;
        float trackH = TRACK_H_DP * d;
        float left = (getWidth() - trackW) / 2f;
        float top = (getHeight() - trackH) / 2f;

        int off = 0xFFD7DBE2;
        int on = 0xFF4C6FFF;
        paint.setColor(blend(off, on, checked ? 1f : 0f));
        rect.set(left, top, left + trackW, top + trackH);
        canvas.drawRoundRect(rect, trackH / 2f, trackH / 2f, paint);

        float knob = KNOB_DP * d;
        float pad = (trackH - knob) / 2f;
        float cx = left + pad + knob / 2f + (trackW - knob - pad * 2f) * (checked ? 1f : 0f);
        paint.setColor(Color.WHITE);
        paint.setShadowLayer(d * 1.5f, 0, d * 0.8f, 0x33000000);
        canvas.drawCircle(cx, top + trackH / 2f, knob / 2f, paint);
        paint.clearShadowLayer();
    }

    private static int blend(int a, int b, float t) {
        int ar = (a >> 16) & 0xFF;
        int ag = (a >> 8) & 0xFF;
        int ab = a & 0xFF;
        int br = (b >> 16) & 0xFF;
        int bg = (b >> 8) & 0xFF;
        int bb = b & 0xFF;
        int r = (int) (ar + (br - ar) * t);
        int g = (int) (ag + (bg - ag) * t);
        int bl = (int) (ab + (bb - ab) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
