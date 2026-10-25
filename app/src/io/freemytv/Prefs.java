package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.KeyEvent;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Settings changed on the TV itself (Free My TV > Settings). config.properties only gives the first
 * values; anything the user changes here wins. Read from the :home process (home screen, screensaver, settings).
 */
final class Prefs {
    private Prefs() {}

    /** Remote keys we handle, with the app each opens until the user picks another. Codes read from the TCL's log. */
    static final int KEY_NETFLIX = 4062, KEY_YOUTUBE = 190, KEY_PRIME = 4099, KEY_TCL_CHANNEL = 4020;
    /** The Netflix button on Vestel TVs and Formovie projectors: STEM_1, which the TV hands to its Netflix key receiver (dumpsys window policy). */
    static final int KEY_STEM_NETFLIX = KeyEvent.KEYCODE_STEM_1;
    /** KTC's (NextStar) Tabii button: BUTTON_8, which the TV hands to its hot-key receiver (its log: "keycode = 195"). */
    static final int KEY_KTC_TABII = KeyEvent.KEYCODE_BUTTON_8;
    /** Sony's app buttons, as SONY_KEY + scan code (read from a BRAVIA's log; the key codes are shared). */
    static final int SONY_KEY = 100_000;
    static final int KEY_SONY_CORE = SONY_KEY + 496, KEY_SONY_NETFLIX = SONY_KEY + 583, KEY_SONY_DISNEY = SONY_KEY + 491,
            KEY_SONY_PRIME = SONY_KEY + 490, KEY_SONY_YOUTUBE = SONY_KEY + 485, KEY_SONY_CRUNCHYROLL = SONY_KEY + 507,
            KEY_SONY_PLAY = SONY_KEY + 587;   // the Google Play button of older remotes: BUTTON_8 (BRAVIA 4K UR3, Android 10)
    static final int[] KEYS = RemoteKeys.SONY
            ? new int[] {KEY_SONY_CORE, KEY_SONY_NETFLIX, KEY_SONY_DISNEY, KEY_SONY_PRIME, KEY_SONY_YOUTUBE, KEY_SONY_CRUNCHYROLL,
                    KEY_SONY_PLAY}
            : RemoteKeys.STEM_NETFLIX ? new int[] {KEY_STEM_NETFLIX}
            : RemoteKeys.KTC ? new int[] {KEY_KTC_TABII}
            : new int[] {KEY_NETFLIX, KEY_YOUTUBE, KEY_PRIME, KEY_TCL_CHANNEL};

    /** Bumped by every change: screens compare it to know when to redraw. */
    static volatile int version;

    private static Context app;
    private static SharedPreferences prefs;   // kept: the screens read settings while they draw

