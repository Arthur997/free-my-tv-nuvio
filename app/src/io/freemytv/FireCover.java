package io.freemytv;

import android.accessibilityservice.AccessibilityService;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.view.PixelCopy;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Fire TV: after Home, Android holds our home screen's start for 5 s (FireTv) and Amazon's launcher shows meanwhile.
 * A picture of our home screen covers it at once: FireHomeActivity takes it (in :home), and the accessibility service
 * (in :keys) shows it in an overlay until ours is on screen. The keys pressed meanwhile are dropped (FireTv.onKey):
 * they'd move through Amazon's launcher underneath. The decoded picture (RGB 565, 4 MB at 1080p) stays in :keys, so
 * the next cover is instant.
 */
final class FireCover {
    private FireCover() {}

    private static final String FILE = "fire-home.jpg";
    /** The picture is taken once the home screen has settled on screen, at most this often (it costs a JPEG). */
    private static final long CAPTURE_AFTER_MS = 3_000, CAPTURE_EVERY_MS = 120_000;
    /** Ours never came: the cover goes anyway. Android's hold is 5 s, plus a cold start of :home. */
    private static final long MAX_COVER_MS = 10_000;
    /** Ours is up: its first frame is drawn before the cover fades out. */
    private static final long SETTLE_MS = 400, FADE_MS = 150;

    // MARK: - The picture (:home)

    private static long capturedAt;
    private static Handler worker;

    /** Takes the picture a moment after the home screen comes to the front, if it's still there by then. */
    static void captureSoon(Activity a) {
        View decor = a.getWindow().getDecorView();
        decor.postDelayed(() -> {
            long now = SystemClock.uptimeMillis();
            if (a.isFinishing() || !a.hasWindowFocus() || (capturedAt != 0 && now - capturedAt < CAPTURE_EVERY_MS)) return;
            capturedAt = now;
            capture(a, decor.getWidth(), decor.getHeight());
        }, CAPTURE_AFTER_MS);
    }

    private static void capture(Activity a, int width, int height) {
        if (width <= 0 || height <= 0) return;
        Bitmap picture = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        File dir = a.getFilesDir();
        PixelCopy.request(a.getWindow(), picture, result -> {
            if (result == PixelCopy.SUCCESS) save(dir, picture);
            picture.recycle();
        }, worker());
    }

    /** Written aside and renamed: :keys never reads half a file. */
    private static void save(File dir, Bitmap picture) {
        File tmp = new File(dir, FILE + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            picture.compress(Bitmap.CompressFormat.JPEG, 85, out);
        } catch (IOException e) {
            AppLog.w("fire tv cover: " + e.getMessage());
            return;
        }
        if (!tmp.renameTo(new File(dir, FILE))) AppLog.w("fire tv cover: could not save the picture");
    }

    private static synchronized Handler worker() {
        if (worker == null) {
            HandlerThread thread = new HandlerThread("fire-cover");
            thread.start();
            worker = new Handler(thread.getLooper());
        }
        return worker;
    }

    // MARK: - The cover (:keys, main thread)

    private static ImageView cover;
    private static Bitmap shown;
    private static long shownModified;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Runnable HIDE = FireCover::hide;

    static boolean showing() {
        return cover != null;
    }

    /** Covers the screen with the picture, if there is one. */
    static void show(AccessibilityService service) {
        if (cover != null) return;
        Bitmap picture = picture(service);
        if (picture == null) return;
        WindowManager wm = service.getSystemService(WindowManager.class);
        if (wm == null) return;
        ImageView view = new ImageView(service);
        view.setScaleType(ImageView.ScaleType.FIT_XY);
        view.setImageBitmap(picture);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_FULLSCREEN,
                PixelFormat.OPAQUE);
        try {
            wm.addView(view, lp);
        } catch (RuntimeException e) {
            AppLog.w("fire tv cover: " + e.getMessage());
            return;
        }
        cover = view;
        MAIN.postDelayed(HIDE, MAX_COVER_MS);
        AppLog.w("fire tv cover: up");
    }

    /** Ours is on screen: the cover goes once its first frame is drawn. */
    static void ourHomeShown() {
        if (cover == null) return;
        MAIN.removeCallbacks(HIDE);
        MAIN.postDelayed(HIDE, SETTLE_MS);
    }

    private static void hide() {
        MAIN.removeCallbacks(HIDE);
        ImageView view = cover;
        if (view == null) return;
        cover = null;
        AppLog.w("fire tv cover: down");
        view.animate().alpha(0f).setDuration(FADE_MS).withEndAction(() -> {
            WindowManager wm = view.getContext().getSystemService(WindowManager.class);
            try {
                if (wm != null) wm.removeView(view);
            } catch (RuntimeException ignored) {
                // Already gone with the service
            }
        });
    }

    /** The picture, decoded again only when FireHomeActivity took a newer one. */
    private static Bitmap picture(AccessibilityService service) {
        File file = new File(service.getFilesDir(), FILE);
        long modified = file.lastModified();
        if (modified == 0) return null;
        if (shown == null || modified != shownModified) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap decoded = BitmapFactory.decodeFile(file.getPath(), options);
            if (decoded == null) return shown;
            shown = decoded;
            shownModified = modified;
        }
        return shown;
    }
}
