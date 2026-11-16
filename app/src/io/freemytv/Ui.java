package io.freemytv;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.icu.text.PluralRules;
import android.os.Build;

import org.json.JSONObject;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Fonts and text for the home screen, the screensaver and the settings. Font: Inter (variable, assets/fonts, SIL OFL),
 * with the "display" optical size for large text and the "text" size for small text; without it, the system font at a
 * similar weight.
 */
final class Ui {
    private Ui() {}

    /** Android 8 to 11: older TVs and boxes, whose GPUs need the cheaper drawing paths (Shadows, rounded images). */
    static final boolean LEGACY = Build.VERSION.SDK_INT < 31;
    /** The light drawing paths: Android 8 to 11 always, or More Settings > Performance mode. */
    static volatile boolean LITE = LEGACY;
    /** The language in use (one of LANGUAGES), picked in the settings or, with "auto", the TV's own (else English). */
    static volatile String language = "pt";
    /** Bumped when the language changes: text cached by the screens is made again. */
    static volatile int generation;
    static Typeface thin, light, displayMedium, displaySemibold, regular, medium, semibold;
    private static boolean loaded;
    private static final Rect BOUNDS = new Rect();
    private static final RectF OVAL = new RectF();

    static synchronized void init(Context c) {
        if (loaded) return;
        loaded = true;
        AssetManager a = c.getAssets();
        thin = font(a, 32, 200);
        light = font(a, 32, 300);
        displayMedium = font(a, 32, 500);
        displaySemibold = font(a, 32, 600);
        regular = font(a, 14, 400);
        medium = font(a, 14, 500);
        semibold = font(a, 14, 600);
    }

    /** Inter at an optical size (14 = text, 32 = display) and weight; the system font if the file is missing. */
    private static Typeface font(AssetManager a, int opticalSize, int weight) {
        if (has(a, FONT)) {
            try {
                Typeface t = new Typeface.Builder(a, FONT)
                        .setFontVariationSettings("'opsz' " + opticalSize + ", 'wght' " + weight).build();
                if (t != null) return t;
            } catch (RuntimeException ignored) {
            }
        }
        // Typeface.create(family, weight, italic) is Android 9+; Android 8 only has regular and bold.
        if (Build.VERSION.SDK_INT >= 28) return Weighted.create(weight);
        return Typeface.create(Typeface.DEFAULT, weight >= 600 ? Typeface.BOLD : Typeface.NORMAL);
    }

    /** Only this class touches the Android 9 call, so Ui itself still verifies on Android 8 (like Recording.Node). */
    private static final class Weighted {
        static Typeface create(int weight) {
            return Typeface.create(Typeface.DEFAULT, weight, false);
        }
    }

    private static final String FONT = "fonts/InterVariable.ttf";

    // MARK: - Text in the viewer's language

    /*
     * The code is written in Brazilian Portuguese, and that text is the key: Ui.tr("TV ao vivo"). Every other language is
     * a catalogue, assets/i18n/<lang>.json, mapping the Portuguese to that language. A key "context|Text" is "Text" where
     * a language needs another word for it (the context is dropped on screen). Text built at runtime is a pattern with
     * named placeholders: Ui.trf("há {n} min", "n", 12). A text a language lacks shows in English, then in Portuguese.
     * tools/translate.py lists and fills what each catalogue lacks. The active catalogues are HashMaps, swapped whole when
     * the language changes, so a lookup is a get() that allocates nothing.
     */
    /** The languages, in the order the settings list them: the source first. */
    static final String[] LANGUAGES = {"pt", "en", "es", "fr", "it"};
    private static final String SOURCE = "pt", FALLBACK = "en";
    private static volatile Map<String, String> catalogue = Collections.emptyMap(), fallback = Collections.emptyMap();
    private static volatile Locale locale = new Locale("pt", "BR");
    private static volatile PluralRules plurals = PluralRules.forLocale(locale);
    private static final Map<String, String> CONTEXT_FREE = new ConcurrentHashMap<>();
    /** The usual country for each language's dates and numbers, when the TV itself is in another language. */
    private static final String[][] REGIONS = {{"pt", "BR"}, {"en", "US"}, {"es", "ES"}, {"fr", "FR"}, {"it", "IT"}};

    /** The text for this Portuguese key in the current language. */
    static String tr(String pt) {
        String s = catalogue.get(pt);
        if (s != null) return s;
        s = fallback.get(pt);
        return s != null ? s : withoutContext(pt);
    }

