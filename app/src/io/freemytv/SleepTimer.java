package io.freemytv;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * The sleep timer (Control Center): the TV turns itself off after 15 to 90 minutes. The time is kept in a small file
 * (the home screen and the main process both read it). The TV's own shell, through its debugging connection, waits for
 * it and turns the TV off (HomeMode.armSleepNow); an alarm a little later (SleepTimer.Fire) does it when the shell
 * didn't (HomeMode.sleepNow). A restart ends it.
 */
final class SleepTimer {
    private SleepTimer() {}

    /** The choices, in minutes; 0 = no timer. */
    static final int[] OPTIONS = {0, 15, 30, 45, 60, 90};

    /** How long after the end the alarm checks that the shell turned the TV off (it takes a few seconds). */
    private static final long FALLBACK_MS = 90_000L;

    private static File file(Context c) {
        return new File(c.getFilesDir(), "sleep-timer.txt");
    }

    /** When the TV turns off (wall clock), or 0: no timer, or it already went off. */
    static long endsAt(Context c) {
        long[] v = read(c);
        return v[0] > System.currentTimeMillis() ? v[0] : 0;
    }

    /** The minutes chosen for the timer that's running, or 0. */
    static int minutes(Context c) {
        long[] v = read(c);
        return v[0] > System.currentTimeMillis() ? (int) v[1] : 0;
    }

    /** {ends at, minutes}; {0, 0} without a timer. */
    private static long[] read(Context c) {
        try (BufferedReader r = new BufferedReader(new FileReader(file(c)))) {
            String[] parts = r.readLine().trim().split(" ");
            return new long[] {Long.parseLong(parts[0]), Long.parseLong(parts[1])};
        } catch (IOException | RuntimeException e) {
            return new long[] {0, 0};
        }
    }

    /** Starts the timer for `minutes` from now (again from now if one was running), or stops it with 0. */
    static void set(Context c, int minutes) {
        long at = minutes > 0 ? System.currentTimeMillis() + minutes * 60_000L : 0;
        File f = file(c), tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write((at + " " + minutes + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            AppLog.w("sleep timer: " + e.getMessage());
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        tmp.renameTo(f);
        AlarmManager alarms = c.getSystemService(AlarmManager.class);
        if (alarms == null) return;
        PendingIntent fire = PendingIntent.getBroadcast(c, 0, new Intent(c, Fire.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        alarms.cancel(fire);
        AppLog.w(minutes > 0 ? "sleep timer: " + minutes + " min, at " + at : "sleep timer: off");
        ModeJob.schedule(c, ModeJob.SLEEP_ARM, null, 0);
        if (at == 0) {
            ModeJob.cancel(c, ModeJob.SLEEP);
            return;
        }
        // The fallback, after the shell's turn. Two ways to wake up, in case the TV holds one back (TCL limits what
        // starts in the background): the job too. The first one checks; the other finds the timer gone (takeDue).
        at += FALLBACK_MS;
        ModeJob.schedule(c, ModeJob.SLEEP, null, at - System.currentTimeMillis());
        // Exact when the TV lets us (Android 12+ asks for it); otherwise Android may run it a little late.
        if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, fire);
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, fire);
        }
    }

    /**
     * The timer is over (within a minute): true once, and the timer is cleared. False when it was turned off or a newer
     * choice put it later.
     */
    static synchronized long takeDue(Context c) {
        long[] v = read(c);
        if (v[0] == 0 || v[0] > System.currentTimeMillis() + 60_000L) return 0;
        File f = file(c);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
        return v[0];
    }

    /** The alarm, in the main process: the timer is over, the TV goes to sleep (in a job: it may take a few seconds). */
    public static class Fire extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent intent) {
            AppLog.w("sleep timer: alarm");
            ModeJob.schedule(c, ModeJob.SLEEP, null, 0);
        }
    }
}
