package com.gallery.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

/** One adjustment tool of the iOS Photos editor: a circle that shows the value as a ring, with its name underneath. */
final class DialButton extends View {
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint value = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint label = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final CharSequence name;
    private final float wPx, hPx, radius;
    private Drawable icon;
    private float frac;
    private boolean bipolar = true, active;
    private String text = "0";

    DialButton(Context c, CharSequence name) {
        super(c);
        this.name = name;
        wPx = Ui.dpf(c, 76f);
        hPx = Ui.dpf(c, 88f);
        radius = Ui.dpf(c, 26f);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeCap(Paint.Cap.ROUND);
        ring.setStrokeWidth(Ui.dpf(c, 3f));
        value.setTextAlign(Paint.Align.CENTER);
        value.setTextSize(Ui.dpf(c, 15f));
        value.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setTextAlign(Paint.Align.CENTER);
        label.setTextSize(Ui.dpf(c, 11.5f));
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        setContentDescription(name);
        Ui.press(c, this);
    }

    void setIcon(int res) {
        icon = getContext().getDrawable(res);
        if (icon != null) icon = icon.mutate();
        invalidate();
    }

    void setActive(boolean on) {
        active = on;
        invalidate();
    }

    /** frac is -1..1 for two-sided tools and 0..1 for one-sided ones; text is what the circle shows. */
    void setState(float frac, String text, boolean bipolar) {
        this.frac = frac;
        this.text = text;
        this.bipolar = bipolar;
        invalidate();
    }

    @Override
    protected void onMeasure(int ws, int hs) {
        setMeasuredDimension(Math.round(wPx), Math.round(hPx));
    }

    @Override
    protected void onDraw(Canvas c) {
        Context ctx = getContext();
        float cx = wPx / 2f, cy = Ui.dpf(ctx, 32f);
        fill.setColor(Ui.color(ctx, active ? R.color.surface_high : R.color.neutral_soft));
        c.drawCircle(cx, cy, radius, fill);
        if (icon == null) {
            float r = radius - ring.getStrokeWidth() / 2f;
            box.set(cx - r, cy - r, cx + r, cy + r);
            ring.setColor(Ui.color(ctx, R.color.stroke));
            ring.setAlpha(active ? 120 : 70);
            c.drawCircle(cx, cy, r, ring);
            if (Math.abs(frac) > 0.004f) {
                ring.setColor(Ui.color(ctx, R.color.accent));
                float sweep = bipolar ? frac * 180f : frac * 360f;
                c.drawArc(box, -90f, sweep, false, ring);
            }
            boolean modified = Math.abs(frac) > 0.004f;
            value.setColor(Ui.color(ctx, modified ? R.color.accent : R.color.text_primary));
            c.drawText(text, cx, cy - (value.ascent() + value.descent()) / 2f, value);
        } else {
            int s = Math.round(Ui.dpf(ctx, 24f));
            icon.setBounds(Math.round(cx) - s / 2, Math.round(cy) - s / 2, Math.round(cx) + s / 2, Math.round(cy) + s / 2);
            icon.setTint(Ui.color(ctx, R.color.accent));
            icon.draw(c);
        }
        label.setColor(Ui.color(ctx, active ? R.color.text_primary : R.color.text_secondary));
        CharSequence t = TextUtils.ellipsize(name, label, wPx - Ui.dpf(ctx, 6f), TextUtils.TruncateAt.END);
        c.drawText(t, 0, t.length(), cx, Ui.dpf(ctx, 72f), label);
    }
}
