package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;

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
    static final int[] KEYS = {KEY_NETFLIX, KEY_YOUTUBE, KEY_PRIME, KEY_TCL_CHANNEL};

    /** Bumped by every change: screens compare it to know when to redraw. */
    static volatile int version;

    private static Context app;
    private static SharedPreferences prefs;   // kept: the screens read settings while they draw

    static void init(Context c) {
        app = c.getApplicationContext();
        prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE);
        Ui.applyLanguage();   // the language picked on the TV, now that it can be read
        Ui.LITE = Ui.LEGACY || performance();
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

    /** "auto", "pt" or "en". */
    static String language() {
        String v = app == null ? Config.LANGUAGE : sp().getString("language", Config.LANGUAGE);
        v = v.trim().toLowerCase(Locale.ROOT);
        return v.startsWith("pt") ? "pt" : v.startsWith("es") ? "es" : v.startsWith("en") ? "en" : "auto";
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

    // MARK: - City (config.properties, or one picked on the TV)

    /** Name, latitude, longitude: the Brazilian state capitals. */
    static final String[][] CITIES = {
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

    /** Index in CITIES, or -1: the city from config.properties. */
    static int cityIndex() {
        int i = sp().getInt("city", -1);
        return i >= 0 && i < CITIES.length ? i : -1;
    }

    static void setCityIndex(int i) { put("city", i); }

    static String city() { return cityIndex() < 0 ? Config.CITY.trim() : CITIES[cityIndex()][0]; }
    static String latitude() { return cityIndex() < 0 ? Config.LATITUDE : CITIES[cityIndex()][1]; }
    static String longitude() { return cityIndex() < 0 ? Config.LONGITUDE : CITIES[cityIndex()][2]; }

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

    /** The picks open in Nuvio (when installed); off: in the streaming app of each title. */
    static boolean recsInNuvio() { return sp().getBoolean("recsNuvio", true); }
    static void setRecsInNuvio(boolean on) { put("recsNuvio", on); }

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
        int i = sp().getInt("pricesEvery", 0);
        return i >= 0 && i < PRICES_EVERY.length ? i : 0;
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
    static boolean performance() { return sp().getBoolean("performance", false); }
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

    // MARK: - Remote buttons

    private static String defaultApp(int keyCode) {
        switch (keyCode) {
            case KEY_NETFLIX: return "com.netflix.ninja";
            case KEY_YOUTUBE: return "com.google.android.youtube.tv";
            case KEY_PRIME: return "com.amazon.amazonvideo.livingroom";
            case KEY_TCL_CHANNEL: return "";
            default: return "";
        }
    }

    /** Package the key opens ("" = nothing). */
    static String keyApp(int keyCode) { return sp().getString("key" + keyCode, defaultApp(keyCode)); }
    static void setKeyApp(int keyCode, String pkg) { put("key" + keyCode, pkg); }
}