    /** "context|Text" is "Text" ("plural|one:…|other:…" is its forms, "format|HH:mm" its pattern). */
    private static String withoutContext(String key) {
        int bar = key.indexOf('|');
        if (bar < 0) return key;
        String plain = CONTEXT_FREE.get(key);
        if (plain == null) {
            plain = key.substring(bar + 1);
            CONTEXT_FREE.put(key, plain);
        }
        return plain;
    }

    /** A pattern with named placeholders, filled from name/value pairs: trf("há {n} min", "n", 12). */
    static String trf(String pt, Object... nameValue) {
        String t = tr(pt);
        int open = t.indexOf('{');
        if (open < 0) return t;
        StringBuilder b = new StringBuilder(t.length() + 24);
        int from = 0;
        while (open >= 0) {
            int close = t.indexOf('}', open);
            if (close < 0) break;
            b.append(t, from, open);
            boolean found = false;
            for (int i = 0; i + 1 < nameValue.length; i += 2) {
                String name = (String) nameValue[i];
                if (name.length() == close - open - 1 && t.regionMatches(open + 1, name, 0, name.length())) {
                    b.append(nameValue[i + 1]);
                    found = true;
                    break;
                }
            }
            if (!found) b.append(t, open, close + 1);
            from = close + 1;
            open = t.indexOf('{', from);
        }
        return b.append(t, from, t.length()).toString();
    }

    /**
     * A count with its word in the right plural form: plural("plural|one:{n} temporada|other:{n} temporadas", 3). Each
     * catalogue gives one form per plural category its language uses (Unicode CLDR: zero, one, two, few, many, other).
     * The category for n comes from ICU's rules for the current locale; a missing one falls back to "other", then to the
     * last form. The forms of a text a catalogue lacks follow the Portuguese rules (or the English ones).
     */
    static String plural(String key, long n) {
        String forms = tr(key), category = plurals.select(n), chosen = null, other = null, last = forms;
        int from = 0;
        while (from <= forms.length()) {
            int bar = forms.indexOf('|', from);
            if (bar < 0) bar = forms.length();
            String form = forms.substring(from, bar);
            int colon = form.indexOf(':');
            String name = colon < 0 ? "" : form.substring(0, colon).trim();
            last = colon < 0 ? form : form.substring(colon + 1);
            if (name.equals(category)) chosen = last;
            if (name.equals("other")) other = last;
            from = bar + 1;
        }
        String text = chosen != null ? chosen : other != null ? other : last;
        return text.replace("{n}", String.valueOf(n));
    }

    /** Dates and numbers in the current language (the TV's own country when it is in that language). */
    static Locale locale() {
        return locale;
    }

    /** The language's own name, capitalized: "Português", "English", "Français". */
    static String languageName(String code) {
        Locale l = Locale.forLanguageTag(code);
        String name = l.getDisplayLanguage(l);
        if (name.isEmpty()) return code;
        return name.substring(0, 1).toUpperCase(l) + name.substring(1);
    }

    /** After the language changes in the settings (and once Prefs can read them). */
    static synchronized void applyLanguage() {
        String lang = resolve(Prefs.language());
        Map<String, String> cat = Collections.emptyMap(), back = Collections.emptyMap();
        if (!lang.equals(SOURCE)) {
            cat = load(lang);
            if (cat == null) {
                lang = FALLBACK;
                cat = load(FALLBACK);
            }
            if (cat == null) {
                lang = SOURCE;
                cat = Collections.emptyMap();
            }
            if (!lang.equals(FALLBACK) && !lang.equals(SOURCE)) {
                Map<String, String> english = load(FALLBACK);
                if (english != null) back = english;
            }
        }
        catalogue = cat;
        fallback = back;
        locale = localeOf(lang);
        plurals = PluralRules.forLocale(locale);
        language = lang;
        DATE_PATTERNS.clear();
        generation++;
    }

    /** The language for a setting ("auto", "fr", "pt-BR"…), or for the TV's language with "auto"; else English. */
    private static String resolve(String setting) {
        String picked = setting.equals("auto") ? Locale.getDefault().getLanguage() : setting.replace('_', '-');
        int dash = picked.indexOf('-');
        String lang = (dash < 0 ? picked : picked.substring(0, dash)).toLowerCase(Locale.ROOT);
        for (String code : LANGUAGES) if (code.equals(lang)) return code;
        return FALLBACK;
    }

