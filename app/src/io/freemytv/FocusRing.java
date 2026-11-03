package io.freemytv;

import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

/**
 * Where the focus is, readable from the couch: a soft glow behind the focused item and a thin line a few pixels
 * outside it, its corners concentric with the item's. Growth alone was easy to miss. Nothing is allocated per frame.
 */
final class FocusRing {
    private static final float GAP = 5, WIDTH = 2.5f, GLOW_BLUR = 22, GLOW_SPREAD = 4;
    private final Shadows shadows;
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG), glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private float scale = -1;

    FocusRing(Shadows shadows) {
        this.shadows = shadows;
        ring.setStyle(Paint.Style.STROKE);
    }

    private void size(float k) {
        if (k == scale) return;
        scale = k;
        glow.setMaskFilter(new BlurMaskFilter(GLOW_BLUR * k, BlurMaskFilter.Blur.NORMAL));
        ring.setStrokeWidth(WIDTH * k);
    }

    /** The glow, drawn before the item (it shows around it, never over it). `f` is the focus, 0 to 1. */
    void glow(Canvas c, RectF shape, float r, float f, float k, int color) {
        if (f < 0.01f || (color >>> 24) == 0) return;
        size(k);
        float spread = GLOW_SPREAD * k;
        box.set(shape);
        box.inset(-spread, -spread);
        glow.setColor(Ui.alpha(color, f));
        shadows.draw(c, box, r + spread, GLOW_BLUR * k, glow);
    }

    /** The line, drawn after the item. */
    void ring(Canvas c, RectF shape, float r, float f, float k, int color) {
        if (f < 0.01f) return;
        size(k);
        float out = (GAP + WIDTH / 2) * k;
        box.set(shape);
        box.inset(-out, -out);
        ring.setColor(Ui.alpha(color, f));
        c.drawRoundRect(box, r + out, r + out, ring);
    }
}
