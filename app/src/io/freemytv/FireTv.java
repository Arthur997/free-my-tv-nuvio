package io.freemytv;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Fire TV (Fire OS 7, Android 9 tested): Fire OS ignores the default home screen (set-home-activity answers Success,
 * yet Home and the HOME intent still open Amazon's launcher), and its launcher mustn't go off. So in Free My TV mode
 * (FireTvMode) the accessibility service (RemoteKeyService, in :keys) takes the Home button and opens ours. Letting the
 * press through and covering Amazon's home afterwards costs 5 s: after a Home press Android holds back activity starts
 * from apps in the background (app switches). Amazon's home still comes up on a start, on Back from an app or by
 * Alexa: then ours opens over it, as it was.
 */
final class FireTv {
    private FireTv() {}

    static final boolean IS = "Amazon".equalsIgnoreCase(Build.MANUFACTURER) || "Amazon".equalsIgnoreCase(Build.BRAND);
    /** The home screen to open by name: on Fire TV the one that keeps the remote's focus over Amazon's launcher. */
    static final Class<? extends HomeActivity> HOME = IS ? FireHomeActivity.class : HomeActivity.class;
    static final String LAUNCHER = "com.amazon.tv.launcher";
    /** Amazon's home activity ("com.amazon.tv.launcher.ui.HomeActivity_vNext"), whatever suffix a version gives it. */
    private static final String LAUNCHER_HOME = LAUNCHER + ".ui.HomeActivity";
    /** What holding Home opens on Fire OS: the quick menu (sleep, settings, mirroring). */
    private static final String QUICK_MENU = "com.amazon.device.settings.action.SHOW_HUD";
    /** Held this long, Home is a long press (Android's long-press timeout). */
    private static final long LONG_PRESS_MS = 500;
    /** Amazon's home can report itself twice in a row: one opening of ours is enough. */
    private static final long DEBOUNCE_MS = 700;

    private static volatile long openedAt;

    /**
     * Home is ours, down to the release: a short press opens our home screen (back at its start), a long one Amazon's
     * quick menu. The remote sends no repeats to the key filter, so the press is timed from its down to its up.
     */
    static boolean onKey(Context c, KeyEvent e) {
        if (!IS || e.getKeyCode() != KeyEvent.KEYCODE_HOME) return false;
        if (e.getAction() == KeyEvent.ACTION_UP && !e.isCanceled()) {
            if (e.getEventTime() - e.getDownTime() >= LONG_PRESS_MS) openQuickMenu(c);
            else openHome(c, true);
        }
        return true;
    }

    static void onWindow(Context c, AccessibilityEvent e) {
        if (!IS || e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = e.getPackageName(), cls = e.getClassName();
        if (pkg == null || cls == null || !LAUNCHER.contentEquals(pkg) || !cls.toString().startsWith(LAUNCHER_HOME)) return;
        long now = SystemClock.uptimeMillis();
        if (now - openedAt < DEBOUNCE_MS) return;
        openHome(c, false);
    }

    /** `reset`: a Home press, ours goes back to its start; otherwise it comes back as it was. */
    private static void openHome(Context c, boolean reset) {
        openedAt = SystemClock.uptimeMillis();
        Intent home = new Intent(c, HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (!reset) home.putExtra(HomeActivity.RESURFACE, true);
        c.startActivity(home);
    }

    private static void openQuickMenu(Context c) {
        try {
            c.startActivity(new Intent(QUICK_MENU).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            AppLog.w("fire tv: no quick menu (" + e.getMessage() + ")");
        }
    }
}
