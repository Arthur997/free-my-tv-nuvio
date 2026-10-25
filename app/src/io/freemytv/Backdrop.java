package io.freemytv;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * The home screen background made from the recommendation's image, built once in the background:
 *  - the photo cropped to 16:9 at the TV's UI size (1920×1080; 1280×720 on TVs that draw their UI at 720p, like TCL's
 *    1 GB models, where every bitmap here is 2/3 as wide), with the dark scrims already applied (on the left,
 *    where the hero text sits; at the bottom, behind the shelf; a faint one at the top, behind the buttons); the left
 *    scrim gets stronger when the photo is bright there;
 *  - the frosted version (192×108, heavily blurred) of the same image: the background of the frosted glass and of the
 *    bottom drawer;
 *  - the Liquid Glass version (480×270, lightly blurred): the photo shows through the shelf and the cards;
 *  - with forLight: the cards (the photo without scrims, as the 16:9 card, already at its focused size) and
 *    the frosted and glass versions blended with white (by day) and with near-black (at night).
 * Only what is needed gets built (with forLight, no full photo: the cards are lightweight, so all 9 for the three tabs
 * fit in memory, ready); the other fields stay null.
 * The final image lives only in GPU memory (HARDWARE): drawing it costs one texture, and the process doesn't hold 8 MB.
 */
final class Backdrop {
    /** The UI's size against 1920×1080 (at most 1): nothing is built bigger than the screen can show. */
    private static final float UI_SCALE = uiScale();
    static final int W = ui(1920), H = ui(1080), FW = 192, FH = 108, GW = 480, GH = 270;
    /**
     * The cards at their focused size (1.05×), 16:9 like the photos: the big one and the four small ones (each its
     * own size, so the small ones aren't a big image squeezed down, which shimmers).
     */
    static final int WIDE_W = even(920), WIDE_H = ui(518), SMALL_W = even(424), SMALL_H = ui(238);

    private static float uiScale() {
        android.util.DisplayMetrics m = android.content.res.Resources.getSystem().getDisplayMetrics();
        int longest = Math.max(m.widthPixels, m.heightPixels);
        return longest > 0 ? Math.min(1f, longest / 1920f) : 1f;
    }

    private static int ui(int px) {
        return Math.round(px * UI_SCALE);
    }

    /** A width at the UI's size, even: see opaque() (at 720p, 920 would be 613). */
    private static int even(int px) {
        return 2 * Math.round(px * UI_SCALE / 2);
    }
    final Bitmap image;
    final int[] frost;      // heavily blurred (frosted glass, bottom drawer)
    final int[] glass;      // lightly blurred (Liquid Glass: the photo shows through the glass)
    final Bitmap wide, small;           // the photo on the big card and on the small ones
    final int[] light, lightGlass;      // by day: the frosted and glass versions, lightened
    final Bitmap lightImage;            // by day: the background (the lightened frosted version, scaled up on screen)
    final int[] nightFrost, nightGlass; // at night: frosted (the bottom drawer's frosted glass) and glass, darkened
    final Bitmap nightImage;            // at night: the background (the darkened frosted version)

    private Backdrop(Bitmap image, int[] frost, int[] glass, Bitmap wide, Bitmap small, int[] light, int[] lightGlass,
                     int[] night, int[] nightGlass) {
        this.image = image;
        this.frost = frost;
        this.glass = glass;
        this.wide = wide;
        this.small = small;
        this.light = light;
        this.lightGlass = lightGlass;
        this.nightFrost = night;
        this.nightGlass = nightGlass;
        lightImage = light == null ? null : Bitmap.createBitmap(light, FW, FH, Bitmap.Config.ARGB_8888);
        nightImage = night == null ? null : Bitmap.createBitmap(night, FW, FH, Bitmap.Config.ARGB_8888);
    }

    static Backdrop make(Bitmap src, boolean forLight) {
        return make(src, forLight, null);
    }