    private static Locale localeOf(String lang) {
        Locale tv = Locale.getDefault();
        if (lang.equals(tv.getLanguage()) && !tv.getCountry().isEmpty()) return tv;   // the TV's own es-MX, fr-CA, pt-PT…
        for (String[] r : REGIONS) if (r[0].equals(lang)) return new Locale(lang, r[1]);
        return new Locale(lang);
    }

    private static Map<String, String> load(String lang) {
        Context c = Prefs.context();
        if (c == null) return null;
        try (InputStream in = c.getAssets().open("i18n/" + lang + ".json")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            JSONObject o = new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
            HashMap<String, String> map = new HashMap<>(o.length() * 2);
            for (java.util.Iterator<String> keys = o.keys(); keys.hasNext(); ) {
                String key = keys.next();
                map.put(key, o.getString(key));
            }
            return map;
        } catch (Exception e) {
            AppLog.w("i18n " + lang + ": " + e.getMessage());
            return null;
        }
    }

    private static boolean has(AssetManager a, String path) {
        try (InputStream in = a.open(path)) {
            return in != null;
        } catch (IOException e) {
            return false;
        }
    }

    static Paint text(Typeface face, float size, int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        p.setTypeface(face);
        p.setTextSize(size);
        p.setColor(color);
        return p;
    }

    /** Fixed-width digits (the clock and the prices don't jitter when a digit changes). */
    static Paint numbers(Typeface face, float size, int color) {
        Paint p = text(face, size, color);
        p.setFontFeatureSettings("'tnum'");
        return p;
    }

    /** Height of capital letters (and digits) above the baseline. */
    static float cap(Paint p) {
        synchronized (BOUNDS) {
            p.getTextBounds("H8", 0, 2, BOUNDS);
            return -BOUNDS.top;
        }
    }

    /** Half the cap height: added to the center line, it gives the baseline that centers the text. */
    static float halfCap(Paint p) {
        return cap(p) / 2f;
    }

    /** Left bearing of the first character (large, thin fonts start a little inward). */
    static float leftBearing(Paint p, String s) {
        if (s.isEmpty()) return 0;
        synchronized (BOUNDS) {
            p.getTextBounds(s, 0, 1, BOUNDS);
            return BOUNDS.left;
        }
    }

    static int alpha(int color, float a) {
        int base = (color >>> 24) & 255;
        return (Math.round(base * Math.max(0, Math.min(1, a))) << 24) | (color & 0xFFFFFF);
    }

    static float clamp(float v) {
        return Math.max(0, Math.min(1, v));
    }

    private static final Map<String, DatePattern> DATE_PATTERNS = new HashMap<>();

    /**
     * The catalogue's SimpleDateFormat pattern for a "format|..." key and its locale, checked once per language: a pattern
     * SimpleDateFormat rejects falls back to the English one, so a bad catalogue can't crash the screens drawing the date.
     */
    static synchronized DatePattern datePattern(String key) {
        DatePattern pattern = DATE_PATTERNS.get(key);
        if (pattern == null) {
            String text = tr(key);
            Locale loc = locale();
            try {
                new SimpleDateFormat(text, loc);
            } catch (IllegalArgumentException e) {
                AppLog.w("i18n " + language + ": invalid date pattern for " + key);
                text = key.substring("format|".length());   // the Portuguese pattern
                loc = new Locale("pt", "BR");
            }
            pattern = new DatePattern(text, loc);
            DATE_PATTERNS.put(key, pattern);
        }
        return pattern;
    }

    static final class DatePattern {
        final String text;
        final Locale locale;

        DatePattern(String text, Locale locale) {
            this.text = text;
            this.locale = locale;
        }

        String format(long at) {
            SimpleDateFormat f = new SimpleDateFormat(text, locale);
            f.setTimeZone(Clock.zone());
            return f.format(new Date(at));
        }
    }

