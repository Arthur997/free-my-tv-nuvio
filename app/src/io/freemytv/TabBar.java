package io.freemytv;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

/**
 * The tabs at the top of the weather and football screens, as the home screen's: one glass capsule with the names in
 * it. The tab in use sits on a pill: white with a shadow while the focus is on the tabs (← → change it), faint while
 * the focus is down in the page. Laid out once per size or language; drawing allocates nothing.
 */
final class TabBar {
    private static final float HEIGHT = 68, PAD_X = 30, INSET = 6;
    private final String[] labels;
    private final RectF[] rects;
    private final RectF bar = new RectF(), shadow = new RectF();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), glow = new Paint(Paint.ANTI_ALIAS_FLAG);

    TabBar(String... labels) {
        this.labels = labels;
        rects = new RectF[labels.length];
        for (int i = 0; i < rects.length; i++) rects[i] = new RectF();
    }

    int count() {
        return labels.length;
    }

    String label(int i) {
        return labels[i];
    }

    /** Places the bar with its left edge at `x` and its middle at `cy` (pixels); `text` is the selected tab's paint. */
    void layout(float x, float cy, float k, Paint text) {
        float h = HEIGHT * k, left = x + INSET * k;
        for (int i = 0; i < labels.length; i++) {
            float w = text.measureText(labels[i]) + 2 * PAD_X * k;
            rects[i].set(left, cy - h / 2 + INSET * k, left + w, cy + h / 2 - INSET * k);
            left += w;
        }
        bar.set(x, cy - h / 2, left + INSET * k, cy + h / 2);
        glow.setMaskFilter(new android.graphics.BlurMaskFilter(18 * k, android.graphics.BlurMaskFilter.Blur.NORMAL));
    }

    float right() {
        return bar.right;
    }

    /** `focused`: the focus is on the tabs. `onPill` is the ink on the white pill (dark in both looks). */
    void draw(Canvas c, DockGlass glass, Shadows shadows, boolean light, float k, int selected, boolean focused,
              Paint rest, Paint on, int onPill) {
        glass.draw(c, bar, bar.height() / 2, light ? 0 : 1, k, 1);
        RectF r = rects[selected];
        float radius = r.height() / 2;
        if (focused) {
            shadow.set(r);
            shadow.offset(0, 6 * k);
            glow.setColor(light ? 0x29000000 : 0x66000000);
            shadows.draw(c, shadow, radius, 18 * k, glow);
            fill.setColor(light ? 0xFFFFFFFF : 0xFFF5F5F7);
        } else {
            fill.setColor(light ? 0x14000000 : 0x24FFFFFF);
        }
        c.drawRoundRect(r, radius, radius, fill);
        for (int i = 0; i < labels.length; i++) {
            Paint p = i == selected ? on : rest;
            int keep = p.getColor();
            if (i == selected && focused) p.setColor(onPill);
            float y = rects[i].centerY() + Ui.halfCap(p);
            c.drawText(labels[i], rects[i].centerX() - p.measureText(labels[i]) / 2, y, p);
            p.setColor(keep);
        }
    }
}