    /** `preview`: where to also save the big card as a JPEG (Recs.preview shows it at once on the next start). */
    static Backdrop make(Bitmap src, boolean forLight, java.io.File preview) {
        if (forLight) {
            // Light design: everything comes from the photo without scrims. The cards straight from it (cropped to 16:9
            // at their own size), the frosted and glass versions from the big card: no full-screen copy (8 MB, drawn on
            // every build) is made.
            Bitmap wide = card(src, WIDE_W, WIDE_H), small = gpu(card(src, SMALL_W, SMALL_H));
            src.recycle();
            if (preview != null) savePreview(wide, preview);
            int[] frost = reduce(wide, FW, FH, 3, 3);
            int[] light = whiten(frost.clone(), 0.82f);
            int[] glass = reduce(wide, GW, GH, 2, 2);
            int[] lightGlass = whiten(glass.clone(), 0.74f);
            int[] night = darken(frost.clone(), 0.76f);
            int[] nightGlass = darken(glass, 0.56f);
            return new Backdrop(null, frost, null, gpu(wide), small, light, lightGlass, night, nightGlass);
        }
        Bitmap out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint photo = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        float scale = Math.max((float) W / src.getWidth(), (float) H / src.getHeight());
        float sw = src.getWidth() * scale, sh = src.getHeight() * scale;
        c.drawBitmap(src, null, new RectF((W - sw) / 2, (H - sh) / 2, (W + sw) / 2, (H + sh) / 2), photo);
        src.recycle();

        float boost = Math.max(0, Math.min(0.3f, (luminance(out, 0, 0.22f, 0.5f, 0.62f) - 0.22f) * 1.1f));
        Paint scrim = new Paint(Paint.DITHER_FLAG);
        scrim.setShader(new LinearGradient(0, 0, W * 0.68f, 0,
                new int[] {black(0.66f + boost), black(0.52f + boost * 0.8f), black(0.26f + boost * 0.4f), black(0.07f), 0},
                new float[] {0, 0.25f, 0.55f, 0.8f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, H, scrim);
        // At the bottom, a faint scrim: the photo must show through the glass shelf (the glass provides the contrast).
        scrim.setShader(new LinearGradient(0, H, 0, H * 0.4f,
                new int[] {black(0.38f), black(0.3f), black(0.18f), black(0.05f), 0},
                new float[] {0, 0.25f, 0.55f, 0.8f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(0, H * 0.4f, W, H, scrim);
        // Behind the prices (above the shelf, on the right), a localized scrim for the small text.
        scrim.setShader(new android.graphics.RadialGradient(W * 0.8f, H * 0.65f, W * 0.24f,
                new int[] {black(0.42f), black(0.18f), 0}, new float[] {0, 0.55f, 1}, Shader.TileMode.CLAMP));
        c.save();
        c.scale(1, 0.32f, W * 0.8f, H * 0.65f);
        c.drawCircle(W * 0.8f, H * 0.65f, W * 0.24f, scrim);
        c.restore();
        scrim.setShader(new LinearGradient(0, 0, 0, H * 0.24f, new int[] {black(0.34f), black(0.1f), 0},
                new float[] {0, 0.5f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, H * 0.24f, scrim);

        int[] frost = reduce(out, FW, FH, 3, 3);
        int[] glass = reduce(out, GW, GH, 2, 2);
        return new Backdrop(gpu(out), frost, glass, null, null, null, null, null, null);
    }

    /** How many times smaller the screensaver photo's frosted version is (the glass math uses the same number). */
    static final int PHOTO_FROST = 6;

    /**
     * Screensaver: the whole original from disk at w × h (center-cropped to that aspect ratio), filtered down, plus the
     * frosted version (1/6, blurred) for the glass clock. `light` receives the luminance (0 to 1) where the caption
     * (bottom left) and the clock (top right) sit, for the scrims. Returns {photo (mutable), frosted}.
     */
    static Bitmap[] photo(java.io.File f, int w, int h, float[] light) throws java.io.IOException {
        android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeFile(f.getPath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new java.io.IOException("unreadable image");
        android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
        opts.inSampleSize = 1;
        while (bounds.outWidth / (opts.inSampleSize * 2) >= w && bounds.outHeight / (opts.inSampleSize * 2) >= h) opts.inSampleSize *= 2;
        Bitmap src = android.graphics.BitmapFactory.decodeFile(f.getPath(), opts);
        if (src == null) throw new java.io.IOException("unreadable image");
        float ratio = (float) w / h;
        int cw = Math.min(src.getWidth(), Math.round(src.getHeight() * ratio));
        int ch = Math.min(src.getHeight(), Math.round(cw / ratio));
        Bitmap b = Bitmap.createBitmap(src, (src.getWidth() - cw) / 2, (src.getHeight() - ch) / 2, cw, ch);
        if (b != src) src.recycle();
        while (b.getWidth() / 2 >= w) {
            Bitmap half = Bitmap.createScaledBitmap(b, b.getWidth() / 2, b.getHeight() / 2, true);
            b.recycle();
            b = half;
        }
        Bitmap out = b.getWidth() == w && b.getHeight() == h ? b : Bitmap.createScaledBitmap(b, w, h, true);
        if (out != b) b.recycle();
        if (light != null && light.length >= 2) {
            light[0] = luminance(out, 0, 0.6f, 0.5f, 0.95f);
            light[1] = luminance(out, 0.62f, 0.04f, 0.96f, 0.36f);
        }
        int fw = w / PHOTO_FROST, fh = h / PHOTO_FROST;
        Bitmap frost = Bitmap.createBitmap(reduce(out, fw, fh, 3, 2), fw, fh, Bitmap.Config.ARGB_8888);
        if (!out.isMutable()) {
            Bitmap copy = out.copy(Bitmap.Config.ARGB_8888, true);
            out.recycle();
            out = copy;
        }
        return new Bitmap[] {out, frost};
    }

    /** The center of the photo at the card's aspect ratio, filtered down (halving while possible) to the exact size. */
    private static Bitmap card(Bitmap full, int w, int h) {
        float ratio = (float) w / h;
        int cw = Math.min(full.getWidth(), Math.round(full.getHeight() * ratio));
        int ch = Math.min(full.getHeight(), Math.round(cw / ratio));
        Bitmap b = Bitmap.createBitmap(full, (full.getWidth() - cw) / 2, (full.getHeight() - ch) / 2, cw, ch);
        while (b.getWidth() / 2 >= w) {
            Bitmap half = Bitmap.createScaledBitmap(b, b.getWidth() / 2, b.getHeight() / 2, true);
            if (b != full) b.recycle();
            b = half;
        }
        Bitmap out = Bitmap.createScaledBitmap(b, w, h, true);
        if (b != full && b != out) b.recycle();
        if (out == full) out = full.copy(full.getConfig(), false);   // already that size: opaque() mustn't recycle the photo
        return opaque(out);   // still in memory: the caller moves it to the GPU (after saving a preview)
    }

    private static void savePreview(Bitmap card, java.io.File f) {
        //noinspection ResultOfMethodCallIgnored
        f.getParentFile().mkdirs();
        java.io.File part = new java.io.File(f.getPath() + ".part");
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(part)) {
            card.compress(Bitmap.CompressFormat.JPEG, 85, out);
        } catch (java.io.IOException e) {
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        part.renameTo(f);
    }

    private static final Paint DITHER = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);

    /**
     * A photo (or an app's card) has no transparency: 16 bits a pixel (RGB_565) hold it in half the memory. Dithered on
     * the way down, so soft gradients don't turn into bands.
     */
    static Bitmap opaque(Bitmap b) {
        if (!rowsAligned(b.getWidth())) return b;
        Bitmap o = Bitmap.createBitmap(b.getWidth(), b.getHeight(), Bitmap.Config.RGB_565);
        new Canvas(o).drawBitmap(b, 0, 0, DITHER);
        b.recycle();
        return o;
    }

    /**
     * A 16-bit image with an odd width has rows of an odd number of 2-byte pixels, not a multiple of 4 bytes: some old
     * TVs' GPUs (a weak TCL, Android 9, at 720p) read each row a little off and draw it slanted. Those stay 32-bit.
     */
    private static boolean rowsAligned(int width) {
        return (width & 1) == 0;
    }

    /** A 16-bit image just decoded (a saved card or banner): as 32-bit when its width is odd (rowsAligned). */
    static Bitmap aligned(Bitmap b) {
        if (b == null || b.getConfig() != Bitmap.Config.RGB_565 || rowsAligned(b.getWidth())) return b;
        Bitmap full = b.copy(Bitmap.Config.ARGB_8888, false);
        if (full == null) return b;
        b.recycle();
        return full;
    }

    /** Copies it to GPU memory (cheap to draw; the process doesn't keep the pixels). */
    /**
     * A title's logo for the cards: at most 480 wide, and white when it is dark (a black logo would vanish on the
     * photo's dark bottom); colored and light logos stay as they are.
     */
    static Bitmap logo(Bitmap src) {
        float scale = Math.min(1f, 480f / src.getWidth());
        Bitmap b = Bitmap.createBitmap(Math.max(1, Math.round(src.getWidth() * scale)),
                Math.max(1, Math.round(src.getHeight() * scale)), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.scale(scale, scale);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        if (inkLuminance(src) < 0.3f) {
            p.setColorFilter(new android.graphics.PorterDuffColorFilter(0xFFFFFFFF, android.graphics.PorterDuff.Mode.SRC_IN));
        }
        c.drawBitmap(src, 0, 0, p);
        src.recycle();
        return gpu(b);
    }

    /** How light the logo's visible pixels are on average (0 = black, 1 = white), from a sample of them. */
    private static float inkLuminance(Bitmap b) {
        double sum = 0, weight = 0;
        int stepX = Math.max(1, b.getWidth() / 64), stepY = Math.max(1, b.getHeight() / 32);
        for (int y = 0; y < b.getHeight(); y += stepY) {
            for (int x = 0; x < b.getWidth(); x += stepX) {
                int px = b.getPixel(x, y), a = px >>> 24;
                if (a < 40) continue;
                sum += a * (0.2126 * ((px >> 16) & 255) + 0.7152 * ((px >> 8) & 255) + 0.0722 * (px & 255)) / 255;
                weight += a;
            }
        }
        return weight == 0 ? 1 : (float) (sum / weight);
    }

    static Bitmap gpu(Bitmap b) {
        Bitmap g = b.copy(Bitmap.Config.HARDWARE, false);
        if (g == null) return b;
        b.recycle();
        return g;
    }

    /** Blends with a near-black (the darkest gray of Apple's dark-mode backgrounds), keeping the photo's tint. */
    private static int[] darken(int[] px, float amount) {
        float keep = 1 - amount, base = 11 * amount;
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            int r = Math.round(((p >> 16) & 255) * keep + base);
            int g = Math.round(((p >> 8) & 255) * keep + base);
            int b = Math.round((p & 255) * keep + base + 2 * amount);
            px[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        return px;
    }

    private static int[] whiten(int[] px, float amount) {
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            int r = Math.round(((p >> 16) & 255) * (1 - amount) + 255 * amount);
            int g = Math.round(((p >> 8) & 255) * (1 - amount) + 255 * amount);
            int b = Math.round((p & 255) * (1 - amount) + 255 * amount);
            px[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        return px;
    }

    /** Halved at each step (a true average, no aliasing), then blurred (box passes of radius r). */
    private static int[] reduce(Bitmap full, int w, int h, int radius, int passes) {
        Bitmap b = full;
        while (b.getWidth() / 2 >= w * 1.5f) {
            Bitmap half = Bitmap.createScaledBitmap(b, b.getWidth() / 2, b.getHeight() / 2, true);
            if (b != full) b.recycle();
            b = half;
        }
        Bitmap small = Bitmap.createScaledBitmap(b, w, h, true);
        if (b != full) b.recycle();
        int[] px = new int[w * h];
        small.getPixels(px, 0, w, 0, 0, w, h);
        small.recycle();
        int[] tmp = new int[px.length];
        for (int pass = 0; pass < passes; pass++) {
            box(px, tmp, w, h, radius, true);
            box(tmp, px, w, h, radius, false);
        }
        return px;
    }

    /** Moving average of radius r in one direction, repeating the edge pixels. */
    private static void box(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        int n = horizontal ? w : h, lines = horizontal ? h : w, step = horizontal ? 1 : w, lineStep = horizontal ? w : 1;
        float k = 1f / (2 * r + 1);
        for (int line = 0; line < lines; line++) {
            int base = line * lineStep;
            float sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int p = in[base + Math.max(0, Math.min(n - 1, i)) * step];
                sr += (p >> 16) & 255;
                sg += (p >> 8) & 255;
                sb += p & 255;
            }
            for (int i = 0; i < n; i++) {
                out[base + i * step] = 0xFF000000 | (Math.round(sr * k) << 16) | (Math.round(sg * k) << 8) | Math.round(sb * k);
                int add = in[base + Math.min(n - 1, i + r + 1) * step], sub = in[base + Math.max(0, i - r) * step];
                sr += ((add >> 16) & 255) - ((sub >> 16) & 255);
                sg += ((add >> 8) & 255) - ((sub >> 8) & 255);
                sb += (add & 255) - (sub & 255);
            }
        }
    }

    /** Average luminance (0 to 1) of a rectangle given as fractions of the image, by sampling. */
    private static float luminance(Bitmap b, float x0, float y0, float x1, float y1) {
        float sum = 0;
        int count = 0;
        for (int j = 0; j < 24; j++) {
            for (int i = 0; i < 32; i++) {
                int x = Math.round((x0 + (x1 - x0) * (i + 0.5f) / 32) * (b.getWidth() - 1));
                int y = Math.round((y0 + (y1 - y0) * (j + 0.5f) / 24) * (b.getHeight() - 1));
                int p = b.getPixel(x, y);
                sum += (0.2126f * ((p >> 16) & 255) + 0.7152f * ((p >> 8) & 255) + 0.0722f * (p & 255)) / 255f;
                count++;
            }
        }
        return sum / count;
    }

    private static int black(float a) {
        return Math.round(Math.max(0, Math.min(1, a)) * 255) << 24;
    }
}
