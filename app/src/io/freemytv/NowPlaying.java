package io.freemytv;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What any app is playing (title, artist or channel, the app), from the media session each app publishes for the remote's
 * play/pause: one way for music, videos, radio and live streams alike (Settings > Home content > Now playing, off by
 * default). Reading the sessions needs this notification listener allowed (HomeMode does it through the TV's ADB while
 * the option is on, in Free My TV mode); it never reads a notification and asks the system to unbind it at once, so no
 * process stays up for it.
 */
public class NowPlaying extends NotificationListenerService {

    @Override public void onListenerConnected() {
        requestUnbind();
    }

    private static final float[] STILL = {0.55f, 1, 0.75f}, PERIOD_MS = {170, 230, 200};

    /** The bars' width for text of this cap height (drawBars). */
    static float barsWidth(float cap) {
        return cap * 1.12f;
    }

    /** Three level bars before the title, standing on its baseline, `cap` tall: still, or bouncing like a level meter. */
    static void drawBars(Canvas c, float x, float base, float cap, Paint p, boolean moving, RectF r) {
        long t = SystemClock.uptimeMillis();
        for (int i = 0; i < 3; i++) {
            float level = moving ? 0.3f + 0.7f * Math.abs((float) Math.sin(t / PERIOD_MS[i] + i * 1.3f)) : STILL[i];
            float bx = x + i * cap * 0.42f;
            r.set(bx, base - cap * level, bx + cap * 0.28f, base);
            c.drawRoundRect(r, cap * 0.14f, cap * 0.14f, p);
        }
    }

    static ComponentName component(Context c) {
        return new ComponentName(c, NowPlaying.class);
    }

    /** Asked once per process when the option is on but Android doesn't let us read the sessions yet. */
    private static boolean asked;

    /** A screen's watch: start() when it shows (and on its periodic tick), stop() when it's hidden. */
    static final class Watch implements MediaSessionManager.OnActiveSessionsChangedListener {
        private final Context context;
        private final Runnable changed;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final List<MediaController> controllers = new ArrayList<>();
        private final Map<String, String> labels = new HashMap<>();
        private final MediaController.Callback callback = new MediaController.Callback() {
            @Override public void onPlaybackStateChanged(PlaybackState state) { update(); }
            @Override public void onMetadataChanged(MediaMetadata metadata) { update(); }
            @Override public void onSessionDestroyed() { update(); }
        };
        private boolean on;
        /** "Title · Artist · App" while something plays, else null. */
        volatile String text;
        /** The title alone (the app's package when it gives none): the line's other parts can change every second. */
        volatile String title;

        Watch(Context c, Runnable changed) {
            context = c;
            this.changed = changed;
        }

        /** Starts watching, if the option is on and Android allows it (a no-op once watching). */
        void start() {
            if (on || !Prefs.nowPlaying()) return;
            MediaSessionManager m = context.getSystemService(MediaSessionManager.class);
            if (m == null) return;
            try {
                m.addOnActiveSessionsChangedListener(this, component(context), handler);
                on = true;
                onActiveSessionsChanged(m.getActiveSessions(component(context)));
            } catch (SecurityException e) {
                if (!asked) {   // on, but not allowed (it was taken back by Original): asked once
                    asked = true;
                    HomeMode.requestNowPlaying(context, true);
                }
            }
        }

        void stop() {
            if (!on) return;
            on = false;
            MediaSessionManager m = context.getSystemService(MediaSessionManager.class);
            if (m != null) m.removeOnActiveSessionsChangedListener(this);
            onActiveSessionsChanged(null);
        }

        @Override public void onActiveSessionsChanged(List<MediaController> sessions) {
            for (MediaController mc : controllers) mc.unregisterCallback(callback);
            controllers.clear();
            if (sessions != null && on) {
                for (MediaController mc : sessions) {
                    mc.registerCallback(callback, handler);
                    controllers.add(mc);
                }
            }
            update();
        }

        private void update() {
            String now = null, nowTitle = null;
            for (MediaController mc : controllers) {   // most recent first
                PlaybackState s = mc.getPlaybackState();
                int state = s == null ? PlaybackState.STATE_NONE : s.getState();
                // A stream that buffers is still playing: the line doesn't blink back.
                if (state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING
                        || state == PlaybackState.STATE_CONNECTING) {
                    now = describe(mc);
                    MediaMetadata m = mc.getMetadata();
                    CharSequence t = m == null ? null : m.getDescription().getTitle();
                    nowTitle = now == null ? null : TextUtils.isEmpty(t) ? mc.getPackageName() : t.toString().trim();
                    break;
                }
            }
            if (Objects.equals(now, text)) return;
            title = nowTitle;
            text = now;
            changed.run();
        }

        /** "Title · Artist · App": the parts the app gives, then its name; null when there's none of them. */
        private String describe(MediaController mc) {
            List<String> parts = new ArrayList<>();
            MediaMetadata m = mc.getMetadata();
            MediaDescription d = m == null ? null : m.getDescription();
            if (d != null && !TextUtils.isEmpty(d.getTitle())) parts.add(d.getTitle().toString().trim());
            if (d != null && !TextUtils.isEmpty(d.getSubtitle())) parts.add(d.getSubtitle().toString().trim());
            String app = labels.get(mc.getPackageName());
            if (app == null) {
                try {
                    PackageManager pm = context.getPackageManager();
                    app = pm.getApplicationLabel(pm.getApplicationInfo(mc.getPackageName(), 0)).toString();
                } catch (PackageManager.NameNotFoundException e) {
                    app = "";   // an app hidden from us (Android 11+ package visibility): no name to show
                }
                labels.put(mc.getPackageName(), app);
            }
            if (!app.isEmpty() && !parts.contains(app)) parts.add(app);
            return parts.isEmpty() ? null : TextUtils.join(" · ", parts);
        }
    }
}
