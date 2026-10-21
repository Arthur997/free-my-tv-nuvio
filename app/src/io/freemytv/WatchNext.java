package io.freemytv;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.net.Uri;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * "Continue watching": the Watch Next list the TV's apps keep in Android's TV provider (Netflix, Prime Video,
 * YouTube…: each app writes its own rows, and the Google TV home screen shows them). Any app holding READ_TV_LISTINGS
 * (a runtime permission: HomeMode grants it on the switch, the home screen asks for it otherwise) can read every app's
 * rows that are marked searchable, which is the default (TvProvider, AOSP).
 */
final class WatchNext {
    private WatchNext() {}

    static final String PERMISSION = "android.permission.READ_TV_LISTINGS";
    /** The most titles on the tab: two more columns than fit on screen. */
    private static final int MAX = Recs.ON_SCREEN + 6;
    private static final String[] COLUMNS = {
        TvContract.WatchNextPrograms._ID,
        TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME,
        TvContract.WatchNextPrograms.COLUMN_TITLE,
        TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE,
        TvContract.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER,
        TvContract.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER,
        TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI,
        TvContract.WatchNextPrograms.COLUMN_POSTER_ART_ASPECT_RATIO,
        TvContract.WatchNextPrograms.COLUMN_THUMBNAIL_URI,
        TvContract.WatchNextPrograms.COLUMN_THUMBNAIL_ASPECT_RATIO,
        TvContract.WatchNextPrograms.COLUMN_INTENT_URI,
        TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS,
        TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
        TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS,
        TvContract.WatchNextPrograms.COLUMN_BROWSABLE,
    };

    static boolean allowed(Context c) {
        return c.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * What to continue, most recent first, one card per title (an app may keep a row per episode), only from the apps
     * More Settings allows and that are on the TV. Empty without the permission. Reads the provider: background only.
     */
    static List<Recs.Pick> load(Context c) {
        List<Row> rows = new ArrayList<>();
        if (!allowed(c)) return new ArrayList<>();
        ContentResolver cr = c.getContentResolver();
        // No selection and no sort: the provider refuses both to apps without full access.
        try (Cursor q = cr.query(TvContract.WatchNextPrograms.CONTENT_URI, COLUMNS, null, null, null)) {
            while (q != null && q.moveToNext()) {
                Row r = new Row(q);
                if (r.browsable) rows.add(r);
            }
        } catch (RuntimeException e) {
            AppLog.w("watch next: " + e.getMessage());
            return new ArrayList<>();
        }
        Collections.sort(rows, (a, b) -> Long.compare(b.engaged, a.engaged));
        PackageManager pm = c.getPackageManager();
        List<Recs.Pick> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Row r : rows) {
            if (out.size() >= MAX) break;
            if (r.pkg == null || r.title == null || r.intent == null || Prefs.watchNextOff(r.pkg)) continue;
            String app = appName(pm, r.pkg);
            // One card per title, even when two apps keep it (Nuvio and Nuvio Legacy): the most recent wins.
            if (app == null || !seen.add(r.title.trim().toLowerCase(java.util.Locale.ROOT))) continue;
            out.add(r.pick(app));
        }
        AppLog.w("watch next: " + rows.size() + " rows, " + out.size() + " shown");
        return out;
    }

    /** The app's name, or null when it isn't on the TV or is turned off. */
    static String appName(PackageManager pm, String pkg) {
        try {
            ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
            return info.enabled ? String.valueOf(pm.getApplicationLabel(info)) : null;
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    /** The apps with something in the list now, as {package, name} (More Settings: which ones may show). */
    static List<String[]> apps(Context c) {
        List<String[]> out = new ArrayList<>();
        if (!allowed(c)) return out;
        PackageManager pm = c.getPackageManager();
        Set<String> seen = new HashSet<>();
        try (Cursor q = c.getContentResolver().query(TvContract.WatchNextPrograms.CONTENT_URI,
                new String[] {TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME}, null, null, null)) {
            while (q != null && q.moveToNext()) {
                String pkg = q.getString(0);
                String name = pkg == null ? null : appName(pm, pkg);
                if (name != null && seen.add(pkg)) out.add(new String[] {pkg, name});
            }
        } catch (RuntimeException e) {
            AppLog.w("watch next apps: " + e.getMessage());
        }
        Collections.sort(out, (a, b) -> a[1].compareToIgnoreCase(b[1]));
        return out;
    }

    private static final class Row {
        final long id, engaged, position, duration;
        final String pkg, title, episode, season, number, intent, image;
        final boolean browsable;

        Row(Cursor q) {
            id = q.getLong(0);
            pkg = q.getString(1);
            title = q.getString(2);
            episode = q.getString(3);
            season = q.getString(4);
            number = q.getString(5);
            image = wideImage(q.getString(6), q.getInt(7), q.getString(8), q.getInt(9));
            intent = q.getString(10);
            engaged = q.getLong(11);
            position = q.getLong(12);
            duration = q.getLong(13);
            browsable = q.isNull(14) || q.getInt(14) == 1;
        }

        Recs.Pick pick(String app) {
            Recs.Pick p = new Recs.Pick();
            p.id = (int) id;
            p.kind = "watch";
            p.title = title;
            p.backdrop = image;
            p.pkg = this.pkg;
            p.app = app;
            p.intentUri = intent;
            p.detailed = true;
            p.progress = duration > 0 ? Math.max(0, Math.min(1, position / (float) duration)) : 0;
            List<String> parts = new ArrayList<>();
            parts.add(app);
            if (number != null && !number.isEmpty()) {
                parts.add((season != null && !season.isEmpty() ? Ui.tr("T", "S", "T") + season + " " : "")
                        + Ui.tr("E", "E", "E") + number);
            } else if (episode != null && !episode.isEmpty()) {
                parts.add(episode);
            }
            long left = duration - position;
            if (duration > 0 && left > 60_000) {
                long minutes = left / 60_000;
                String time = minutes >= 60 ? (minutes / 60) + " h " + (minutes % 60) + " min" : minutes + " min";
                parts.add(Ui.tr("faltam " + time, time + " left", "quedan " + time));
            }
            p.meta = String.join(" · ", parts);
            return p;
        }
    }

    /**
     * The card's picture: the one that is already wide (16:9, 3:2 or 4:3: little gets cut on a 16:9 card), the
     * thumbnail first; a poster only when there is nothing else.
     */
    private static String wideImage(String poster, int posterRatio, String thumbnail, int thumbnailRatio) {
        if (!empty(thumbnail) && thumbnailRatio <= TvContract.PreviewPrograms.ASPECT_RATIO_4_3) return thumbnail;
        if (!empty(poster) && posterRatio <= TvContract.PreviewPrograms.ASPECT_RATIO_4_3) return poster;
        return !empty(thumbnail) ? thumbnail : empty(poster) ? null : poster;
    }

    private static boolean empty(String s) {
        return s == null || s.isEmpty();
    }

    /** A picture from the list: on the internet, or inside the app that wrote it (content:// or android.resource://). */
    static java.io.InputStream open(Context c, String uri) throws java.io.IOException {
        Uri u = Uri.parse(uri);
        String scheme = u.getScheme() == null ? "" : u.getScheme();
        if (scheme.equals("http") || scheme.equals("https")) return null;   // the caller downloads it
        java.io.InputStream in = c.getContentResolver().openInputStream(u);
        if (in == null) throw new java.io.IOException("no image at " + scheme);
        return in;
    }
}
