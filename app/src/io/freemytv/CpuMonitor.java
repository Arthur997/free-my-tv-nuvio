package io.freemytv;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.IOException;

/**
 * How busy the TV's processor is, every 2 s while the Control Center is open. Apps can't read /proc/stat (Android 8 and
 * newer), the TV's shell can: one connection to its own debugging for as long as it's watched, one line read each time.
 */
final class CpuMonitor {
    interface Listener {
        /** On the main thread: 0..100. */
        void onCpu(int percent);
    }

    private static final long EVERY_MS = 2_000;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Thread thread;

    /** Starts reading (again from the start if it was). */
    synchronized void start(Context c, Listener listener) {
        stop();
        final Context app = c.getApplicationContext();
        thread = new Thread(() -> run(app, listener), "free-my-tv-cpu");
        thread.start();
    }

    synchronized void stop() {
        if (thread == null) return;
        thread.interrupt();
        thread = null;
    }

    private void run(Context app, Listener listener) {
        Thread self = Thread.currentThread();
        // No waiting for the TV's "Allow debugging?": the switch to Free My TV asked it already.
        try (Adb adb = Adb.connect(app, 0, null)) {
            long[] before = null;
            while (!self.isInterrupted()) {
                long[] now = read(adb);
                if (before != null && now != null && now[0] > before[0]) {
                    final int percent = (int) Math.round(100.0 * (now[1] - before[1]) / (now[0] - before[0]));
                    main.post(() -> {
                        if (!self.isInterrupted()) listener.onCpu(Math.max(0, Math.min(100, percent)));
                    });
                }
                before = now;
                SystemClock.sleep(EVERY_MS);
            }
        } catch (IOException e) {
            AppLog.w("cpu: " + e.getMessage());
        }
    }

    /** {all time, busy time} since boot, from the first line of /proc/stat ("cpu user nice system idle iowait ..."). */
    private static long[] read(Adb adb) throws IOException {
        String[] f = adb.shell("head -1 /proc/stat").trim().split("\\s+");
        if (f.length < 5 || !f[0].equals("cpu")) return null;
        long all = 0, idle = 0;
        for (int i = 1; i < f.length; i++) {
            long v;
            try { v = Long.parseLong(f[i]); } catch (NumberFormatException e) { return null; }
            if (i <= 8) all += v;               // guest time is counted in user already
            if (i == 4 || i == 5) idle += v;    // idle and iowait
        }
        return new long[] {all, all - idle};
    }
}
