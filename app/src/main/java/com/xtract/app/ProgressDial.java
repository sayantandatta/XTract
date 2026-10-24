package com.xtract.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.View;

/**
 * Ring-shaped progress indicator in the top bar. It is idle (an empty track) while the user
 * browses and only animates while a thread is being collected, exported or printed.
 */
final class ProgressDial extends View {

    private enum Mode { IDLE, INDETERMINATE, DETERMINATE, DONE }

    private static final long DONE_VISIBLE_MS = 1800;

    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final Path check = new Path();
    private final Runnable backToIdle = new Runnable() {
        @Override
        public void run() {
            setIdle();
        }
    };

    private Mode mode = Mode.IDLE;
    private float shown;      // fraction currently drawn
    private float spin;       // start angle of the indeterminate arc
    private ValueAnimator anim;
    private int trackColor = 0xFFEFF3F4, accentColor = AppTheme.ACCENT, textColor = 0xFF0F1419;

    ProgressDial(Context context) {
        super(context);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeCap(Paint.Cap.ROUND);
        ring.setStrokeJoin(Paint.Join.ROUND);
        label.setTextAlign(Paint.Align.CENTER);
        label.setTypeface(Typeface.DEFAULT_BOLD);
    }

    void setColors(int track, int accent, int text) {
        trackColor = track;
        accentColor = accent;
        textColor = text;
        invalidate();
    }

    boolean isIdle() {
        return mode == Mode.IDLE;
    }

    void setIdle() {
        switchTo(Mode.IDLE);
        shown = 0;
        invalidate();
    }

    void setIndeterminate() {
        if (mode == Mode.INDETERMINATE) return;
        switchTo(Mode.INDETERMINATE);
        invalidate();
    }

    /** Shows {@code fraction} (0..1), animating from the value currently drawn. */
    void setProgress(float fraction) {
        final float target = Math.max(0f, Math.min(1f, fraction));
        if (mode != Mode.DETERMINATE) {
            switchTo(Mode.DETERMINATE);
            shown = 0;
        }
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(shown, target);
        anim.setDuration(250);
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                shown = (Float) a.getAnimatedValue();
                invalidate();
            }
        });
        anim.start();
    }

    /** Full ring with a check mark, then back to idle. */
    void setDone() {
        switchTo(Mode.DONE);
        shown = 1;
        invalidate();
        postDelayed(backToIdle, DONE_VISIBLE_MS);
    }

    private void switchTo(Mode m) {
        removeCallbacks(backToIdle);
        if (anim != null) {
            anim.cancel();
            anim = null;
        }
        mode = m;
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float stroke = dp(3);
        float inset = stroke / 2f + dp(1);
        box.set(inset, inset, w - inset, h - inset);
        ring.setStrokeWidth(stroke);
        ring.setColor(trackColor);
        c.drawOval(box, ring);
        ring.setColor(accentColor);

        switch (mode) {
            case INDETERMINATE:
                c.drawArc(box, spin, 90, false, ring);
                spin = (spin + 5) % 360;
                postInvalidateOnAnimation();
                break;
            case DETERMINATE:
                if (shown > 0) c.drawArc(box, -90, 360 * shown, false, ring);
                drawLabel(c, Math.round(shown * 100) + "%");
                break;
            case DONE:
                c.drawOval(box, ring);
                check.reset();
                check.moveTo(w * 0.30f, h * 0.52f);
                check.lineTo(w * 0.44f, h * 0.66f);
                check.lineTo(w * 0.71f, h * 0.37f);
                ring.setStrokeWidth(dp(2.5f));
                c.drawPath(check, ring);
                break;
            default:
                break;
        }
    }

    private void drawLabel(Canvas c, String s) {
        label.setColor(textColor);
        label.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, s.length() > 3 ? 8.5f : 10f,
                getResources().getDisplayMetrics()));
        float y = getHeight() / 2f - (label.ascent() + label.descent()) / 2f;
        c.drawText(s, getWidth() / 2f, y, label);
    }

    private float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}
