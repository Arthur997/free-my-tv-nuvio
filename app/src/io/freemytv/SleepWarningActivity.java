package io.freemytv;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;

/**
 * The sleep timer's last minute, over whatever is on screen: when the TV turns off, more time (15, 30 or 60 minutes
 * from now) or no timer. Opened by the TV's shell, which runs the timer (HomeMode.SLEEP_SCRIPT), so it shows up even
 * after the TV force-stopped Free My TV. Left alone, it goes away when the TV turns off. In the Control Center's
 * look over other apps (ControlActivity): solid, at the top in the middle, dropping in from above.
 */
public class SleepWarningActivity extends Activity {
    private Panel panel;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (SleepTimer.endsAt(this) == 0) {   // turned off or moved while the shell was on its way
            finish();
            return;
        }
        Ui.init(this);
        panel = new Panel(this);
        setContentView(panel);
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

    private static final class Panel extends View implements Choreographer.FrameCallback {
        /** The buttons: minutes from now, 0 = no timer. */
        private static final int[] CHOICES = {15, 30, 60, 0};
        // In the 1920 × 1080 space; concentric corners: panel radius = button radius + padding.
        private static final float TOP = 48, PAD = 28, BUTTON_W = 140, BUTTON_H = 64, GAP = 12, BUTTON_RADIUS = 22;
        private static final int INK = 0xFF1D1D1F, DIM = 0xFF6E6E73, WHITE = 0xFFF5F5F7;
        private final Activity activity;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Spring appear = new Spring(0.32f, 0.86f, 0);
        private final Spring[] focus = new Spring[CHOICES.length];
        private final RectF panelRect = new RectF(), shadowRect = new RectF();
        private final RectF[] buttons = new RectF[CHOICES.length];
        private final String[] labels = new String[CHOICES.length];
        private final Shadows shadows = new Shadows();
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Paint title, detail, label;
        private float k = 1, labelHalfCap;
        private int index;
        private boolean night = true, running, frameQueued, okDown, backDown, leaving;
        private long lastNanos;
        private String countdown = "";
        private final Runnable second = new Runnable() {
            @Override public void run() {
                updateCountdown();
                handler.postDelayed(this, 1000);
            }
        };

        Panel(Activity a) {
            super(a);
            activity = a;
            for (int i = 0; i < CHOICES.length; i++) {
                focus[i] = Spring.focus(i == 0 ? 1 : 0);
                buttons[i] = new RectF();
                labels[i] = CHOICES[i] == 0 ? Ui.tr("Cancelar", "Cancel", "Cancelar")
                        : Ui.trf(Ui.tr("+{n} min", "+{n} min", "+{n} min"), "n", CHOICES[i]);
            }
            setFocusable(true);
        }

        void resume() {
            running = true;
            night = HomeData.get(activity).isNight(System.currentTimeMillis());
            appear.target = 1;
            handler.post(second);
            wake();
        }

        void pause() {
            running = false;
            handler.removeCallbacks(second);
            Choreographer.getInstance().removeFrameCallback(this);
            frameQueued = false;
        }

        @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
            k = w / 1920f;
            title = Ui.text(Ui.semibold, 30 * k, INK);
            detail = Ui.text(Ui.medium, 22 * k, DIM);
            label = Ui.numbers(Ui.semibold, 24 * k, INK);
            label.setTextAlign(Paint.Align.CENTER);
            labelHalfCap = Ui.halfCap(label);
            shadow.setMaskFilter(new android.graphics.BlurMaskFilter(26 * k, android.graphics.BlurMaskFilter.Blur.NORMAL));
            float pad = PAD * k, bw = BUTTON_W * k, gap = GAP * k;
            float width = 2 * pad + CHOICES.length * bw + (CHOICES.length - 1) * gap, left = (w - width) / 2, top = TOP * k;
            panelRect.set(left, top, left + width, top + pad + 96 * k + BUTTON_H * k + pad);
            float x = left + pad, y = panelRect.bottom - pad - BUTTON_H * k;
            for (RectF b : buttons) {
                b.set(x, y, x + bw, y + BUTTON_H * k);
                x += bw + gap;
            }
        }

