package io.freemytv;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Home screen picks ("This morning / afternoon / tonight"): what's trending this week plus recent releases, only from
 * the streaming apps installed on the TV (Netflix, Prime Video, Disney+, Max, Apple TV, Okko, Hulu, Paramount+, Peacock,
 * Globoplay...), via TMDB (an open movie database that knows what streams where in each country). Three per hour.
 * "Watch" opens the service's app. With an app that opens IMDb links picked in More Settings (Nuvio, Nuvio Legacy,
 * Stremio: LINK_APPS), any title counts and "Watch" opens it there instead.
 *
 * Each person uses their own free TMDB key (config.properties "tmdb_key", built into the app). It never goes to the log.
 */
final class Recs {
    static final class Pick {
        int id;
        String kind;            // "movie" or "tv"
        String title, backdrop, meta = "", app, pkg, imdb;
        String link;            // opens the title in its app (looked up with Watchmode), or null: the app's home
        String logo;            // the title's logo on TMDB (a transparent PNG), or null
        String overview = "", year = "";   // the preview after resting on a card: the synopsis and the year
        double rating;                     // TMDB's average (0 to 10), 0 = none
        // Continue watching (WatchNext): the app's own link to where it stopped, and how much was watched (0 to 1).
        // Its `backdrop` is then the picture's address (https://, content:// or android.resource://), not a TMDB path.
        String intentUri;
        float progress;
        boolean detailed;
    }

    interface Listener { void onRecs(); }

    private static final String API = "https://api.themoviedb.org/3";
    private static final String IMAGES = "https://image.tmdb.org/t/p/";
    /**
     * The cards are at most ~920 px wide: 1280 is plenty and ~5x lighter than the original (up to 3840). Free My TV's
     * own screensaver shows the photo full screen, so with it on (and a UI bigger than 720p) the originals come down.
     */
    private static String imageSize() {
        return Prefs.ownScreensaver() && Backdrop.W > 1280 ? "original" : "w1280";
    }
    /** TMDB's Animation genre (movies and shows: cartoons and anime), left out with hide_animation=true. */
    private static final int ANIMATION = 16;
    /** The lists are the day's top: fetched once a day (a new day, or 24 h after the last fetch). */
    private static final long LIST_EVERY = 24 * 3600_000L;
    /** Bump when what is saved for each title changes (the saved list is discarded and fetched again). */
    private static final int CACHE_VERSION = 7;   // 7: synopsis, rating and year for the preview
    private static final int MANHA = 0, TARDE = 1, NOITE = 2;
    /** Service name on TMDB (part of the name, lowercase) → TV app (HomeApps key, package). */
    private static final String[][] SERVICES = {
        {"netflix", "netflix", "com.netflix.ninja"},
        {"prime video", "prime", "com.amazon.amazonvideo.livingroom"},
        {"disney", "disney", "com.disney.disneyplus"},
        {"hulu", "hulu", "com.hulu.livingroomplus"},
        {"paramount", "paramount", "com.cbs.ott"},
        {"peacock", "peacock", "com.peacocktv.peacockandroid"},
        {"globoplay", "globoplay", "com.globo.globotv"},
        {"max", "max", "com.wbd.stream"},
        {"max", "max", "com.hbo.hbonow"},
        {"apple tv", "appletv", "com.apple.atve.androidtv.appletv"},
        {"apple tv", "appletv", "com.apple.atve.sony.appletv"},   // Sony's own build
        {"hotstar", "hotstar", "in.startv.hotstar"},   // TMDB: "JioHotstar" (earlier "Disney Plus Hotstar")
        {"okko", "okko", "ru.more.play"},
        {"crunchyroll", "crunchyroll", "com.crunchyroll.crunchyroid"},
        {"oneplay", "oneplay", "net.cme.voyo.cz.tvapp"},    // Czechia: Voyo and O2 TV became Oneplay, same app
    };
    private static final Map<String, String> SERVICE_NAME = new HashMap<>();
    static {
        SERVICE_NAME.put("netflix", "Netflix");
        SERVICE_NAME.put("prime", "Prime Video");
        SERVICE_NAME.put("disney", "Disney+");
        SERVICE_NAME.put("hulu", "Hulu");
        SERVICE_NAME.put("paramount", "Paramount+");
        SERVICE_NAME.put("peacock", "Peacock");
        SERVICE_NAME.put("globoplay", "Globoplay");
        SERVICE_NAME.put("max", "Max");
        SERVICE_NAME.put("appletv", "Apple TV");
        SERVICE_NAME.put("hotstar", "JioHotstar");
        SERVICE_NAME.put("okko", "Okko");
        SERVICE_NAME.put("crunchyroll", "Crunchyroll");
        SERVICE_NAME.put("oneplay", "Oneplay");
    }
    /** Titles on screen at once in each tab: the big card and the four small ones. */
    static final int ON_SCREEN = 5;
    /** Country for "what streams where" (config.properties "tmdb_region", e.g. US, GB, BR). */
    private static final String REGION = Config.TMDB_REGION.trim().toUpperCase(Locale.ROOT);

    private static Recs instance;