    /** Brands as people write them (Build.BRAND is often lowercase: "tcl", "xiaomi", "nvidia"). */
    private static final String[][] BRANDS = {
        {"tcl", "TCL"}, {"lge", "LG"}, {"lg", "LG"}, {"nvidia", "NVIDIA"}, {"onn", "onn."}, {"philips", "Philips"},
        {"tpvision", "Philips"}, {"hisense", "Hisense"}, {"vidaa", "Hisense"}, {"xiaomi", "Xiaomi"}, {"sony", "Sony"},
        {"google", "Google TV"}, {"sharp", "Sharp"}, {"toshiba", "Toshiba"}, {"panasonic", "Panasonic"},
        {"aoc", "AOC"}, {"jvc", "JVC"}, {"semp", "Semp"}, {"multilaser", "Multilaser"}, {"amazon", "Amazon"},
    };
    /** Chip makers and placeholders that cheap boxes report as their brand: not a brand to show. */
    private static final String[] NOT_BRANDS = {"", "android", "generic", "unknown", "alps", "mediatek", "mtk",
        "amlogic", "rockchip", "allwinner", "realtek", "droidlogic"};
    private static String brand;

    /** The TV's brand only, never its model ("TCL" on a TCL C6K); "" when the TV doesn't say one. */
    static synchronized String brand() {
        if (brand == null) brand = brandName(isPlaceholder(Build.BRAND) ? Build.MANUFACTURER : Build.BRAND);
        return brand;
    }

    private static String brandName(String raw) {
        String b = raw == null ? "" : raw.trim();
        if (isPlaceholder(b)) return "";
        String key = b.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        for (String[] known : BRANDS) if (key.equals(known[0])) return known[1];
        // Unknown: short names are initials ("BGH"), longer ones get a capital ("semp toshiba" → "Semp toshiba").
        if (b.length() <= 3) return b.toUpperCase(Locale.ROOT);
        return b.equals(b.toLowerCase(Locale.ROOT)) ? Character.toUpperCase(b.charAt(0)) + b.substring(1) : b;
    }

    private static boolean isPlaceholder(String raw) {
        String key = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        for (String s : NOT_BRANDS) if (key.equals(s)) return true;
        return false;
    }

    /** "21:47" (in English, "9:47", like the iPhone in the US). */
    static String clock(long now) {
        return Ui.datePattern("format|HH:mm").format(now);
    }

    /** "5:21" (sunrise and sunset; in English, "5:21 AM"). */
    static String time(long at) {
        return once("time", at, () -> Ui.datePattern("format|H:mm").format(at));
    }

    private static final HashMap<String, String> FORMATTED = new HashMap<>();
    private static int formattedZone;

    /**
     * A formatted value, made once per language: the bottom drawer is redrawn on every frame of its focus animation,
     * and SimpleDateFormat and String.format there cost ~10 ms a frame on a Mi Box 3.
     */
    static String once(String kind, double value, Supplier<String> format) {
        String key = kind + '|' + language + '|' + value;
        synchronized (FORMATTED) {
            if (formattedZone != Clock.generation) {   // times formatted in another zone
                FORMATTED.clear();
                formattedZone = Clock.generation;
            }
            String s = FORMATTED.get(key);
            if (s == null) {
                if (FORMATTED.size() > 200) FORMATTED.clear();
                s = format.get();
                FORMATTED.put(key, s);
            }
            return s;
        }
    }

    /** "domingo, 18 de outubro" (lowercase, like the iPhone in Portuguese); "Sunday, October 18" in English; "Sonntag, 18. Oktober". */
    static String date(long now) {
        return Ui.datePattern("format|EEEE, d 'de' MMMM").format(now);
    }

    /**
     * One background thread at background priority: decoding photos and reading files mustn't take CPU from the frames
     * on a weak TV (the default priority is the UI thread's).
     */
    static java.util.concurrent.ExecutorService background(String name) {
        return java.util.concurrent.Executors.newSingleThreadExecutor(task -> new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            task.run();
        }, "free-my-tv-" + name));
    }

    /**
     * The window's color as the theme is now (dark at night, light by day): what shows for a moment before the screen
     * draws its first frame. The theme's own is fixed, and was a white flash at night.
     */
    static void themeWindow(android.app.Activity a) {
        boolean night = HomeData.get(a).isNight(System.currentTimeMillis());
        a.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(night ? 0xFF1C1C1E : 0xFFEBEBF0));
    }

    /** Spinning arc (work in progress). */
    static void spinner(Canvas c, float cx, float cy, float r, float seconds, Paint stroke) {
        OVAL.set(cx - r, cy - r, cx + r, cy + r);
        c.drawArc(OVAL, (seconds * 360f) % 360f, 270f, false, stroke);
    }
}
