package io.freemytv;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

/**
 * Free My TV's one glass (the shelf's, tinted as the Control Center's): light gray by day, smoked at night, a drop shadow
 * and the Liquid Glass rim, its rim and bend scaled down for smaller glass. Every card, button, panel and notice on the
 * home screen and the weather screen is made of it. Performance mode (and Android 8 to 12): Scene's flat look.
 */
final class DockGlass {
    /** The shelf glass's height, in the 1920 × 1080 space: the rim is sized for it. */
    private static final float REFERENCE_H = 206;
    static final float SHADOW_BLUR = 26;
    /**
     * The day glass is a light gray, not white: on the near-white day background a white glass vanished, and with it
     * the white of a focused item, white app art and the glass's own rim.
     */
    private static final int SMOKE = 0xFF111113, DAY = 0xFFC9C9D0;

    private final Scene scene;
    private final Shadows shadows;
    /** Its BlurMaskFilter must have radius SHADOW_BLUR × k. */
    private final Paint shadow;
    private final RectF drop = new RectF();

    DockGlass(Scene scene, Shadows shadows, Paint shadow) {
        this.scene = scene;
        this.shadows = shadows;
        this.shadow = shadow;
    }

    /** With its shadow. `dark`: 0 by day, 1 at night (in between during the transition). */
    void draw(Canvas c, RectF r, float radius, float dark, float k, float alpha) {
        if (alpha <= 0.004f) return;
        drop.set(r);
        drop.offset(0, 10 * k * Math.max(0.4f, scale(r, k)));
        shadow.setColor(Ui.alpha(mix(0x1F000000, 0x52000000, dark), alpha));
        shadows.draw(c, drop, radius, SHADOW_BLUR * k, shadow);
        body(c, r, radius, dark, k, alpha);
    }

    /**
     * Without its shadow (for things that draw their own, like a focus that lifts them). The tint is the Control
     * Center's: denser than the shelf's first clear smoke, which over a bright photo at night left text hard to read;
     * the rim, the bend and the light keep the glass look.
     */
    void body(Canvas c, RectF r, float radius, float dark, float k, float alpha) {
        float size = scale(r, k);
        scene.liquid(c, r, radius, mix(DAY, SMOKE, dark), lerp(0.62f, 0.55f, dark), lerp(0.02f, 0.08f, dark),
                Math.max(8 * k, lerp(30, 34, dark) * k * size), Math.max(5 * k, lerp(22, 28, dark) * k * size),
                lerp(0.65f, 0.95f, dark), alpha);
    }

    /**
     * What the glass refracts on a screen with a plain vertical gradient behind it (no photo): that gradient, as the
     * home screen does without a photo.
     */
    void refract(int top, int bottom) {
        scene.setFrost(gradient(Backdrop.FW, Backdrop.FH, top, bottom));
        scene.setGlassImage(gradient(Backdrop.GW, Backdrop.GH, top, bottom));
    }

    private static int[] gradient(int width, int height, int top, int bottom) {
        int[] px = new int[width * height];
        for (int y = 0; y < height; y++) java.util.Arrays.fill(px, y * width, (y + 1) * width, mix(top, bottom, y / (float) (height - 1)));
        return px;
    }

    private static float scale(RectF r, float k) {
        return Math.min(1, Math.min(r.width(), r.height()) / (REFERENCE_H * k));
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static int mix(int a, int b, float f) {
        int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return (Math.round(aa + (ba - aa) * f) << 24) | (Math.round(ar + (br - ar) * f) << 16)
                | (Math.round(ag + (bg - ag) * f) << 8) | Math.round(ab + (bb - ab) * f);
    }
}
