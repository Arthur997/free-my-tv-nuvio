package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.Locale;
import java.util.SimpleTimeZone;
import java.util.TimeZone;
import java.util.concurrent.Executor;

/**
 * The time zone every clock on screen uses (top of Home, screensaver, sunrise, the sky).
 * Automatic (the default): the weather city's UTC offset from Open-Meteo, plus how far the TV's clock is from the
 * server's. Old TVs carry an old time zone database (Brazil's summer time, gone since 2019, is still in it), so a
 * named zone like America/Sao_Paulo can be an hour off there: the offset comes from the internet instead.
 * Manual: a UTC offset and an adjustment picked in Free My TV > Clock.
 */
final class Clock {
    /** The UTC offsets offered by hand, in minutes. */
    static final int[] ZONES = zones();
    /** The adjustments offered by hand, in minutes: -3 h to +3 h in quarters of an hour. */
    static final int[] ADJUSTS = adjusts();

    private static final long UNKNOWN = Long.MIN_VALUE;
    private static final long REFRESH_EVERY = 6 * 3600_000L, RETRY_AFTER = 10 * 60_000L;
    /** Clock errors under this are the server's rounding (its Date has whole seconds): left alone. */
    private static final long SKEW_IGNORED = 30_000L;

    private static volatile long autoOffset = UNKNOWN;
    private static volatile String autoPlace = "";   // the city autoOffset is for
    private static volatile long fetchedAt, failedAt;
    private static volatile boolean fetching;
    private static SharedPreferences cache;

    /** Bumped every time the zone changes. */
    static volatile int generation;

    private static TimeZone zone;
    private static long zoneOffset = UNKNOWN;
    private static int zoneVersion = -1;
    private static long zoneMinute = -1;

    private Clock() {}

    /** The zone to format and read local times with. Cheap: rebuilt only when settings change or once a minute. */
    static synchronized TimeZone zone() {
        long minute = System.currentTimeMillis() / 60_000;
        if (zone != null && zoneVersion == Prefs.version && zoneMinute == minute) return zone;
        zoneVersion = Prefs.version;
        zoneMinute = minute;
        long offset = offset();
        if (zone == null || offset != zoneOffset) {
            zoneOffset = offset;
            zone = new SimpleTimeZone((int) offset, "FreeMyTV");
            generation++;   // Ui.once forgets the times it formatted in the old zone
        }
        return zone;
    }

    /** Milliseconds to add to UTC for the time on screen. */
    private static long offset() {
        if (Prefs.manualClock()) return (Prefs.clockZone() + Prefs.clockAdjust()) * 60_000L;
        loadCache();
        long fetched = autoOffset;
        if (fetched != UNKNOWN && autoPlace.equals(place())) return fetched;
        return TimeZone.getDefault().getOffset(System.currentTimeMillis());   // nothing from the internet yet
    }

    /** The zone's UTC offset in minutes, without a manual adjustment (for the settings' value). */
    static int zoneMinutes() {
        return Prefs.manualClock() ? Prefs.clockZone() : (int) Math.round(offset() / 60_000.0);
    }

    /** "UTC−3", "UTC+5:30", "UTC". */
    static String utcLabel(int minutes) {
        if (minutes == 0) return "UTC";
        int abs = Math.abs(minutes);
        String sign = minutes < 0 ? "−" : "+";
        return abs % 60 == 0 ? "UTC" + sign + abs / 60 : String.format(Locale.US, "UTC%s%d:%02d", sign, abs / 60, abs % 60);
    }

    /** "+1 h", "−0:15", "0". */
    static String adjustLabel(int minutes) {
        if (minutes == 0) return "0";
        int abs = Math.abs(minutes);
        String sign = minutes < 0 ? "−" : "+";
        return abs % 60 == 0 ? sign + abs / 60 + " h" : String.format(Locale.US, "%s%d:%02d", sign, abs / 60, abs % 60);
    }

    /** The TV's own UTC offset now, in minutes: where the manual zone starts. */
    static int systemZoneMinutes() {
        return TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000;
    }

    /**
     * Fetches the city's offset when stale or for another city (automatic mode only). Home calls this every minute.
     * With the weather on, the forecast brings it (fromForecast): a request of its own only when that is stale too.
     */
    static void refreshIfStale(Executor io) {
        if (Prefs.manualClock() || fetching) return;
        loadCache();
        long now = System.currentTimeMillis();
        boolean fresh = autoOffset != UNKNOWN && autoPlace.equals(place()) && now >= fetchedAt && now - fetchedAt < REFRESH_EVERY;
        boolean failedRecently = failedAt != 0 && now >= failedAt && now - failedAt < RETRY_AFTER;
        if (fresh || failedRecently) return;
        fetching = true;
        io.execute(() -> {
            try {
                fetch();
            } catch (Exception e) {
                failedAt = System.currentTimeMillis();
                AppLog.w("clock: " + e.getMessage());
            } finally {
                fetching = false;
            }
        });
    }

    private static void fetch() throws Exception {
        long sent = System.currentTimeMillis();
        long[] serverDate = {0};
        String body = HomeData.get("https://api.open-meteo.com/v1/forecast?latitude=" + Prefs.latitude()
                + "&longitude=" + Prefs.longitude() + "&timezone=auto&forecast_days=1&current=is_day", serverDate);
        if (!fromForecast(body, serverDate[0], sent, System.currentTimeMillis())) throw new Exception("no utc_offset_seconds");
    }

    /**
     * Takes the offset from an Open-Meteo answer for the weather city (its own request or the weather's) and the TV
     * clock's error from the server's Date (sent/received: the TV's clock around the request). False if it has none.
     */
    static boolean fromForecast(String body, long serverDate, long sent, long received) {
        long zoneMs;
        try {
            zoneMs = new JSONObject(body).getLong("utc_offset_seconds") * 1000L;
        } catch (org.json.JSONException e) {
            return false;
        }
        loadCache();
        if (cache == null) return false;
        long skew = serverDate > 0 ? serverDate - (sent + received) / 2 : 0;
        if (Math.abs(skew) < SKEW_IGNORED) skew = 0;
        String place = place();
        boolean changed = autoOffset != zoneMs + skew || !place.equals(autoPlace);
        autoOffset = zoneMs + skew;
        autoPlace = place;
        fetchedAt = received;
        failedAt = 0;
        cache.edit().putLong("offset", autoOffset).putLong("at", fetchedAt).putString("place", place).apply();
        if (changed) AppLog.w("clock: " + utcLabel((int) (zoneMs / 60_000)) + (skew != 0 ? ", TV clock off by " + skew / 1000 + " s" : ""));
        return true;
    }

    /** The last offset fetched (and its city), once per process. */
    private static synchronized void loadCache() {
        if (cache != null) return;
        Context c = Prefs.context();
        if (c == null) return;
        cache = c.getSharedPreferences("clock", Context.MODE_PRIVATE);
        autoPlace = cache.getString("place", "");
        autoOffset = cache.getLong("offset", UNKNOWN);
        fetchedAt = cache.getLong("at", 0);
    }

    private static String place() {
        return Prefs.latitude() + "," + Prefs.longitude();
    }

    private static int[] zones() {
        int[] z = new int[27];   // UTC-12 to UTC+14
        for (int i = 0; i < z.length; i++) z[i] = (i - 12) * 60;
        return z;
    }

    private static int[] adjusts() {
        int[] a = new int[25];
        for (int i = 0; i < a.length; i++) a[i] = (i - 12) * 15;
        return a;
    }
}
