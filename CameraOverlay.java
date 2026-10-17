package com.example.steadycam;

import android.content.Context;
import android.graphics.*;
import android.os.SystemClock;
import android.view.View;

/** Grid, level and iOS-style focus square with the exposure "sun" slider. */
final class CameraOverlay extends View {
    private static final int YELLOW = Color.rgb(255, 214, 10);
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF pill = new RectF();
    boolean grid = true, level = true, focusLocked = false;
    float roll = 0, focusX = -1, focusY = -1, evFraction = .5f;
    String evText = "";
    int focusColor = YELLOW;
    long focusStart = 0, focusUntil = 0;

    CameraOverlay(Context c) {
        super(c);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void focus(float x, float y, boolean locked) {
        long now = SystemClock.elapsedRealtime();
        focusX = x; focusY = y; focusLocked = locked; focusColor = YELLOW;
        focusStart = now; focusUntil = now + 3500; evText = ""; evFraction = .5f;
        invalidate();
    }

    void focused(boolean success, boolean locked) {
        focusLocked = locked;
        focusColor = success ? YELLOW : Color.rgb(255, 110, 80);
        focusUntil = Math.max(focusUntil, SystemClock.elapsedRealtime() + 1800);
        invalidate();
    }

    boolean focusVisible() {
        return focusX >= 0 && (focusLocked || SystemClock.elapsedRealtime() < focusUntil);
    }

    void setEv(float fraction, String text) {
        evFraction = Math.max(0f, Math.min(1f, fraction));
        evText = text;
        focusUntil = Math.max(focusUntil, SystemClock.elapsedRealtime() + 2500);
        invalidate();
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        float w = getWidth(), h = getHeight(), d = getResources().getDisplayMetrics().density;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(d * .8f);
        if (grid) {
            p.setColor(Color.argb(70, 255, 255, 255));
            for (int i = 1; i <= 2; i++) {
                c.drawLine(w * i / 3, 0, w * i / 3, h, p);
                c.drawLine(0, h * i / 3, w, h * i / 3, p);
            }
        }
        if (level && Math.abs(roll) < 35) {
            boolean flat = Math.abs(roll) < 1.5f;
            c.save();
            c.translate(w / 2, h / 2);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(1.6f * d);
            p.setColor(Color.argb(flat ? 0 : 150, 255, 255, 255));
            c.drawLine(-46 * d, 0, -22 * d, 0, p);
            c.drawLine(22 * d, 0, 46 * d, 0, p);
            c.rotate(-roll);
            p.setStrokeWidth(2f * d);
            p.setColor(flat ? YELLOW : Color.WHITE);
            c.drawLine(-flat2(flat) * d, 0, flat2(flat) * d, 0, p);
            c.restore();
            p.setStrokeCap(Paint.Cap.BUTT);
        }
        long now = SystemClock.elapsedRealtime();
        if (focusX >= 0 && (focusLocked || now < focusUntil)) {
            float fade = focusLocked ? 1f : Math.max(0f, Math.min(1f, (focusUntil - now) / 400f));
            float age = now - focusStart;
            float pop = age < 200 ? 1.22f - .22f * (age / 200f) : 1f;
            int col = Color.argb((int) (255 * fade), Color.red(focusColor), Color.green(focusColor), Color.blue(focusColor));
            float s = 34 * d * pop;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.6f * d);
            p.setColor(col);
            c.drawRect(focusX - s, focusY - s, focusX + s, focusY + s, p);
            float t = 6 * d;
            c.drawLine(focusX, focusY - s, focusX, focusY - s + t, p);
            c.drawLine(focusX, focusY + s, focusX, focusY + s - t, p);
            c.drawLine(focusX - s, focusY, focusX - s + t, focusY, p);
            c.drawLine(focusX + s, focusY, focusX + s - t, focusY, p);
            // exposure sun and rail
            float sx = focusX + s + 20 * d;
            if (sx > w - 22 * d) sx = focusX - s - 20 * d;
            float half = 46 * d;
            float sy = focusY + half - evFraction * 2 * half;
            p.setStrokeWidth(1.1f * d);
            c.drawLine(sx, focusY - half, sx, sy - 14 * d, p);
            c.drawLine(sx, sy + 14 * d, sx, focusY + half, p);
            p.setStrokeWidth(1.5f * d);
            c.drawCircle(sx, sy, 5.5f * d, p);
            for (int i = 0; i < 8; i++) {
                double a = Math.toRadians(i * 45);
                c.drawLine(sx + (float) Math.cos(a) * 8.5f * d, sy + (float) Math.sin(a) * 8.5f * d,
                        sx + (float) Math.cos(a) * 11.5f * d, sy + (float) Math.sin(a) * 11.5f * d, p);
            }
            p.setStyle(Paint.Style.FILL);
            p.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            p.setTextAlign(Paint.Align.CENTER);
            if (evText.length() > 0) {
                p.setTextSize(11 * d);
                c.drawText(evText, sx, sy - 18 * d, p);
            }
            if (focusLocked) {
                String label = "БЛОКИРОВКА AE/AF";
                p.setTextSize(10 * d);
                float tw = p.measureText(label);
                pill.set(focusX - tw / 2 - 9 * d, focusY - s - 28 * d, focusX + tw / 2 + 9 * d, focusY - s - 8 * d);
                p.setColor(YELLOW);
                c.drawRoundRect(pill, 10 * d, 10 * d, p);
                p.setColor(Color.BLACK);
                c.drawText(label, focusX, focusY - s - 14.5f * d, p);
            }
            p.setTextAlign(Paint.Align.LEFT);
            postInvalidateOnAnimation();
        }
    }

    private static float flat2(boolean flat) { return flat ? 46 : 22; }
}