    static synchronized Recs get(Context c) {
        if (instance == null) instance = new Recs(c.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final SharedPreferences cache;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    // Built and ready to draw, least recently drawn first (onTrimMemory lets them go when the TV is short of memory).
    private final Map<String, Backdrop> images = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<String, Bitmap> logos = new LinkedHashMap<>(16, 0.75f, true);   // by Pick.logo
    private final java.util.Set<String> badLogos = new java.util.HashSet<>();
    // Each card's big picture saved as a JPEG when it's built: decoded in a few ms (the whole build takes ~0.5 s), it
    // shows the cards right away after the home screen restarts, until the full one is ready. By Pick.backdrop.
    private final Map<String, Bitmap> previews = new LinkedHashMap<>(16, 0.75f, true);
    private final ExecutorService previewIo = Executors.newSingleThreadExecutor();
    private final ExecutorService builder = Executors.newSingleThreadExecutor();   // decoding and building photos
    // Room for every card a screen can ask for at once (this tab's on screen and next, the first of each tab, and the
    // suggestions in Continue Watching): fewer made them push each other out and be built again in a loop.
    private static final int READY_LIGHT = 24, READY_GLASS = 4;
    private final java.util.Set<String> pending = new java.util.HashSet<>();
    private volatile List<Pick> picks = new ArrayList<>();
    private volatile int period = -1;
    private volatile long listAt;
    private volatile boolean fetching;
    private volatile String error = "";
    private volatile boolean light;     // light design: builds only the cards (all three tabs' cards fit in memory)
    private volatile long failedAt, keyStamp, linkRetryAt;
    private volatile String listLanguage = "";   // language used for the saved titles, genres and texts

    private Recs(Context c) {
        context = c;
        cache = c.getSharedPreferences("recs", Context.MODE_PRIVATE);
        if (cache.getInt("version", 0) != CACHE_VERSION) cache.edit().clear().putInt("version", CACHE_VERSION).apply();
        try {
            String saved = cache.getString("picks", null);
            if (saved != null) picks = parse(new JSONArray(saved));
            period = cache.getInt("period", -1);
            listAt = cache.getLong("listAt", 0);
            listLanguage = cache.getString("language", cache.getBoolean("english", false) ? "en" : "pt");
            keyStamp = cache.getLong("keyStamp", 0);
        } catch (Exception ignored) {
        }
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    static boolean hasKey(Context c) {
        return !Prefs.tmdbKey().isEmpty();
    }

    /** Last fetch, for the settings screen: {count, error, at}. Written by the home screen (another process). */
    static JSONObject status(Context c) {
        File f = new File(c.getFilesDir(), "recs-status.json");
        if (!f.exists() || !hasKey(c)) return new JSONObject();
        try (FileInputStream in = new FileInputStream(f)) {
            return new JSONObject(read(in));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private void writeStatus() {
        try (FileOutputStream out = new FileOutputStream(new File(context.getFilesDir(), "recs-status.json"))) {
            out.write(new JSONObject().put("count", picks.size()).put("error", error).put("at", System.currentTimeMillis())
                    .toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    String error() { return error; }

    static String periodLabel() {
        int p = period(System.currentTimeMillis());
        return p == MANHA ? Ui.tr("THIS MORNING") : p == TARDE ? Ui.tr("THIS AFTERNOON") : Ui.tr("TONIGHT");
    }

    private static boolean sameDay(long a, long b) {
        Calendar x = Calendar.getInstance(), y = Calendar.getInstance();
        x.setTimeInMillis(a);
        y.setTimeInMillis(b);
        return x.get(Calendar.YEAR) == y.get(Calendar.YEAR) && x.get(Calendar.DAY_OF_YEAR) == y.get(Calendar.DAY_OF_YEAR);
    }

    private static int period(long now) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(now);
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        return hour >= 5 && hour < 12 ? MANHA : hour >= 12 && hour < 18 ? TARDE : NOITE;
    }

    /** This hour's picks (the first is the featured one; the arrows switch to the others). */
    List<Pick> now() {
        return now(null);
    }

    /** This hour's five, movies only ("movie") or shows only ("tv"); null = both (the home screen tabs). */
    List<Pick> now(String kind) {
        List<Pick> list = new ArrayList<>();
        if (!hasKey(context)) return list;    // no TMDB key: no recommendations
        Map<String, Boolean> have = new HashMap<>();   // one look per app, not per title (this runs every 15 s)
        for (Pick p : picks) {
            if ((kind == null || kind.equals(p.kind))
                    && (!p.detailed || (p.pkg != null && have.computeIfAbsent(p.pkg, this::installed)))) list.add(p);   // app missing: skip
        }
        if (list.isEmpty()) return list;
        // Top of the day (More Settings): in rank order, as many as asked for.
        if (Prefs.topRecs()) return new ArrayList<>(list.subList(0, Math.min(Prefs.topCount(), list.size())));
        long hour = System.currentTimeMillis() / 3600_000L;
        // Five per hour, never two from the same franchise ("Law & Order" and "Law & Order: Special…").
        List<Pick> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < list.size() && out.size() < ON_SCREEN; i++) {
            Pick p = list.get((int) ((hour * ON_SCREEN + i) % list.size()));
            if (seen.add(franchise(p.title))) out.add(p);
        }
        return out;
    }

    /** "Law & Order: Special Victims Unit" → "law & order" (what comes before ":" or " - "). */
    private static String franchise(String title) {
        String t = title == null ? "" : title.toLowerCase(Locale.ROOT);
        int cut = t.indexOf(':');
        if (cut < 0) cut = t.indexOf(" - ");
        return (cut > 0 ? t.substring(0, cut) : t).trim();
    }

    /** Name of the service the pick is on ("Netflix"), or empty. */
    static String serviceName(Pick p) {
        if (p == null || p.app == null) return "";
        if (p.intentUri != null) return p.app;   // continue watching: the app's own name
        String[] a = linkApp(p.app);
        return a != null ? a[1] : SERVICE_NAME.get(p.app);
    }

    /**
     * Apps that open a title from its IMDb id (More Settings > Open picks in): key, name, packages by preference
     * (";"), the movie link and the show link ({id} = the IMDb id).
     */
    static final String[][] LINK_APPS = {
        {"nuvio", "Nuvio", "com.nuvio.tv;com.nuviodebug.com;com.nuvio.app", "nuvio://movie/{id}", "nuvio://detail/tv/{id}"},
        {"nuvio-legacy", "Nuvio Legacy", "space.nuvio.nativelegacy", "nuvio-legacy://movie/{id}", "nuvio-legacy://detail/tv/{id}"},
        {"stremio", "Stremio", "com.stremio.one", "stremio:///detail/movie/{id}", "stremio:///detail/series/{id}"},
    };

    private static String[] linkApp(String key) {
        for (String[] a : LINK_APPS) if (a[0].equals(key)) return a;
        return null;
    }

    /** What "Watch" opens: the title in the app picked in More Settings, or the streaming app it is on. */
    static HomeApps.Item item(Pick p) {
        if (p.intentUri != null) return HomeApps.title(p.pkg, p.intentUri, p.app);   // continue watching
        String[] a = linkApp(p.app);
        if (a == null) return HomeApps.app(p.pkg, serviceName(p), p.link);   // the title's page (Watchmode), or the app
        String link = (p.kind.equals("movie") ? a[3] : a[4]).replace("{id}", p.imdb);
        return HomeApps.title(p.pkg, link, a[1]);
    }

    /**
     * The app the picks open in, as {key, package}, when one is picked in More Settings and installed (one that takes
     * its links); null: each title opens in the streaming app it is on (the default).
     */
    private String[] linkTarget() {
        synchronized (apps) {
            checkApps();
            if (targetVersion != Prefs.version) {
                target = findLinkTarget();
                targetVersion = Prefs.version;
            }
            return target;
        }
    }

    private String[] findLinkTarget() {
        String[] a = linkApp(Prefs.recsTarget());
        if (a == null) return null;
        PackageManager pm = context.getPackageManager();
        String probe = a[3].replace("{id}", "tt0000001");
        for (String pkg : a[2].split(";")) {
            if (pm.resolveActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(probe)).setPackage(pkg), 0) != null) {
                return new String[] {a[0], pkg};
            }
        }
        return null;
    }

    // What is installed only changes when an app is installed, updated or removed (Android counts those changes): the
    // answers are kept until then, so the screen's check every 15 s doesn't ask the package manager a dozen times.
    private final Map<String, Boolean> apps = new HashMap<>();
    private int appsSeen = -1, targetVersion = -1, stampVersion = -1;
    private String[] target;
    private long stamp;

    /** Forgets the kept answers when an app changed since the last look (call with `apps` held). */
    private void checkApps() {
        android.content.pm.ChangedPackages changed = context.getPackageManager().getChangedPackages(Math.max(0, appsSeen));
        if (appsSeen >= 0 && changed == null) return;
        appsSeen = changed != null ? changed.getSequenceNumber() : 0;
        apps.clear();
        targetVersion = stampVersion = -1;
    }

    /** The pick's ready backdrop (or null: builds it in the background and notifies when it's ready). */
    Backdrop backdrop(Pick p) {
        if (p == null || p.backdrop == null) return null;
        final String key = p.backdrop;    // the details may swap the image midway
        synchronized (images) {
            Backdrop b = images.get(key);
            if (b != null) return b;
            Long failed = failedAtByKey.get(key);
            // A card asks on every frame: one that just failed (no internet) waits a minute before trying again.
            if (failed != null && SystemClock.uptimeMillis() - failed < 60_000L) return null;
            if (!pending.add(key)) return null;
        }
        // The network part on `io`, the build on `builder`: the next photo downloads while this one is built.
        io.execute(() -> {
            File source;
            try {
                source = fetchImage(p);
            } catch (Exception | OutOfMemoryError e) {
                AppLog.w("recommendation image: " + e.getMessage());
                finish(key, false);
                return;
            }
            if (source == null) finish(key, true);
            else builder.execute(() -> finish(key, buildImage(p, source)));
        });
        return null;
    }

    private void finish(String key, boolean ok) {
        synchronized (images) {
            pending.remove(key);
            if (ok) failedAtByKey.remove(key); else failedAtByKey.put(key, SystemClock.uptimeMillis());
        }
    }

    private final Map<String, Long> failedAtByKey = new HashMap<>();

    private boolean hasImage(Pick p) {
        return p.backdrop != null && imageFile(p).exists();
    }

    private File imageFile(Pick p) {
        // A continue-watching picture is a whole address: a short name made from it.
        String name = p.backdrop.startsWith("/") ? p.backdrop.replace("/", "") : "watch-" + Integer.toHexString(p.backdrop.hashCode());
        return new File(new File(context.getCacheDir(), "recs"), name);
    }

    /** With a key: fetches the list once a day (or with a new key, country or language); then the details. */
    void refreshIfStale() {
        if (!hasKey(context) || fetching) return;
        long now = System.currentTimeMillis();
        long stamp = sourceStamp();   // a new key, country, animation choice or installed streaming-app set refetches
        boolean newKey = stamp != keyStamp;
        long sinceFail = now - failedAt;    // negative = the clock went back: don't wait
        if (!newKey && failedAt != 0 && sinceFail >= 0 && sinceFail < 10 * 60_000L) return;
        int p = period(now);
        boolean stale = newKey || !sameDay(now, listAt) || now - listAt > LIST_EVERY || now < listAt || picks.isEmpty()
                || !listLanguage.equals(Ui.language);
        // Photos come on demand (backdrop(): the cards on screen and the next three); so do the top's details.
        boolean lazy = lazyDetails();
        boolean missing = false;
        if (!lazy) for (Pick pick : wanted()) missing |= !pick.detailed || needsLink(pick);
        if (!stale && !missing) return;
        keyStamp = stamp;
        fetching = true;
        io.execute(() -> {
            try {
                if (stale) fetchList(p);
                // One failing title doesn't stop the others.
                int failures = 0, tried = 0;
                String last = "";
                for (Pick pick : lazy ? new ArrayList<Pick>() : wanted()) {
                    tried++;
                    try {
                        if (!pick.detailed) fetchDetails(pick);
                    } catch (Exception e) {
                        failures++;
                        last = e.getMessage() == null ? e.toString() : e.getMessage();
                    }
                    findLink(pick);
                }
                save();
                if (tried > 0 && failures == tried) throw new IOException(last);
                error = "";
                failedAt = 0;
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
                failedAt = System.currentTimeMillis();
                AppLog.w("recommendations: " + error);
            } finally {
                fetching = false;
                writeStatus();
                notifyListeners();
            }
        });
    }

    /** Home screen design: the ready images are rebuilt for it (the light design uses cards, not the whole photo). */
    void setLight(boolean value) {
        if (value == light) return;
        light = value;
        synchronized (images) {
            images.clear();
        }
    }

    /**
     * Screensaver: the picks with an app on the TV and an image already on disk, starting with this hour's (the
     * featured one first), never two from the same franchise.
     */
    List<Pick> showcase() {
        List<Pick> out = new ArrayList<>();
        if (!hasKey(context)) return out;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Pick p : wanted()) if (hasImage(p) && seen.add(franchise(p.title))) out.add(p);
        Map<String, Boolean> have = new HashMap<>();
        for (Pick p : picks) {
            if (p.detailed && p.pkg != null && have.computeIfAbsent(p.pkg, this::installed) && hasImage(p)
                    && seen.add(franchise(p.title))) out.add(p);
        }
        return out;
    }

    /** The pick's original image if it's already on disk (the screensaver loads it in its own way), or null. */
    File readyImage(Pick p) {
        return hasImage(p) ? imageFile(p) : null;
    }

    /**
     * While the screensaver is showing: details and image for every pick in the list (not just this hour's), one at a
     * time in the background, so it has more photos. Not used otherwise.
     */
    void prefetchAll() {
        if (!hasKey(context) || fetching) return;
        long sinceFail = System.currentTimeMillis() - failedAt;
        if (failedAt != 0 && sinceFail >= 0 && sinceFail < 10 * 60_000L) return;
        List<Pick> todo = new ArrayList<>();
        for (Pick p : picks) if (!p.detailed || (p.pkg != null && !hasImage(p))) todo.add(p);
        if (todo.isEmpty()) return;
        fetching = true;
        io.execute(() -> {
            int failures = 0;
            String last = "";
            try {
                // One failing title (gone from TMDB, an image that won't download) doesn't stop the others.
                for (Pick p : todo) {
                    try {
                        if (!p.detailed) fetchDetails(p);
                        if (p.pkg != null) download(p);
                    } catch (Exception e) {
                        failures++;
                        last = e.getMessage() == null ? e.toString() : e.getMessage();
                    }
                }
                save();
                if (failures == todo.size()) {
                    error = last;
                    failedAt = System.currentTimeMillis();
                    AppLog.w("recommendations (screensaver): " + last);
                }
            } finally {
                fetching = false;
                notifyListeners();
            }
        });
    }

    /** This hour's picks for the three tabs (Explore, Movies, Shows), without repeats. */
    List<Pick> wanted() {
        List<Pick> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String kind : new String[] {"movie", "tv"}) {   // the first tab is continue watching (WatchNext)
            // The first five of each tab (all there is in the original): in the top, the rest load as you browse.
            List<Pick> tab = now(kind);
            for (int i = 0; i < Math.min(ON_SCREEN, tab.size()); i++) if (seen.add(key(tab.get(i)))) out.add(tab.get(i));
        }
        return out;
    }

    /** Movies and shows are numbered separately on TMDB: a pick is identified by type + number. */
    static String key(Pick p) {
        return p.kind + ":" + p.id;
    }

    private void notifyListeners() {
        main.post(() -> { for (Listener l : new ArrayList<>(listeners)) l.onRecs(); });
    }

    // MARK: - TMDB

    private void fetchList(int p) throws Exception {
        String common = "&language=" + lang() + "&include_adult=false&sort_by=popularity.desc"
                + (Config.HIDE_ANIMATION ? "&without_genres=" + ANIMATION : "");
        // Nuvio plays any title; otherwise only what streams on the services the TV has, by their TMDB ids.
        if (linkTarget() == null) {
            Map<Integer, String[]> services = new HashMap<>();
            JSONArray providers = get("/watch/providers/movie?language=" + lang() + "&watch_region=" + REGION).getJSONArray("results");
            for (int i = 0; i < providers.length(); i++) {
                JSONObject o = providers.getJSONObject(i);
                String[] s = service(o.optString("provider_name"));
                if (s != null && installed(s[2])) services.put(o.getInt("provider_id"), s);
            }
            if (services.isEmpty()) throw new IOException("none of the TV's streaming services are on TMDB");
            StringBuilder ids = new StringBuilder();
            for (int id : services.keySet()) ids.append(ids.length() == 0 ? "" : "|").append(id);
            common += "&watch_region=" + REGION + "&with_watch_monetization_types=flatrate&with_watch_providers="
                    + URLEncoder.encode(ids.toString(), "UTF-8");
        }
        if (Prefs.topRecs()) {
            fetchTop(p);
            return;
        }
        // 1. Trending this week (TMDB goes by what people are watching and searching for right now), only what is
        //    on one of the TV's services: the details say where (and are then ready).
        List<Pick> hotMovies = new ArrayList<>(), hotShows = new ArrayList<>();
        for (String kind : new String[] {"movie", "tv"}) {
            JSONArray hot = get("/trending/" + kind + "/week?language=" + lang()).getJSONArray("results");
            List<Pick> candidates = new ArrayList<>();
            for (int i = 0; i < hot.length() && i < 20; i++) add(candidates, hot.getJSONObject(i), kind);
            for (Pick pick : candidates) {
                try {
                    fetchDetails(pick);
                } catch (Exception e) {
                    continue;
                }
                if (pick.pkg != null) (kind.equals("movie") ? hotMovies : hotShows).add(pick);
            }
        }
        // 2. Recent releases on the TV's services: movies from the past year and shows with a new episode in the
        //    past 3 months.
        JSONArray movies = get("/discover/movie?vote_count.gte=30&primary_release_date.gte=" + day(-365)
                + "&primary_release_date.lte=" + day(0) + common).getJSONArray("results");
        JSONArray shows = get("/discover/tv?vote_count.gte=20&air_date.gte=" + day(-90) + "&air_date.lte=" + day(0)
                + common).getJSONArray("results");
        List<Pick> newMovies = new ArrayList<>(), newShows = new ArrayList<>();
        for (int i = 0; i < movies.length(); i++) add(newMovies, movies.getJSONObject(i), "movie");
        for (int i = 0; i < shows.length(); i++) add(newShows, shows.getJSONObject(i), "tv");
        // Trending first, then the new releases; movies and shows alternating, without repeats, up to 24.
        List<Pick> list = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        alternate(list, seen, hotMovies, hotShows, 24);
        alternate(list, seen, newMovies, newShows, 24);
        if (list.isEmpty()) throw new IOException("TMDB returned no recommendations");
        picks = list;
        period = p;
        listAt = System.currentTimeMillis();
        listLanguage = Ui.language;
    }

    /**
     * Top of the day: TMDB's most watched and searched today (trending/day), movies and shows, in rank order, up to
     * Prefs.topCount() of each that Nuvio (or one of the TV's services) can open. Two pages: some don't make it.
     */
    private void fetchTop(int p) throws Exception {
        int want = Prefs.topCount();
        boolean lazy = lazyDetails();
        List<Pick> movies = new ArrayList<>(), shows = new ArrayList<>();
        for (String kind : new String[] {"movie", "tv"}) {
            List<Pick> out = kind.equals("movie") ? movies : shows;
            for (int page = 1; page <= 2 && out.size() < want; page++) {
                JSONArray hot = get("/trending/" + kind + "/day?language=" + lang() + "&page=" + page).getJSONArray("results");
                List<Pick> candidates = new ArrayList<>();
                for (int i = 0; i < hot.length(); i++) add(candidates, hot.getJSONObject(i), kind);
                for (Pick pick : candidates) {
                    if (out.size() >= want) break;
                    if (lazy) {
                        out.add(pick);   // Nuvio opens anything: details when its card comes up
                        continue;
                    }
                    try {
                        fetchDetails(pick);
                    } catch (Exception e) {
                        continue;
                    }
                    if (pick.pkg != null) out.add(pick);
                }
            }
        }
        List<Pick> list = new ArrayList<>();
        alternate(list, new java.util.HashSet<>(), movies, shows, 2 * want);
        if (list.isEmpty()) throw new IOException("TMDB returned no recommendations");
        picks = list;
        period = p;
        listAt = System.currentTimeMillis();
        listLanguage = Ui.language;
    }

    /**
     * The top with Nuvio: no need to know where each title streams before showing it, so its details (and photo) are
     * fetched only when its card is on screen or next. Without Nuvio the details decide what makes the list.
     */
    private boolean lazyDetails() {
        return Prefs.topRecs() && linkTarget() != null;
    }

    /** Language of titles and genres (TMDB's "fr-FR", "zh-CN"…); the services come from the region. */
    private static String lang() {
        String lang = Ui.language;
        if (lang.equals("en")) return "en-US";
        if (lang.equals("pt")) return "pt-BR";
        if (lang.equals("es")) return "ES".equals(java.util.Locale.getDefault().getCountry()) ? "es-ES" : "es-MX";
        if (lang.equals("de")) return "de-DE";   // TMDB's de-AT and de-CH have few translations of their own
        if (lang.indexOf('-') > 0) return lang;
        return lang + "-" + Ui.locale().getCountry();
    }

    private static void alternate(List<Pick> out, java.util.Set<String> seen, List<Pick> a, List<Pick> b, int max) {
        for (int i = 0; i < Math.max(a.size(), b.size()) && out.size() < max; i++) {
            if (i < a.size() && seen.add(key(a.get(i)))) out.add(a.get(i));
            if (i < b.size() && out.size() < max && seen.add(key(b.get(i)))) out.add(b.get(i));
        }
    }

    /** The date `offset` days from today, as "2026-10-19" (negative = in the past). */
    private static String day(int offset) {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, offset);
        return String.format(Locale.US, "%04d-%02d-%02d", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH));
    }

