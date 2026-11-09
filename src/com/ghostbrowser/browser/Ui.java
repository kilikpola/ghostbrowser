package com.ghostbrowser.browser;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.view.View;

/** Small drawing helpers; the UI is built in code so it adapts to toolbar position and theme. */
final class Ui {
    private Ui() {}

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable rounded(int fill, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(radius);
        return d;
    }

    static GradientDrawable outlined(int stroke, int width, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.TRANSPARENT);
        d.setStroke(width, stroke);
        d.setCornerRadius(radius);
        return d;
    }

    static GradientDrawable oval(int fill) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(fill);
        return d;
    }

    @SuppressWarnings("deprecation")
    static void bg(View v, android.graphics.drawable.Drawable d) {
        v.setBackgroundDrawable(d); // setBackground() needs API 16
    }

    static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    static int darker(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[2] *= 0.78f;
        return Color.HSVToColor(hsv);
    }

    /** Blends {@code a} over {@code b}; {@code t} is the weight of {@code a}. */
    static int mix(int a, int b, float t) {
        int r = Math.round(Color.red(a) * t + Color.red(b) * (1 - t));
        int g = Math.round(Color.green(a) * t + Color.green(b) * (1 - t));
        int bl = Math.round(Color.blue(a) * t + Color.blue(b) * (1 - t));
        return Color.rgb(r, g, bl);
    }

    /** Roboto Medium on Android 5+, the regular face on older releases (no fake bold). */
    static android.graphics.Typeface medium() {
        return android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL);
    }

    /**
     * Holds a page and presents it as a rounded card floating in the browser frame (Zen's look).
     * Corners are masked in the frame colour rather than clipped, because clipPath isn't
     * hardware-accelerated before Android 4.3 and WebView must stay accelerated.
     */
    static final class Card extends android.widget.FrameLayout {
        private final Paint mask = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path corners = new android.graphics.Path();
        private final float radius;
        private boolean outlined;

        Card(Context c) {
            super(c);
            radius = dp(c, 12);
            outline.setStyle(Paint.Style.STROKE);
            outline.setStrokeWidth(dp(c, 1.5f));
            setWillNotDraw(false);
        }

        void setFrameColor(int color) {
            mask.setColor(color);
            invalidate();
        }

        void setOutline(boolean on, int color) {
            outlined = on;
            outline.setColor(color);
            invalidate();
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            float r = radius, d = 2 * r;
            corners.reset();
            corner(0, 0, new android.graphics.RectF(0, 0, d, d), 180);
            corner(w, 0, new android.graphics.RectF(w - d, 0, w, d), 270);
            corner(w, h, new android.graphics.RectF(w - d, h - d, w, h), 0);
            corner(0, h, new android.graphics.RectF(0, h - d, d, h), 90);
        }

        /** Adds the sliver between a view corner (cx, cy) and the quarter circle inside it. */
        private void corner(float cx, float cy, android.graphics.RectF oval, float start) {
            corners.moveTo(cx, cy);
            corners.arcTo(oval, start, 90, false);
            corners.lineTo(cx, cy);
            corners.close();
        }

        @Override protected void dispatchDraw(Canvas canvas) {
            super.dispatchDraw(canvas);
            canvas.drawPath(corners, mask);
            if (outlined) {
                float in = outline.getStrokeWidth() / 2;
                canvas.drawRoundRect(new android.graphics.RectF(in, in, getWidth() - in, getHeight() - in),
                        radius - in, radius - in, outline);
            }
        }
    }

    /** Vertical "more" dots for the menu button (avoids relying on glyphs missing from old fonts). */
    static final class Dots extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        Dots(Context c) {
            super(c);
            paint.setColor(Color.WHITE);
        }

        void setColor(int color) {
            paint.setColor(color);
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            float r = dp(getContext(), 2.2f);
            float gap = dp(getContext(), 6.5f);
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            canvas.drawCircle(cx, cy - gap, r, paint);
            canvas.drawCircle(cx, cy, r, paint);
            canvas.drawCircle(cx, cy + gap, r, paint);
        }
    }

    /** A 3dp page-load progress line. */
    static final class Progress extends View {
        private final Paint paint = new Paint();
        private int progress = 100;

        Progress(Context c) {
            super(c);
        }

        void setColor(int color) {
            paint.setColor(color);
            invalidate();
        }

        void setProgress(int p) {
            progress = p;
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            if (progress >= 100) return;
            float w = getWidth() * Math.max(5, progress) / 100f;
            canvas.drawRect(0, 0, w, getHeight(), paint);
        }
    }
}
