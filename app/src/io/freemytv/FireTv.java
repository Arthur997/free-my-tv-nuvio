package io.freemytv;

import android.Manifest;
import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * Fire TV (Fire OS 7, Android 9 tested): Fire OS ignores the default home screen (set-home-activity answers Success,
 * yet Home still opens Amazon's launcher), handles Home before any accessibility service sees it, and its launcher
 * can't go off (a protected package). So in Free My TV mode (FireTvMode) the accessibility service (RemoteKeyService,
 * in :keys) opens ours whenever Amazon's comes up. After Home, Android holds that start for 5 s (app switches):
 * FireCover hides the wait behind a picture of our home screen. To put it up before Amazon's home shows, Home is read
 * in the system log (READ_LOGS, granted with FireTvMode.GRANT): Fire OS logs the start of Amazon's home ~250 ms before
 * it's drawn. Without that permission, or on Back from an app, a start or Alexa, the window event does it, a little
 * later.
 */
final class FireTv {
    private FireTv() {}

    static final boolean IS = "Amazon".equalsIgnoreCase(Build.MANUFACTURER) || "Amazon".equalsIgnoreCase(Build.BRAND);
    /** The home screen to open by name: on Fire TV the one that keeps the remote's focus over Amazon's launcher. */
    static final Class<? extends HomeActivity> HOME = IS ? FireHomeActivity.class : HomeActivity.class;
    static final String LAUNCHER = "com.amazon.tv.launcher";
    /** Amazon's home activity ("com.amazon.tv.launcher.ui.HomeActivity_vNext"), whatever suffix a version gives it. */
    private static final String LAUNCHER_HOME = LAUNCHER + ".ui.HomeActivity";
    private static final String OUR_HOME = FireHomeActivity.class.getName();
    /** Amazon's home reports itself after the log line: one opening of ours is enough. */
    private static final long DEBOUNCE_MS = 1_500;

    private static volatile long openedAt;
    private static Process log;

    /** Home was pressed (or Alexa went home): the cover goes up, and ours is asked for (back at its start). */
    private static void onHomePressed(AccessibilityService service) {
        FireCover.show(service);
        if (SystemClock.uptimeMillis() - openedAt >= DEBOUNCE_MS) openHome(service, true);
    }

    static void onWindow(AccessibilityService service, AccessibilityEvent e) {
        if (!IS || e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = e.getPackageName(), cls = e.getClassName();
        if (pkg == null || cls == null) return;
        String name = cls.toString();
        if (OUR_HOME.equals(name)) {
            FireCover.ourHomeShown();
        } else if (LAUNCHER.contentEquals(pkg) && name.startsWith(LAUNCHER_HOME)) {
            FireCover.show(service);
            if (SystemClock.uptimeMillis() - openedAt >= DEBOUNCE_MS) openHome(service, false);
        }
    }

    /** While the cover is up, the keys go nowhere (Amazon's launcher is underneath); volume and power still work. */
    static boolean onKey(KeyEvent e) {
        if (!IS || !FireCover.showing()) return false;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_VOLUME_UP: case KeyEvent.KEYCODE_VOLUME_DOWN: case KeyEvent.KEYCODE_VOLUME_MUTE:
            case KeyEvent.KEYCODE_POWER: case KeyEvent.KEYCODE_SLEEP: case KeyEvent.KEYCODE_WAKEUP:
                return false;
            default:
                return true;
        }
    }

    /**
     * Follows the system log for "START u0 {act=MAIN cat=[...HOME] ... cmp=com.amazon.tv.launcher/...}". logcat filters
     * by tag itself, so this thread sees only ActivityManager's lines.
     */
    static void watchLog(AccessibilityService service) {
        if (!IS || log != null) return;
        if (service.checkSelfPermission(Manifest.permission.READ_LOGS) != PackageManager.PERMISSION_GRANTED) {
            AppLog.w("fire tv: no READ_LOGS, Home is seen once Amazon's home shows");
            return;
        }
        Process process;
        try {
            process = new ProcessBuilder("logcat", "-b", "system", "-v", "brief", "-T", "1", "ActivityManager:I", "*:S")
                    .redirectErrorStream(true).start();
        } catch (IOException e) {
            AppLog.w("fire tv: no system log (" + e.getMessage() + ")");
            return;
        }
        log = process;
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                in.readLine();   // the last line from before: not a press now
                for (String line; (line = in.readLine()) != null; ) {
                    if (line.contains("START u0") && line.contains("android.intent.category.HOME")
                            && line.contains("cmp=" + LAUNCHER + "/")) {
                        main.post(() -> onHomePressed(service));
                    }
                }
            } catch (IOException ignored) {
                // The service stopped (stopLog)
            }
        }, "fire-home-log").start();
    }

    static void stopLog() {
        if (log == null) return;
        log.destroy();
        log = null;
    }

    /** `reset`: a Home press, ours goes back to its start; otherwise it comes back as it was. */
    private static void openHome(Context c, boolean reset) {
        openedAt = SystemClock.uptimeMillis();
        Intent home = new Intent(c, HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (!reset) home.putExtra(HomeActivity.RESURFACE, true);
        c.startActivity(home);
    }
}
