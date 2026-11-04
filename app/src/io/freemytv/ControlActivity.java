package io.freemytv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;

/**
 * The Control Center over whatever is on screen, opened by the remote button picked in Remote Buttons (that button
 * again closes it). The same panel as the home screen's (ControlCenter), in the middle, in the night look so it
 * reads over a video. In the :home process, translucent, out of the recent tasks itself.
 */
public class ControlActivity extends Activity {
    private Panel panel;

    static boolean open(Context c) {
        try {
            c.startActivity(new Intent(c, ControlActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (RuntimeException e) {
            AppLog.w("control center: " + e.getMessage());
            return false;
        }
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (HomeActivity.inFront && !RecentsActivity.showing) {
            // Over the home screen: its own panel, under its button in the corner, instead of this one in the middle.
            startActivity(new Intent(this, HomeActivity.class).putExtra(HomeActivity.OPEN_CONTROL, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
            finish();
            overridePendingTransition(0, 0);
            return;
        }
        Ui.init(this);
        panel = new Panel(this);
        setContentView(panel);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        finish();
    }

    @Override protected void onResume() {
        super.onResume();
        if (panel != null) panel.resume();
    }

    @Override protected void onPause() {
        if (panel != null) panel.pause();
        super.onPause();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (RemoteKeys.onKey(this, event)) return true;
        if (panel != null && panel.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    /** The panel alone (no veil over the app), growing in from the middle, and a line for what just happened under it. */
    private static final class Panel extends View implements ControlCenter.Host, Choreographer.FrameCallback {
        private static final long TOAST_MS = 4000;
        private final Activity activity;
        private final ControlCenter cc;
        private final Spring appear = new Spring(0.22f, 0.86f, 0);
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Paint toastPaint = new Paint(Paint.ANTI_ALIAS_FLAG), pillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glassPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF pill = new android.graphics.RectF();
        private float k = 1;
        private String toast = "";
        private long toastAt;
        private boolean running, frameQueued, okDown, backDown;
        private long lastNanos;
        private final Runnable minute = new Runnable() {
            @Override public void run() {
                cc.minuteTick();
                handler.postDelayed(this, 30_000);
            }
        };

        Panel(Activity a) {
            super(a);
            activity = a;
            cc = new ControlCenter(a, this);
            setFocusable(true);
        }

        void resume() {
            running = true;
            cc.open();
            appear.target = 1;
            handler.postDelayed(minute, 30_000);
            wake();
        }

        void pause() {
            running = false;
            cc.stop();
            handler.removeCallbacks(minute);
            Choreographer.getInstance().removeFrameCallback(this);
            frameQueued = false;
        }

        @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
            k = w / 1920f;
            // In the middle of the screen, over whatever is playing.
            cc.layout(k, (w + ControlCenter.width(k)) / 2f, (h - ControlCenter.height(k)) / 2f);
            toastPaint.setTypeface(Ui.medium);
            toastPaint.setTextSize(24 * k);
            toastPaint.setColor(0xE6FFFFFF);
            toastPaint.setTextAlign(Paint.Align.CENTER);
            pillPaint.setColor(0xE62C2C2E);
            glassPaint.setColor(0xD91C1C1E);
        }

        boolean onKey(KeyEvent e) {
            wake();
            boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
            int code = e.getKeyCode();
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (down) cc.move(code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : 0,
                            code == KeyEvent.KEYCODE_DPAD_UP ? -1 : code == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : 0);
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    // On release, and only for a press that began here (as the home screen).
                    if (down) {
                        if (e.getRepeatCount() == 0) okDown = true;
                    } else {
                        if (okDown && !e.isCanceled()) cc.press();
                        okDown = false;
                    }
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    if (down) {
                        if (e.getRepeatCount() == 0) backDown = true;
                    } else {
                        if (backDown && !e.isCanceled()) activity.finish();
                        backDown = false;
                    }
                    return true;
                default:
                    return false;
            }
        }

        // MARK: - ControlCenter.Host

        @Override public View view() {
            return this;
        }

        @Override public void redraw() {
            wake();
        }

        @Override public void toast(String text) {
            toast = text;
            toastAt = System.currentTimeMillis();
            invalidate();
            handler.postDelayed(this::invalidate, TOAST_MS);
        }

        @Override public void close(boolean opened) {
            activity.finish();
        }

        @Override public float dark() {
            return 1;
        }

        /** Smoked glass: over another app there's no backdrop to bend, only the picture behind the window. */
        @Override public void glass(Canvas c, android.graphics.RectF r, float radius) {
            c.drawRoundRect(r, radius, radius, glassPaint);
        }

        // MARK: - Frames

        private void wake() {
            if (!running || frameQueued) return;
            frameQueued = true;
            lastNanos = 0;
            Choreographer.getInstance().postFrameCallback(this);
        }

        @Override public void doFrame(long frameTimeNanos) {
            frameQueued = false;
            if (!running) return;
            float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.05f, (frameTimeNanos - lastNanos) / 1e9f);
            lastNanos = frameTimeNanos;
            boolean moving = appear.step(dt) | cc.step(dt);
            invalidate();
            if (moving) {
                frameQueued = true;
                Choreographer.getInstance().postFrameCallback(this);
            }
        }

        @Override protected void onDraw(Canvas c) {
            float v = appear.value, a = Ui.clamp(v), s = 0.92f + 0.08f * v;
            if (a > 0.999f) {
                cc.draw(c);   // at rest: no offscreen layer (a weak GPU feels a full-screen one)
            } else {
                int layer = c.saveLayerAlpha(cc.bounds().left - 60 * k, cc.bounds().top - 60 * k, cc.bounds().right + 60 * k,
                        cc.bounds().bottom + 80 * k, Math.round(255 * a));
                c.scale(s, s, cc.bounds().centerX(), cc.bounds().centerY());
                cc.draw(c);
                c.restoreToCount(layer);
            }
            if (!toast.isEmpty() && System.currentTimeMillis() - toastAt < TOAST_MS) {
                // On its own dark pill: with no veil, the app's picture is right behind it.
                float cx = cc.bounds().centerX(), base = cc.bounds().bottom + 56 * k, half = toastPaint.measureText(toast) / 2 + 28 * k;
                pill.set(cx - half, base - 38 * k, cx + half, base + 18 * k);
                c.drawRoundRect(pill, pill.height() / 2, pill.height() / 2, pillPaint);
                c.drawText(toast, cx, base, toastPaint);
            }
        }
    }
}
