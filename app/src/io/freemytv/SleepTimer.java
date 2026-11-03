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
 * (the home screen and the main process both read it) and an alarm wakes the main process then (SleepTimer.Fire),
 * which puts the TV to sleep through its own debugging connection (HomeMode.sleepNow). A restart ends it.
 */
final class SleepTimer {
    private SleepTimer() {}

    /** The choices, in minutes; 0 = no timer. */
    static final int[] OPTIONS = {0, 15, 30, 45, 60, 90};

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
        AppLog.w(minutes > 0 ? "sleep timer: " + minutes + " min" : "sleep timer: off");
        if (at == 0) return;
        // Exact when the TV lets us (Android 12+ asks for it); otherwise Android may run it a little late.
        if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, fire);
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, fire);
        }
    }

    /** The alarm, in the main process: the timer is over, the TV goes to sleep. */
    public static class Fire extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent intent) {
            long[] v = read(c);
            // A newer choice replaced it (the file holds a later time) or turned it off: nothing to do yet.
            if (v[0] == 0 || v[0] > System.currentTimeMillis() + 60_000L) return;
            set(c, 0);
            ModeJob.schedule(c, ModeJob.SLEEP, null, 0);
        }
    }
}