    /** Anime is left out of the picks: TMDB's genre 16 (animation) made in Japanese, Chinese or Korean (Pixar stays). */
    private static boolean anime(JSONObject o) {
        String lang = o.optString("original_language");
        if (!lang.equals("ja") && !lang.equals("zh") && !lang.equals("ko")) return false;
        JSONArray genres = o.optJSONArray("genre_ids");
        for (int i = 0; genres != null && i < genres.length(); i++) if (genres.optInt(i) == 16) return true;
        return false;
    }

    private static void add(List<Pick> list, JSONObject o, String kind) {
        String backdrop = o.optString("backdrop_path", "");
        if (backdrop.isEmpty() || "null".equals(backdrop) || anime(o)) return;
        if (Config.HIDE_ANIMATION && animated(o)) return;
        Pick p = new Pick();
        p.id = o.optInt("id");
        p.kind = kind;
        p.title = o.optString(kind.equals("movie") ? "title" : "name");
        p.backdrop = backdrop;
        list.add(p);
    }

    private static boolean animated(JSONObject o) {
        JSONArray genres = o.optJSONArray("genre_ids");
        for (int i = 0; genres != null && i < genres.length(); i++) if (genres.optInt(i) == ANIMATION) return true;
        return false;
    }

    /** Genre, runtime (or seasons) and the service it's on, among the ones the TV has. */
    private void fetchDetails(Pick p) throws Exception {
        JSONObject o = get("/" + p.kind + "/" + p.id
                + "?language=" + lang() + "&append_to_response=watch/providers,images,external_ids&include_image_language=null,"
                + Ui.language.replaceAll("-.*", "") + ",en");
        p.title = title(p, o);
        p.overview = o.optString("overview", "").trim();
        p.rating = o.optDouble("vote_average", 0);
        String date = o.optString(p.kind.equals("movie") ? "release_date" : "first_air_date", "");
        p.year = date.length() >= 4 ? date.substring(0, 4) : "";
        String best = bestBackdrop(o.optJSONObject("images"));
        if (best != null) p.backdrop = best;
        p.logo = bestLogo(o.optJSONObject("images"));
        List<String> parts = new ArrayList<>();
        JSONArray genres = o.optJSONArray("genres");
        if (p.kind.equals("tv")) parts.add(Ui.tr("Series"));
        if (genres != null && genres.length() > 0) {
            String name = genres.getJSONObject(0).optString("name");
            parts.add(genre(name));
        }
        if (p.kind.equals("movie")) {
            int minutes = o.optInt("runtime");
            if (minutes > 0) parts.add(minutes >= 60 ? Ui.trf("{h}h {m}m", "h", minutes / 60, "m", minutes % 60) : Ui.trf("{m}m", "m", minutes));
        } else {
            int seasons = o.optInt("number_of_seasons");
            if (seasons > 0) parts.add(Ui.plural("plural|one:{n} season|other:{n} seasons", seasons));
        }
        JSONObject wp = o.optJSONObject("watch/providers");
        JSONObject br = wp == null ? null : wp.optJSONObject("results") == null ? null : wp.optJSONObject("results").optJSONObject(REGION);
        JSONArray flat = br == null ? null : br.optJSONArray("flatrate");
        for (int i = 0; flat != null && i < flat.length() && p.app == null; i++) {
            String[] s = service(flat.getJSONObject(i).optString("provider_name"));
            if (s != null && installed(s[2])) {
                p.app = s[1];
                p.pkg = s[2];
            }
        }
        JSONObject ids = o.optJSONObject("external_ids");
        String imdb = ids == null ? "" : ids.optString("imdb_id", "");
        String[] target = linkTarget();
        if (target != null && imdb.startsWith("tt")) {
            // The service it streams on stays in the line; the picked app (Nuvio, Stremio...) is what opens it.
            p.imdb = imdb;
            if (p.app != null) parts.add(SERVICE_NAME.get(p.app));
            p.app = target[0];
            p.pkg = target[1];
        } else if (target != null) {
            p.app = null;     // no IMDb id: the picked app can't open it, so it's left out
            p.pkg = null;
        } else if (p.app != null) {
            parts.add(SERVICE_NAME.get(p.app));
        }
        p.meta = String.join(" · ", parts);
        p.detailed = true;
    }