        /** "Turns off in 42 s"; at the end the TV turns off, and this goes with it. */
        private void updateCountdown() {
            long left = SleepTimer.endsAt(activity) - System.currentTimeMillis();
            if (left <= 0) {
                dismiss();
                return;
            }
            countdown = Ui.trf(Ui.tr("A TV desliga em {n} s", "The TV turns off in {n} s", "La TV se apaga en {n} s"),
                    "n", (left + 999) / 1000);
            invalidate();
        }

        boolean onKey(KeyEvent e) {
            boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (down) move(e.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    // On release, and only for a press that began here (as the Control Center).
                    if (down) {
                        if (e.getRepeatCount() == 0) okDown = true;
                    } else {
                        if (okDown && !e.isCanceled()) choose(CHOICES[index]);
                        okDown = false;
                    }
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    // Only the notice goes: the timer keeps running.
                    if (down) {
                        if (e.getRepeatCount() == 0) backDown = true;
                    } else {
                        if (backDown && !e.isCanceled()) dismiss();
                        backDown = false;
                    }
                    return true;
                default:
                    return false;
            }
        }

        private void move(int dx) {
            int next = Math.max(0, Math.min(CHOICES.length - 1, index + dx));
            if (next == index) return;
            focus[index].target = 0;
            index = next;
            focus[index].target = 1;
            announceForAccessibility(labels[index]);
            wake();
        }

        /** More time from now, or no timer; said in a toast, as the Control Center says it. */
        private void choose(int minutes) {
            SleepTimer.set(activity, minutes);
            String said = minutes == 0 ? Ui.tr("Timer desligado. A TV fica ligada.", "Sleep timer off. The TV stays on.",
                    "Temporizador apagado. La TV sigue encendida.")
                    : Ui.trf(Ui.tr("A TV desliga em {n} min, às {time}.", "The TV turns off in {n} min, at {time}.",
                            "La TV se apaga en {n} min, a las {time}."), "n", minutes, "time", Ui.clock(SleepTimer.endsAt(activity)));
            android.widget.Toast.makeText(activity, said, android.widget.Toast.LENGTH_SHORT).show();
            dismiss();
        }

        /** Back up off the screen, then gone. */
        private void dismiss() {
            if (leaving) return;
            leaving = true;
            appear.target = 0;
            wake();
        }

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
            boolean moving = appear.step(dt);
            for (Spring s : focus) moving |= s.step(dt);
            invalidate();
            if (leaving && appear.value < 0.02f) {
                activity.finish();
                activity.overridePendingTransition(0, 0);
                return;
            }
            if (moving) {
                frameQueued = true;
                Choreographer.getInstance().postFrameCallback(this);
            }
        }

        @Override protected void onDraw(Canvas c) {
            if (title == null) return;
            float dk = night ? 1 : 0, pad = PAD * k, buttonRadius = BUTTON_RADIUS * k, radius = buttonRadius + pad;
            c.save();
            c.translate(0, -(1 - appear.value) * (panelRect.bottom + 140 * k));
            shadowRect.set(panelRect);
            shadowRect.offset(0, 12 * k);
            shadow.setColor(Ui.alpha(0xFF000000, 0.16f + 0.24f * dk));
            shadows.draw(c, shadowRect, radius, 26 * k, shadow);
            fill.setColor(night ? 0xFF1C1C1E : 0xFFF5F5F7);
            c.drawRoundRect(panelRect, radius, radius, fill);
            int ink = night ? WHITE : INK;
            title.setColor(ink);
            c.drawText(countdown, panelRect.left + pad, panelRect.top + pad + 34 * k, title);
            detail.setColor(night ? 0xFF98989D : DIM);
            c.drawText(Ui.tr("Mais tempo, ou cancele o timer.", "More time, or cancel the timer.", "Más tiempo, o cancela el temporizador."),
                    panelRect.left + pad, panelRect.top + pad + 72 * k, detail);
            for (int i = 0; i < buttons.length; i++) {
                RectF b = buttons[i];
                float f = Ui.clamp(focus[i].value), s = 1 + 0.04f * f;
                c.save();
                c.scale(s, s, b.centerX(), b.centerY());
                fill.setColor(ControlCenter.blend(Ui.alpha(ink, 0.1f), ink, f));
                c.drawRoundRect(b, buttonRadius, buttonRadius, fill);
                label.setColor(ControlCenter.blend(ink, ControlCenter.contrast(ink), f));
                c.drawText(labels[i], b.centerX(), b.centerY() + labelHalfCap, label);
                c.restore();
            }
            c.restore();
        }
    }
}