    static void init(Context c) {
        app = c.getApplicationContext();
        prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE);
        migrateKeys();   // before anything reads the button choices
        migrateCity();
        Ui.applyLanguage();   // the language picked on the TV, now that it can be read
        weakTv = weak(app);
        Ui.LITE = Ui.LEGACY || performance();
    }

    /** The app's context in this process (null before App.onCreate). */
    static Context context() {
        return app;
    }

    private static SharedPreferences sp() {
        return prefs;
    }

    private static void put(String key, String value) {
        sp().edit().putString(key, value).apply();
        version++;
    }

    private static void put(String key, boolean value) {
        sp().edit().putBoolean(key, value).apply();
        version++;
    }

    // MARK: - Language and units

    /** "auto" or a language code as picked ("pt", "fr", "zh-CN"…); Ui.applyLanguage finds its catalogue. */
    static String language() {
        String v = app == null ? Config.LANGUAGE : sp().getString("language", Config.LANGUAGE);
        v = v.trim();
        return v.isEmpty() || v.equalsIgnoreCase("auto") ? "auto" : v;
    }

    static void setLanguage(String v) { put("language", v); Ui.applyLanguage(); }

    /** "auto", "C" or "F". */
    static String temperature() {
        String v = sp().getString("temperature", Config.TEMPERATURE).trim().toUpperCase(Locale.ROOT);
        return v.startsWith("F") ? "F" : v.startsWith("C") ? "C" : "auto";
    }

    static void setTemperature(String v) { put("temperature", v); }

    /** "auto" (dark from sunset to sunrise), "light" or "dark". */
    static String theme() {
        String v = sp().getString("theme", "auto");
        return v.equals("light") || v.equals("dark") ? v : "auto";
    }

    static void setTheme(String v) { put("theme", v); }

    private static void put(String key, int value) {
        sp().edit().putInt(key, value).apply();
        version++;
    }

    // MARK: - City

    static String city() { return sp().getString("city", Config.CITY).trim(); }
    static String latitude() { return sp().getString("latitude", Config.LATITUDE); }
    static String longitude() { return sp().getString("longitude", Config.LONGITUDE); }

    static void setCity(String name, String latitude, String longitude) {
        sp().edit().putString("city", name).putString("latitude", latitude).putString("longitude", longitude).apply();
        version++;
    }

    /** The fork's earlier list of cities (the Brazilian state capitals): name, latitude, longitude. */
    private static final String[][] CAPITALS = {
        {"Aracaju", "-10.9472", "-37.0731"}, {"Belém", "-1.4558", "-48.4902"},
        {"Belo Horizonte", "-19.9167", "-43.9345"}, {"Boa Vista", "2.8235", "-60.6758"},
        {"Brasília", "-15.7939", "-47.8828"}, {"Campo Grande", "-20.4697", "-54.6201"},
        {"Cuiabá", "-15.6014", "-56.0979"}, {"Curitiba", "-25.4284", "-49.2733"},
        {"Florianópolis", "-27.5954", "-48.5480"}, {"Fortaleza", "-3.7319", "-38.5267"},
        {"Goiânia", "-16.6869", "-49.2648"}, {"João Pessoa", "-7.1195", "-34.8450"},
        {"Macapá", "0.0349", "-51.0694"}, {"Maceió", "-9.6658", "-35.7353"},
        {"Manaus", "-3.1190", "-60.0217"}, {"Natal", "-5.7945", "-35.2110"},
        {"Palmas", "-10.2491", "-48.3243"}, {"Porto Alegre", "-30.0346", "-51.2177"},
        {"Porto Velho", "-8.7612", "-63.9004"}, {"Recife", "-8.0476", "-34.8770"},
        {"Rio Branco", "-9.9754", "-67.8249"}, {"Rio de Janeiro", "-22.9068", "-43.1729"},
        {"Salvador", "-12.9777", "-38.5016"}, {"São Luís", "-2.5307", "-44.3068"},
        {"São Paulo", "-23.5505", "-46.6333"}, {"Teresina", "-5.0892", "-42.8019"},
        {"Vitória", "-20.3155", "-40.3128"},
    };

    /**
     * An earlier version kept the city as an index in CAPITALS (under the same "city" key, as a number): it becomes
     * the city's name and coordinates, so nothing is lost and the key reads as text again.
     */
    private static void migrateCity() {
        Object old = sp().getAll().get("city");
        if (!(old instanceof Integer)) return;
        int i = (Integer) old;
        SharedPreferences.Editor e = sp().edit().remove("city");
        if (i >= 0 && i < CAPITALS.length) {
            e.putString("city", CAPITALS[i][0]).putString("latitude", CAPITALS[i][1]).putString("longitude", CAPITALS[i][2]);
        }
        e.commit();
    }

    /** The home screen's soft click when moving and opening. */
    /** The click's volume (Click.LEVELS): 0 off, 1 low, 2 medium (the default), 3 high. Sounds turned off before stay off. */
    static int soundLevel() {
        if (!sp().contains("soundLevel")) return sp().getBoolean("sounds", true) ? 2 : 0;
        return Math.max(0, Math.min(3, sp().getInt("soundLevel", 2)));
    }

    static void setSoundLevel(int level) { put("soundLevel", Math.max(0, Math.min(3, level))); }

    // MARK: - TMDB key (typed on the TV, or config.properties)

    /**
     * The TMDB key: the one typed in More Settings, else config.properties'. Secret: only kept in the app's private
     * settings, never logged or shown (the screen says only whether there is one).
     */
    static String tmdbKey() {
        String typed = sp().getString("tmdbKey", "").trim();
        return typed.isEmpty() ? Config.TMDB_KEY.trim() : typed;
    }

    /** Empty clears the typed key (config.properties' applies again). */
    static void setTmdbKey(String key) { put("tmdbKey", key == null ? "" : key.trim()); }

    static boolean typedTmdbKey() { return !sp().getString("tmdbKey", "").trim().isEmpty(); }

    // MARK: - Fork options (Free My TV > More Settings)

    /** Where the picks open: "apps" (the streaming app of each title, the default) or a key of Recs.LINK_APPS. */
    static String recsTarget() { return sp().getString("recsTarget", "apps"); }
    static void setRecsTarget(String key) { put("recsTarget", key); }

    /** Picks: off = the original (3 per tab, new ones every hour); on = today's top titles, browsable with → and ←. */
    static boolean topRecs() { return sp().getBoolean("topRecs", false); }
    static void setTopRecs(boolean on) { put("topRecs", on); }

    static final int TOP_MIN = 5, TOP_MAX = 20;

    /** How many titles per tab in the top (Explore mixes movies and shows). */
    static int topCount() { return Math.max(TOP_MIN, Math.min(TOP_MAX, sp().getInt("topCount", 10))); }
    static void setTopCount(int n) { put("topCount", Math.max(TOP_MIN, Math.min(TOP_MAX, n))); }

    /** How often prices are fetched while the home screen is on: live (~15 s, the screen's tick), 1 or 5 min. */
    static final long[] PRICES_EVERY = {10_000L, 60_000L, 5 * 60_000L};

    static int pricesEveryIndex() {
        int i = sp().getInt("pricesEvery", 2);   // 5 min until changed
        return i >= 0 && i < PRICES_EVERY.length ? i : 2;
    }

    static void setPricesEveryIndex(int i) { put("pricesEvery", i); }

    /** The shelf shows each app's own TV banner instead of its logo lifted onto a brand-colored card. */
    static boolean originalArt() { return sp().getBoolean("originalArt", true); }
    static void setOriginalArt(boolean on) { put("originalArt", on); }

    /** Free My TV's own screensaver in Free My TV mode (off: the TV's own, TCL's Google photos). */
    static boolean ownScreensaver() { return sp().getBoolean("ownScreensaver", false); }
    static void setOwnScreensaver(boolean on) { put("ownScreensaver", on); }

    /**
     * Performance mode: the light drawing paths of Android 9 to 11 (cached shadows, images rounded by a shader) and the
     * flat look instead of the glass shaders (Ui.LITE).
     */
    static boolean performance() { return sp().getBoolean("performance", weakTv); }

    /** A TV with little memory (Android's low-RAM flag, or under ~1.5 GB): performance mode starts on there. */
    private static boolean weakTv;

    private static boolean weak(Context c) {
        android.app.ActivityManager am = (android.app.ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return false;
        android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return am.isLowRamDevice() || mi.totalMem < 1_600_000_000L;
    }

    static void setPerformance(boolean on) {
        Ui.LITE = Ui.LEGACY || on;   // the home screen is rebuilt when settings change: it takes effect right away
        put("performance", on);
    }

    /** The top right of "For You" (light design): the clock and the weather; off: the prices. */
    static boolean topClock() { return sp().getBoolean("topClock", true); }
    static void setTopClock(boolean on) { put("topClock", on); }

    // MARK: - Widgets and coins

    static boolean weather() { return sp().getBoolean("weather", true); }
    static void setWeather(boolean on) { put("weather", on); }

    static boolean quotes() { return sp().getBoolean("quotes", Config.SHOW_CRYPTO); }
    static void setQuotes(boolean on) { put("quotes", on); }

    /** On by default: bitcoin, the dollar and the euro (HomeData.COINS); Ethereum and Solana are opt-in. */
    static boolean coin(int i) { return sp().getBoolean("coin" + i, i == 0 || i >= 3); }
    static void setCoin(int i, boolean on) { put("coin" + i, on); }

    // MARK: - Shelf

    /** Packages on the home shelf, in order; null until the user edits it (then the built-in list applies). */
    static List<String> shelf() {
        String v = sp().getString("shelf", null);
        if (v == null) return null;
        List<String> out = new ArrayList<>();
        for (String p : v.split(",")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    static void setShelf(List<String> packages) {
        StringBuilder sb = new StringBuilder();
        for (String p : packages) sb.append(sb.length() == 0 ? "" : ",").append(p);
        put("shelf", sb.toString());
    }

    // MARK: - Continue watching

    /** Apps kept out of "Continue watching" (More Settings); every app shows until turned off. */
    static boolean watchNextOff(String pkg) {
        return ("," + sp().getString("watchNextOff", "") + ",").contains("," + pkg + ",");
    }

    static void setWatchNextOff(String pkg, boolean off) {
        List<String> list = new ArrayList<>();
        for (String p : sp().getString("watchNextOff", "").split(",")) if (!p.isEmpty() && !p.equals(pkg)) list.add(p);
        if (off) list.add(pkg);
        put("watchNextOff", String.join(",", list));
    }

    static int watchNextOffCount() {
        String v = sp().getString("watchNextOff", "");
        return v.isEmpty() ? 0 : v.split(",").length;
    }

    // MARK: - Remote buttons

    private static String defaultApp(int keyCode) {
        switch (keyCode) {
            case KEY_NETFLIX: case KEY_STEM_NETFLIX: return "com.netflix.ninja";
            case KEY_YOUTUBE: return "com.google.android.youtube.tv";
            case KEY_PRIME: return "com.amazon.amazonvideo.livingroom";
            case KEY_TCL_CHANNEL: return "";
            default: return "";
        }
    }

    // The key service runs in its own small process (:keys), so that a busy home screen can never delay the TV's keys.
    // SharedPreferences are not shared between processes, so these few values live in a tiny file both processes read.
    // The service asks on every key: the values stay in memory and the file is read again only when it changes.
    private static File keysFile() {
        return new File(app.getFilesDir(), "remote-keys.txt");
    }

    private static final String[] keyApps = new String[KEYS.length];   // null = the default
    private static long keysStamp = Long.MIN_VALUE;

    private static boolean writeKeys(String text) {
        return writeSafely(keysFile(), text);
    }

    /** A temporary file per process (two can start together after an update), synced before it replaces the old one. */
    private static boolean writeSafely(File target, String text) {
        File tmp = new File(app.getFilesDir(), target.getName() + "." + android.os.Process.myPid() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return false;
        }
        return tmp.renameTo(target);
    }

    /** Choices saved before they moved to the file (in SharedPreferences) are copied there once. */
    private static void migrateKeys() {
        if (keysFile().exists()) return;
        SharedPreferences old = sp();
        boolean any = false;
        for (int key : KEYS) any |= old.contains("key" + key);
        if (!any) return;
        StringBuilder sb = new StringBuilder();
        for (int key : KEYS) sb.append(key).append('=').append(old.getString("key" + key, defaultApp(key))).append('\n');
        writeKeys(sb.toString());
    }

    /** Package the key opens ("" = nothing). */
    static String keyApp(int keyCode) {
        synchronized (keyApps) {
            File f = keysFile();
            long stamp = f.lastModified() * 31 + f.length();   // 0 without the file: every key opens its default
            if (stamp != keysStamp) {
                keysStamp = stamp;
                java.util.Arrays.fill(keyApps, null);
                if (stamp != 0) {
                    try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                        String line;
                        while ((line = r.readLine()) != null) {
                            for (int i = 0; i < KEYS.length; i++) {
                                String prefix = KEYS[i] + "=";
                                if (line.startsWith(prefix)) keyApps[i] = line.substring(prefix.length());
                            }
                        }
                    } catch (IOException ignored) {
                    }
                }
            }
            for (int i = 0; i < KEYS.length; i++) if (KEYS[i] == keyCode && keyApps[i] != null) return keyApps[i];
            return defaultApp(keyCode);
        }
    }

    /** Saves the app a key opens; false when the file couldn't be written (the old choice stays). */
    static boolean setKeyApp(int keyCode, String pkg) {
        StringBuilder sb = new StringBuilder();
        for (int key : KEYS) sb.append(key).append('=').append(key == keyCode ? pkg : keyApp(key)).append('\n');
        if (!writeKeys(sb.toString())) return false;
        version++;
        return true;
    }

    // The buttons this remote has: the key service notes when it last saw each one (Remote Buttons shows them).
    private static File seenFile() {
        return new File(app.getFilesDir(), "remote-keys-seen.txt");
    }

    private static final long[] keysSeenAt = new long[KEYS.length];
    private static long seenStamp = Long.MIN_VALUE;

    /** The key service saw this button (called on its first press): saved off the key's path, in a thread. */
    static void keySeen(int keyCode) {
        long now = System.currentTimeMillis();
        new Thread(() -> {
            synchronized (keysSeenAt) {
                keySeenAt(keyCode);   // the file's latest
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < KEYS.length; i++) {
                    long at = KEYS[i] == keyCode ? now : keysSeenAt[i];
                    if (at > 0) sb.append(KEYS[i]).append('=').append(at).append('\n');
                }
                writeSafely(seenFile(), sb.toString());
            }
        }, "key-seen").start();
    }

    /** When the key service last saw this button (wall clock), or 0: not on this remote, or not pressed yet. */
    static long keySeenAt(int keyCode) {
        synchronized (keysSeenAt) {
            File f = seenFile();
            long stamp = f.lastModified() * 31 + f.length();
            if (stamp != seenStamp) {
                seenStamp = stamp;
                java.util.Arrays.fill(keysSeenAt, 0);
                if (stamp != 0) readSeen(f);
            }
            for (int i = 0; i < KEYS.length; i++) if (KEYS[i] == keyCode) return keysSeenAt[i];
            return 0;
        }
    }

    /** Lines of "key=time" into keysSeenAt (call with it held). */
    private static void readSeen(File f) {
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) {
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String key = line.substring(0, eq);
                for (int i = 0; i < KEYS.length; i++) {
                    if (!key.equals(String.valueOf(KEYS[i]))) continue;
                    try { keysSeenAt[i] = Long.parseLong(line.substring(eq + 1)); } catch (NumberFormatException ignored) {}
                }
            }
        } catch (IOException ignored) {
        }
    }
}