    /**
     * Without a title in the TV's language TMDB answers with the original one, so a Korean series on a German TV reads
     * "닥터X: 하얀 마피아의 시대". Then the English title, when TMDB has one (one more request, only for those).
     */
    private String title(Pick p, JSONObject o) {
        String field = p.kind.equals("movie") ? "title" : "name";
        String own = o.optString(field, p.title);
        String original = o.optString("original_language");
        String lang = Ui.language.replaceAll("-.*", "");   // TMDB's original_language has no country: "zh", not "zh-CN"
        if (lang.equals("en") || own.isEmpty() || !own.equals(o.optString("original_" + field))
                || lang.equals(original) || "en".equals(original)) return own;
        try {
            String english = get("/" + p.kind + "/" + p.id + "?language=en-US").optString(field);
            return english.isEmpty() ? own : english;
        } catch (Exception e) {
            return own;
        }
    }

    /** TV genres that TMDB returns in English even in other languages (pt-BR, es, de…). */
    private static String genre(String name) {
        switch (name) {
            case "Action & Adventure": return Ui.tr("genre|Action & Adventure");
            case "Sci-Fi & Fantasy": return Ui.tr("genre|Sci-Fi & Fantasy");
            case "War & Politics": return Ui.tr("genre|War & Politics");
            case "Kids": return Ui.tr("genre|Kids");
            case "News": return Ui.tr("genre|News");
            case "Soap": return Ui.tr("genre|Soap");
            case "Talk": return Ui.tr("genre|Talk");
            default: return name;
        }
    }

