package io.freemytv;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.PersistableBundle;

/**
 * Runs the mode switches in the background (HomeMode). As a job, the system keeps the app's main process running until
 * the switch finishes, even with the screen showing something else.
 */
public class ModeJob extends JobService {
    static final String APPLY = "apply", VERIFY = "verify", REVERT = "revert", MEMORY = "memory", REMOTE = "remote",
            DREAM = "dream", FREE_MEMORY = "freememory", TRIM = "trim", CLEAR_CACHE = "clearcache",
            FORCE_STOP = "forcestop", APP_STATE = "appstate", SLEEP = "sleep";

    @Override public boolean onStartJob(JobParameters params) {
        PersistableBundle extras = params.getExtras();
        final String action = extras.getString("action", "");
        final String mode = extras.getString("mode", "");
        final Context app = getApplicationContext();
        new Thread(() -> {
            try {
                switch (action) {
                    case APPLY: HomeMode.applyNow(app, mode); break;
                    case VERIFY: HomeMode.verifyNow(app); break;
                    case REVERT: HomeMode.revertHomeNow(app); break;
                    case MEMORY: HomeMode.measureMemoryNow(app, mode); break;
                    case REMOTE: HomeMode.ensureRemoteServiceNow(app); break;
                    case DREAM: HomeMode.screensaverNow(app, "own".equals(mode)); break;
                    case FREE_MEMORY: HomeMode.freeMemoryNow(app); break;
                    case TRIM: HomeMode.trimBackgroundNow(app); break;
                    case FORCE_STOP: HomeMode.forceStopNow(app, appOf(mode), nameOf(mode)); break;
                    case CLEAR_CACHE: HomeMode.clearCacheNow(app, appOf(mode), nameOf(mode)); break;
                    case SLEEP: HomeMode.sleepNow(app); break;
                    case APP_STATE: HomeMode.setAppEnabledNow(app, appOf(mode), "on".equals(nameOf(mode))); break;
                    default: break;
                }
            } catch (Throwable t) {
                AppLog.w("job " + action + " failed: " + t);
            }
            jobFinished(params, false);
        }, "free-my-tv-" + action).start();
        return true;
    }

    // An app's job carries "package|name" (the name for the notice).
    private static String appOf(String target) {
        int bar = target.indexOf('|');
        return bar < 0 ? target : target.substring(0, bar);
    }

    private static String nameOf(String target) {
        int bar = target.indexOf('|');
        return bar < 0 ? target : target.substring(bar + 1);
    }

    @Override public boolean onStopJob(JobParameters params) {
        return false;
    }

    /** Schedules a job (one pending per action: a new request replaces the old one). */
    static void schedule(Context c, String action, String mode, long delayMs) {
        schedule(c, 1000 + Math.abs(action.hashCode() % 1000), action, mode, delayMs);
    }

    /** Drops the action's pending job (the sleep timer turned off). */
    static void cancel(Context c, String action) {
        JobScheduler scheduler = c.getSystemService(JobScheduler.class);
        if (scheduler != null) scheduler.cancel(1000 + Math.abs(action.hashCode() % 1000));
    }

    /** One job per target (each app turned on or off): quick requests for several apps don't replace each other. */
    static void scheduleOne(Context c, String action, String target) {
        schedule(c, 3000 + Math.abs((action + "|" + appOf(target)).hashCode() % 1000), action, target, 0);
    }

    private static void schedule(Context c, int id, String action, String mode, long delayMs) {
        JobScheduler scheduler = c.getSystemService(JobScheduler.class);
        if (scheduler == null) return;
        PersistableBundle extras = new PersistableBundle();
        extras.putString("action", action);
        extras.putString("mode", mode == null ? "" : mode);
        JobInfo.Builder job = new JobInfo.Builder(id, new ComponentName(c, ModeJob.class)).setExtras(extras);
        if (delayMs > 0) job.setMinimumLatency(delayMs);
        job.setOverrideDeadline(delayMs + 2_000);
        scheduler.schedule(job.build());
    }
}