    /**
     * The title's best backdrop: no text written over it (our own text goes on top of it), the closest to 1920 wide
     * (larger only costs download, disk and decoding) and, on a tie, the highest rated.
     */
    private static String bestBackdrop(JSONObject images) {
        JSONArray list = images == null ? null : images.optJSONArray("backdrops");
        String best = null;
        double bestScore = -1;
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject b = list.optJSONObject(i);
            if (b == null || b.optString("file_path", "").isEmpty()) continue;
            boolean textless = b.isNull("iso_639_1");
            int width = b.optInt("width");
            double score = (textless ? 1e7 : 0) + Math.min(width, 1920) * 100 - Math.max(0, width - 1920) + b.optDouble("vote_average", 0);
            if (score > bestScore) {
                bestScore = score;
                best = b.optString("file_path");
            }
        }
        return best;
    }

    /**
     * The title's logo: in the TV's language, else in English, else one with no language; the best rated among those.
     * Only PNGs (some logos are SVG, which Android doesn't decode).
     */
    private static String bestLogo(JSONObject images) {
        JSONArray list = images == null ? null : images.optJSONArray("logos");
        String best = null;
        double bestScore = -1;
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject l = list.optJSONObject(i);
            String path = l == null ? "" : l.optString("file_path", "");
            if (!path.endsWith(".png")) continue;
            String lang = l.isNull("iso_639_1") ? "" : l.optString("iso_639_1");
            int rank = lang.equals(Ui.language) ? 3 : lang.equals("en") ? 2 : lang.isEmpty() ? 1 : 0;
            if (rank == 0) continue;
            double score = rank * 100 + l.optDouble("vote_average", 0);
            if (score > bestScore) {
                bestScore = score;
                best = path;
            }
        }
        return best;
    }

    /**
     * The pick's logo, ready to draw, or null: none on TMDB, or not loaded yet (it comes with the photo; one let go
     * when the TV was short of memory loads again in the background).
     */
    Bitmap logo(Pick p) {
        if (p == null || p.logo == null) return null;
        synchronized (images) {
            Bitmap b = logos.get(p.logo);
            if (b != null || badLogos.contains(p.logo) || !images.containsKey(p.backdrop) || !pending.add("logo:" + p.logo)) return b;
        }
        io.execute(() -> {
            loadLogo(p);
            synchronized (images) {
                pending.remove("logo:" + p.logo);
            }
            notifyListeners();
        });
        return null;
    }

    /** Downloads (once) and decodes the logo; a logo that fails only leaves the title written in text. */
    private void loadLogo(Pick p) {
        if (p.logo == null) return;
        synchronized (images) {
            if (logos.containsKey(p.logo)) return;
        }
        File f = new File(new File(context.getCacheDir(), "recs-logos"), p.logo.replace("/", ""));
        try {
            fetch(IMAGES + "w500" + p.logo, f);
            Bitmap b = BitmapFactory.decodeFile(f.getPath());
            if (b == null) throw new IOException("unreadable logo");
            Bitmap ready = Backdrop.logo(b);
            synchronized (images) {
                logos.put(p.logo, ready);
                while (logos.size() > READY_LIGHT) logos.remove(logos.keySet().iterator().next());
            }
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            synchronized (images) {
                badLogos.add(p.logo);   // not asked for again until the app restarts
            }
            AppLog.w("recommendation logo: " + e.getMessage());
        }
    }

    /** The SERVICES row for a TMDB name: the installed one first, when a service has more than one package. */
    private String[] service(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        String[] first = null;
        for (String[] s : SERVICES) {
            if (!(s[0].equals("max") ? n.matches(".*\\bmax\\b.*") : n.contains(s[0]))) continue;
            if (installed(s[2])) return s;
            if (first == null) first = s;
        }
        return first;
    }

    /** Services named in hide_services (config.properties), trimmed and lowercased: never a source of picks. */
    private static final Set<String> HIDDEN = new HashSet<>();
    static {
        for (String s : Config.HIDE_SERVICES.split(",")) {
            String key = s.trim().toLowerCase(Locale.ROOT);
            if (!key.isEmpty()) HIDDEN.add(key);
        }
    }

    /** The service's app is on the TV, and the service isn't hidden (an app the TV ships without a subscription, say). */
    private boolean installed(String pkg) {
        if (!HIDDEN.isEmpty()) for (String[] s : SERVICES) if (s[2].equals(pkg) && HIDDEN.contains(s[1])) return false;
        synchronized (apps) {
            checkApps();
            Boolean known = apps.get(pkg);
            if (known == null) {
                known = HomeApps.launchIntent(context.getPackageManager(), pkg) != null;
                apps.put(pkg, known);
            }
            return known;
        }
    }

    private long sourceStamp() {
        synchronized (apps) {
            checkApps();
            if (stampVersion != Prefs.version) {
                stamp = makeStamp();
                stampVersion = Prefs.version;
            }
            return stamp;
        }
    }

    private long makeStamp() {
        long stamp = (Prefs.tmdbKey().hashCode() * 31L + REGION.hashCode()) * 31L + (Config.HIDE_ANIMATION ? 1 : 0);
        for (String[] service : SERVICES) stamp = stamp * 31L + (installed(service[2]) ? service[2].hashCode() : 0);
        String[] target = linkTarget();
        stamp = stamp * 31L + (target == null ? 0 : (target[0] + target[1]).hashCode());
        return stamp * 31L + (Prefs.topRecs() ? Prefs.topCount() : 0);   // the top, and its size, is another list
    }

    // MARK: - Watchmode (the title's own page in its app; the free plan's web addresses are what the apps open)

    private static String linkKey(Pick p) {
        return "wm:" + p.kind + p.id + p.app;
    }

    private boolean needsLink(Pick p) {
        return linksOn() && p.pkg != null && p.link == null
                && cache.getString(linkKey(p), null) == null && System.currentTimeMillis() >= linkRetryAt;
    }

    /** A key is set and Watchmode hasn't refused it (a refused key isn't tried again until it changes). */
    private boolean linksOn() {
        String key = Config.WATCHMODE_KEY.trim();
        return !key.isEmpty() && cache.getInt("wm:refused", 0) != key.hashCode();
    }

    /** Sets p.link from what was looked up before, or looks it up (2 requests of the free plan's 2,500 a month). */
    private void findLink(Pick p) {
        if (!linksOn() || p.pkg == null || p.link != null) return;
        String saved = cache.getString(linkKey(p), null);
        if (saved != null) {
            p.link = saved.isEmpty() ? null : saved;
            return;
        }
        if (System.currentTimeMillis() < linkRetryAt) return;
        try {
            String link = "";
            JSONArray found = new JSONObject(watchmode("/search/?search_field=tmdb_" + p.kind + "_id&search_value=" + p.id))
                    .optJSONArray("title_results");
            if (found != null && found.length() > 0) {
                JSONArray sources = new JSONArray(watchmode("/title/" + found.getJSONObject(0).getInt("id") + "/sources/?regions=" + REGION));
                String[] service = null;
                for (String[] s : SERVICES) if (s[1].equals(p.app)) service = s;
                for (int i = 0; service != null && i < sources.length() && link.isEmpty(); i++) {
                    JSONObject s = sources.getJSONObject(i);
                    String name = s.optString("name").toLowerCase(Locale.ROOT).replace(" ", "");
                    String want = service[0].replace(" ", "");
                    boolean same = want.equals("max") ? name.equals("max") : name.contains(want);
                    if (same && s.optString("type").equals("sub") && s.optString("web_url").startsWith("https://")) link = s.optString("web_url");
                }
            }
            cache.edit().putString(linkKey(p), link).apply();
            p.link = link.isEmpty() ? null : link;
        } catch (Exception e) {
            if ("Watchmode returned HTTP 401".equals(e.getMessage())) {
                cache.edit().putInt("wm:refused", Config.WATCHMODE_KEY.trim().hashCode()).apply();
            }
            linkRetryAt = System.currentTimeMillis() + 10 * 60_000L;   // quota or network: not again right away
            AppLog.w("watchmode: " + e.getMessage());
        }
    }

    private static String watchmode(String path) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL("https://api.watchmode.com/v1" + path + (path.contains("?") ? "&" : "?")
                + "apiKey=" + URLEncoder.encode(Config.WATCHMODE_KEY.trim(), "UTF-8")).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("Watchmode returned HTTP " + code);
            return read(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    private JSONObject get(String path) throws IOException, org.json.JSONException {
        String key = readKey();
        if (key == null) throw new IOException("no TMDB key");
        boolean bearer = key.startsWith("eyJ");   // read access token (v4) or API key (v3)
        String url = API + path + (bearer ? "" : (path.contains("?") ? "&" : "?") + "api_key=" + URLEncoder.encode(key, "UTF-8"));
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("Accept", "application/json");
        if (bearer) c.setRequestProperty("Authorization", "Bearer " + key);
        try {
            int code = c.getResponseCode();
            if (code == 401) throw new IOException("TMDB rejected the key");
            if (code != 200) throw new IOException("TMDB returned HTTP " + code);
            return new JSONObject(read(c.getInputStream()));
        } finally {
            c.disconnect();
        }
    }

    private String readKey() {
        String key = Prefs.tmdbKey();
        return key.isEmpty() ? null : key;
    }

    // MARK: - Images (TMDB originals on disk, up to 24 so the whole list fits; this hour's kept ready in memory)

    private void download(Pick p) throws IOException {
        if (p.backdrop.startsWith("/")) {
            fetch(IMAGES + imageSize() + p.backdrop, imageFile(p));
            return;
        }
        InputStream inside = WatchNext.open(context, p.backdrop);   // null: on the internet
        if (inside == null) fetch(p.backdrop, imageFile(p));
        else save(inside, imageFile(p));
    }

    /** Copies a picture another app shares (content://) to `f`, like a download. */
    private static void save(InputStream source, File f) throws IOException {
        //noinspection ResultOfMethodCallIgnored
        f.getParentFile().mkdirs();
        File part = new File(f.getPath() + ".part");
        try (InputStream in = source; FileOutputStream out = new FileOutputStream(part)) {
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        }
        if (!part.renameTo(f)) throw new IOException("couldn't save the image");
        prune(f.getParentFile());
    }

    /** Saves `url` to `f` (an image already on disk isn't downloaded again), keeping the newest few in its folder. */
    private static void fetch(String url, File f) throws IOException {
        if (f.exists()) return;
        //noinspection ResultOfMethodCallIgnored
        f.getParentFile().mkdirs();
        File part = new File(f.getPath() + ".part");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(20000);
        try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw e;
        } finally {
            c.disconnect();
        }
        if (!part.renameTo(f)) throw new IOException("couldn't save the image");
        prune(f.getParentFile());
    }

    /**
     * The network part of a photo: the details first when missing (the top's come with its photo: genre, runtime, the
     * IMDb id Nuvio opens), then the photo on disk. Returns the file to build from, or null when there is nothing to
     * build (already built, or nothing can open the title). A card built before is made again from its saved preview,
     * a fraction of the photo's size and with no download.
     */
    private File fetchImage(Pick p) throws Exception {
        if (!p.detailed) {
            fetchDetails(p);
            save();
            notifyListeners();
            if (p.pkg == null) return null;   // nothing can open it: it leaves the list, no photo needed
        }
        synchronized (images) {
            if (images.containsKey(p.backdrop)) return null;
        }
        File preview = previewFile(p);
        if (light && preview.exists()) return preview;
        try {
            download(p);
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            imageFile(p).delete();
            throw e;
        }
        return imageFile(p);
    }

    /** Decodes `f` just big enough and builds the backdrop (Backdrop: cards plus frosted and glass versions). */
    private boolean buildImage(Pick p, File f) {
        boolean fromPreview = f.equals(previewFile(p));
        if (fromPreview && !light) return true;   // the design changed: the full photo is fetched on the next ask
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), bounds);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 1;
            // The light design's largest picture is the big card; the other one fills the screen.
            int needed = light ? Backdrop.WIDE_W : Backdrop.W;
            while (bounds.outWidth / (opts.inSampleSize * 2) >= needed) opts.inSampleSize *= 2;
            Bitmap b = BitmapFactory.decodeFile(f.getPath(), opts);
            if (b == null) throw new IOException("unreadable image");
            AppLog.w("backdrop \u201c" + p.title + "\u201d: " + bounds.outWidth + "x" + bounds.outHeight + (fromPreview ? " (saved card)" : ""));
            boolean forLight = light;
            File preview = forLight && !fromPreview && !previewFile(p).exists() ? previewFile(p) : null;
            Backdrop backdrop = Backdrop.make(b, forLight, preview);
            if (preview != null) prune(preview.getParentFile());   // the saved cards: only the newest few stay
            if (forLight != light) return true;   // the design changed midway: this one is stale
            synchronized (images) {
                images.put(p.backdrop, backdrop);
                previews.remove(p.backdrop);   // the full one now; if it's let go later, the preview may come back
                pending.remove("preview:" + p.backdrop);
                while (images.size() > (forLight ? READY_LIGHT : READY_GLASS)) images.remove(images.keySet().iterator().next());
            }
            if (forLight) io.execute(() -> {
                loadLogo(p);
                notifyListeners();
            });
            notifyListeners();
            return true;
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            AppLog.w("recommendation image: " + e.getMessage());
            return false;
        }
    }

    private File previewFile(Pick p) {
        return new File(new File(context.getCacheDir(), "cards"), imageFile(p).getName() + ".jpg");
    }

    /** The card's saved picture while the full one is built, or null (none saved yet, or still being read). */
    Bitmap preview(Pick p) {
        if (p == null || p.backdrop == null) return null;
        final String key = p.backdrop;
        final File f = previewFile(p);
        synchronized (images) {
            Bitmap b = previews.get(key);
            if (b != null || !pending.add("preview:" + key)) return b;
        }
        previewIo.execute(() -> {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap b = f.exists() ? BitmapFactory.decodeFile(f.getPath(), opts) : null;
            synchronized (images) {
                // Not saved yet: not looked for again (the full picture comes instead, and saves it for next time).
                // Read: free to be read again if it's let go before the full picture is ready.
                if (b != null) {
                    pending.remove("preview:" + key);
                    if (!images.containsKey(key)) previews.put(key, b);
                    while (previews.size() > ON_SCREEN + 2) previews.remove(previews.keySet().iterator().next());
                }
            }
            if (b != null) notifyListeners();
        });
        return null;
    }

    /**
     * The system is short of memory (onTrimMemory): drop the photos built for the screen, keeping only the `keep` drawn
     * most recently (they come back from disk when asked for again).
     */
    void trim(int keep) {
        synchronized (images) {
            while (images.size() > keep) images.remove(images.keySet().iterator().next());
            while (logos.size() > keep) logos.remove(logos.keySet().iterator().next());
            while (previews.size() > keep) previews.remove(previews.keySet().iterator().next());
        }
    }

    void trim() {
        // Explore's picks stay: the home screen opens on that tab, so its cards are never gray when you come back.
        java.util.Set<String> keep = new java.util.HashSet<>();
        for (Pick p : now(null)) if (p.backdrop != null) keep.add(p.backdrop);
        synchronized (images) {
            images.keySet().retainAll(keep);
        }
    }

    private static void prune(File dir) {
        // A download cut off when the system stopped the app leaves its .part behind.
        long stale = System.currentTimeMillis() - 10 * 60_000L;
        File[] parts = dir.listFiles((d, name) -> name.endsWith(".part"));
        for (int i = 0; parts != null && i < parts.length; i++) {
            //noinspection ResultOfMethodCallIgnored
            if (parts[i].lastModified() < stale) parts[i].delete();
        }
        File[] files = dir.listFiles((d, name) -> !name.endsWith(".part"));
        int keep = Math.max(24, 2 * Prefs.topCount() + 12);   // the top browses up to 2 × topCount titles
        if (files == null || files.length <= keep) return;
        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (int i = 0; i < files.length - keep; i++) {
            //noinspection ResultOfMethodCallIgnored
            files[i].delete();
        }
    }

    // MARK: - List cache

    private void save() {
        try {
            JSONArray a = new JSONArray();
            for (Pick p : picks) {
                a.put(new JSONObject().put("id", p.id).put("kind", p.kind).put("title", p.title).put("backdrop", p.backdrop).put("link", p.link == null ? "" : p.link)
                        .put("meta", p.meta).put("app", p.app == null ? "" : p.app).put("pkg", p.pkg == null ? "" : p.pkg)
                        .put("imdb", p.imdb == null ? "" : p.imdb).put("logo", p.logo == null ? "" : p.logo)
                        .put("overview", p.overview).put("rating", p.rating).put("year", p.year)
                        .put("detailed", p.detailed));
            }
            SharedPreferences.Editor e = cache.edit().putString("picks", a.toString()).putInt("period", period).putLong("listAt", listAt)
                    .putString("language", listLanguage)
                    .putLong("keyStamp", keyStamp).putInt("version", CACHE_VERSION);
            // Watchmode links of titles no longer in the list go (the refused-key mark stays).
            Set<String> keep = new HashSet<>();
            for (Pick p : picks) keep.add(linkKey(p));
            for (String k : cache.getAll().keySet()) if (k.startsWith("wm:") && !k.equals("wm:refused") && !keep.contains(k)) e.remove(k);
            e.apply();
        } catch (Exception ignored) {
        }
    }

    private static List<Pick> parse(JSONArray a) {
        List<Pick> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Pick p = new Pick();
            p.id = o.optInt("id");
            p.kind = o.optString("kind");
            p.title = o.optString("title");
            p.backdrop = o.optString("backdrop");
            p.link = o.optString("link").isEmpty() ? null : o.optString("link");
            p.meta = o.optString("meta");
            p.app = o.optString("app").isEmpty() ? null : o.optString("app");
            p.pkg = o.optString("pkg").isEmpty() ? null : o.optString("pkg");
            p.imdb = o.optString("imdb").isEmpty() ? null : o.optString("imdb");
            p.logo = o.optString("logo").isEmpty() ? null : o.optString("logo");
            p.overview = o.optString("overview");
            p.rating = o.optDouble("rating", 0);
            p.year = o.optString("year");
            p.detailed = o.optBoolean("detailed");
            out.add(p);
        }
        return out;
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        return out.toString(StandardCharsets.UTF_8.name());
    }
}
