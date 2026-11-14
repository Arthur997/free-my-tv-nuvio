package io.freemytv;

import android.content.Context;
import android.content.pm.PackageManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The TV cleanup (Prepare the TV): turns off the TV's promo, demo and telemetry apps, on any brand, apart from the
 * home screen switch (HomeMode), and turns them back on. The list is assets/debloat.json (a newer copy comes from the
 * fork's GitHub); every app is checked again on the TV with HomeMode's rules before it goes off, and only system apps
 * count. Names that look like ads or telemetry but aren't on the list are only suggested, never picked by a level.
 */
final class Debloat {
    static final int LIGHT = 1, RECOMMENDED = 2, COMPLETE = 3, SUGGESTED = 0;

    private static final String ASSET = "debloat.json", CACHE = "debloat.json";
    private static final String REMOTE = "https://raw.githubusercontent.com/Arthur997/free-my-tv-nuvio/main/app/assets/debloat.json";
    private static final long REFRESH_MS = 24 * 3600_000L;
    /** What the cleanup turned off, in the TV's settings too (as HomeMode's record): survives a Clear data. */
    private static final String RECORD = "free_my_tv_cleaned";
    private static final Pattern PACKAGE = Pattern.compile("[A-Za-z][\\w.]*");

    private Debloat() {}

    /** One app the cleanup can turn off: why, at which level, how it is now, and why it must stay ("" = it may go). */
    static final class App {
        final String pkg, label, why, locked;
        final int level;
        final boolean on, cleaned;
        final long mb;

        App(String pkg, String label, String why, int level, boolean on, boolean cleaned, String locked, long mb) {
            this.pkg = pkg;
            this.label = label;
            this.why = why;
            this.level = level;
            this.on = on;
            this.cleaned = cleaned;
            this.locked = locked;
            this.mb = mb;
        }

        /** Picked by this level: on the list at or below it, still on, and free to go. */
        boolean pickedBy(int chosen) {
            return on && locked.isEmpty() && level != SUGGESTED && level <= chosen;
        }
    }

    // MARK: - Analysis (any process, off the UI thread)

    /** The TV's apps the cleanup knows about (and the suggestions), sorted by level and name. Takes a few seconds. */
    static List<App> analyze(Context c) throws IOException {
        refreshList(c);
        JSONObject list = list(c);
        Map<String, JSONObject> known = new LinkedHashMap<>();
        JSONArray apps = list.optJSONArray("apps");
        if (apps != null) for (int i = 0; i < apps.length(); i++) {
            JSONObject a = apps.optJSONObject(i);
            if (a != null && PACKAGE.matcher(a.optString("pkg")).matches()) known.put(a.optString("pkg"), a);
        }
        Pattern suggest = suggestPattern(list);
        Set<String> cleaned = new HashSet<>(HomeMode.stringList(HomeMode.state(c), "cleaned"));
        try (Adb adb = Adb.connect(c, 30_000, null)) {
            Set<String> system = HomeMode.packages(adb, "-s"), off = HomeMode.packages(adb, "-d");
            Set<String> homes = HomeMode.homePackages(adb, HomeMode.state(c));
            String keyboard = HomeMode.keyboardPackage(adb);
            Set<String> owners = HomeMode.wifiOwners(adb);
            Set<String> dreams = screensavers(adb);
            Set<String> shown = new HashSet<>();
            for (String pkg : known.keySet()) if (system.contains(pkg)) shown.add(pkg);
            for (String pkg : system) {
                if (!known.containsKey(pkg) && suggest != null && suggest.matcher(pkg).find() && HomeMode.mayTurnOff(pkg)) shown.add(pkg);
            }
            Map<String, String> locks = new LinkedHashMap<>();
            Set<String> free = new HashSet<>();
            for (String pkg : shown) {
                String why = lock(pkg, homes, keyboard, owners, dreams);
                locks.put(pkg, why);
                if (why.isEmpty() && !off.contains(pkg)) free.add(pkg);
            }
            Set<String> needed = HomeMode.neededByTv(adb, free);
            Map<String, Long> memory = HomeMode.appMemoryMB(adb);
            List<App> out = new ArrayList<>();
            for (String pkg : shown) {
                String why = locks.get(pkg);
                if (why.isEmpty() && free.contains(pkg) && (needed == null || needed.contains(pkg))) {
                    why = Ui.tr("as entradas ou o controle da TV precisam dele", "the TV's inputs or remote need it",
                            "las entradas o el control de la TV la necesitan");
                }
                JSONObject entry = known.get(pkg);
                int level = entry != null ? Math.max(LIGHT, Math.min(COMPLETE, entry.optInt("level", COMPLETE))) : SUGGESTED;
                String reason = entry != null ? reason(entry) : Ui.tr("O nome parece de propaganda ou telemetria; confira antes",
                        "The name looks like ads or telemetry; check it first", "El nombre parece de publicidad o telemetría; revísala antes");
                Long mb = memory.get(pkg);
                out.add(new App(pkg, label(c, pkg), reason, level, !off.contains(pkg), cleaned.contains(pkg), why, mb == null ? 0 : mb));
            }
            Collections.sort(out, (a, b) -> a.level != b.level ? Integer.compare(order(a.level), order(b.level))
                    : a.label.compareToIgnoreCase(b.label));
            return out;
        }
    }

    /** Suggestions last. */
    private static int order(int level) {
        return level == SUGGESTED ? 99 : level;
    }

    /** HomeMode's reasons, plus the screensaver in use (off, the TV would have none). */
    private static String lock(String pkg, Set<String> homes, String keyboard, Set<String> owners, Set<String> dreams) {
        String why = HomeMode.lockReason(pkg, homes, keyboard, owners);
        if (why.isEmpty() && dreams.contains(pkg)) why = Ui.tr("é o protetor de tela em uso", "the screensaver in use", "es el protector de pantalla en uso");
        return why;
    }

    private static Set<String> screensavers(Adb adb) throws IOException {
        Set<String> out = new HashSet<>();
        String list = adb.shell("settings get secure screensaver_components").trim() + ","
                + adb.shell("settings get secure screensaver_default_component").trim();
        for (String component : list.split(",")) {
            String pkg = HomeMode.packageOf(component.trim());
            if (pkg != null && !pkg.equals("null")) out.add(pkg);
        }
        return out;
    }

    private static String reason(JSONObject entry) {
        JSONObject why = entry.optJSONObject("why");
        if (why == null) return "";
        return Ui.tr(why.optString("pt"), why.optString("en"), why.optString("es", why.optString("en")));
    }

    private static String label(Context c, String pkg) {
        PackageManager pm = c.getPackageManager();
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    // MARK: - The list: the bundled one, or a newer one from GitHub

    /** The newer of the bundled list and the downloaded one. */
    private static JSONObject list(Context c) {
        JSONObject bundled = parse(readAsset(c));
        JSONObject cached = parse(readFile(new File(c.getFilesDir(), CACHE)));
        if (cached != null && (bundled == null || cached.optInt("version") > bundled.optInt("version"))) return cached;
        return bundled != null ? bundled : new JSONObject();
    }

    /** Once a day at most; a failure keeps what's there. */
    private static void refreshList(Context c) {
        File file = new File(c.getFilesDir(), CACHE);
        if (file.exists() && System.currentTimeMillis() - file.lastModified() < REFRESH_MS) return;
        try {
            String text = HomeData.get(REMOTE, null);
            if (parse(text) == null) return;
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            AppLog.w("cleanup list: " + e.getMessage());
        }
    }

    /** A list with apps, or null. */
    private static JSONObject parse(String text) {
        if (text == null) return null;
        try {
            JSONObject o = new JSONObject(text);
            return o.optJSONArray("apps") != null ? o : null;
        } catch (JSONException e) {
            return null;
        }
    }

    private static Pattern suggestPattern(JSONObject list) {
        try {
            String p = list.optString("suggest");
            return p.isEmpty() ? null : Pattern.compile(p);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String readAsset(Context c) {
        try (InputStream in = c.getAssets().open(ASSET)) {
            return read(in);
        } catch (IOException e) {
            return null;
        }
    }

    private static String readFile(File f) {
        if (!f.exists()) return null;
        try (InputStream in = new java.io.FileInputStream(f)) {
            return read(in);
        } catch (IOException e) {
            return null;
        }
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        return out.toString(StandardCharsets.UTF_8.name());
    }

    // MARK: - Turning off and back on (main process, from ModeJob)

    /** Turns `off` off and `on` back on (only apps the cleanup turned off), in the background; a notice tells how it went. */
    static void request(Context c, List<String> off, List<String> on) {
        ModeJob.schedule(c, ModeJob.CLEANUP, String.join(",", off) + "|" + String.join(",", on), 0);
    }

    /** Everything the cleanup turned off, back on. */
    static void requestUndo(Context c) {
        request(c, new ArrayList<>(), HomeMode.stringList(HomeMode.state(c), "cleaned"));
    }

    static void applyNow(Context c, String plan) {
        int bar = plan.indexOf('|');
        List<String> off = packages(bar < 0 ? plan : plan.substring(0, bar));
        List<String> on = packages(bar < 0 ? "" : plan.substring(bar + 1));
        synchronized (HomeMode.RUN) {
            try (Adb adb = Adb.connect(c, 30_000, null)) {
                Set<String> cleaned = new HashSet<>(HomeMode.stringList(HomeMode.state(c), "cleaned"));
                int turnedOn = 0;
                for (String pkg : on) {
                    if (!cleaned.contains(pkg)) continue;   // only what the cleanup turned off
                    if (adb.shell("pm enable " + pkg).contains("new state: enabled")) {
                        cleaned.remove(pkg);
                        turnedOn++;
                    }
                }
                int turnedOff = 0;
                long mb = 0;
                if (!off.isEmpty()) {
                    Set<String> owners = HomeMode.wifiOwners(adb);
                    if (owners == null && !"0".equals(adb.shell("settings get global wifi_on").trim())) {
                        throw new IOException(Ui.tr("não deu para checar qual app guardou o Wi-Fi; nada foi desligado",
                                "couldn't check which app saved the Wi-Fi; nothing was turned off",
                                "no se pudo revisar qué app guardó el Wi-Fi; no se apagó nada"));
                    }
                    if (owners == null) owners = new HashSet<>();
                    Set<String> system = HomeMode.packages(adb, "-s"), disabled = HomeMode.packages(adb, "-d");
                    Set<String> homes = HomeMode.homePackages(adb, HomeMode.state(c));
                    String keyboard = HomeMode.keyboardPackage(adb);
                    Set<String> dreams = screensavers(adb);
                    Set<String> candidates = new HashSet<>();
                    for (String pkg : off) {
                        if (system.contains(pkg) && !disabled.contains(pkg) && lock(pkg, homes, keyboard, owners, dreams).isEmpty()) candidates.add(pkg);
                    }
                    Set<String> needed = HomeMode.neededByTv(adb, candidates);
                    if (needed == null) throw new IOException(Ui.tr("não deu para checar o que as entradas da TV usam; nada foi desligado",
                            "couldn't check what the TV's inputs need; nothing was turned off",
                            "no se pudo revisar qué usan las entradas de la TV; no se apagó nada"));
                    Map<String, Long> memory = HomeMode.appMemoryMB(adb);
                    int done = 0;
                    for (String pkg : HomeMode.sorted(candidates)) {
                        HomeMode.busy(c, Ui.trf(Ui.tr("Desligando apps ({done}/{total})…", "Turning off apps ({done}/{total})…",
                                "Apagando apps ({done}/{total})…"), "done", ++done, "total", candidates.size()));
                        if (needed.contains(pkg)) continue;
                        if (adb.shell("pm disable-user --user 0 " + pkg).contains("disabled-user")) {
                            cleaned.add(pkg);
                            turnedOff++;
                            Long m = memory.get(pkg);
                            if (m != null) mb += m;
                            save(c, adb, cleaned);
                        }
                    }
                }
                save(c, adb, cleaned);
                HomeMode.done(c, null);
                AppLog.w("cleanup: " + turnedOff + " off, " + turnedOn + " back on");
                HomeMode.notice(c, summary(turnedOff, turnedOn, mb));
            } catch (Exception e) {
                AppLog.w("cleanup: " + e.getMessage());
                HomeMode.done(c, Ui.trf(Ui.tr("A limpeza não terminou: {error}", "The cleanup didn't finish: {error}",
                        "La limpieza no terminó: {error}"), "error", e.getMessage()));
            }
        }
    }

    private static String summary(int off, int on, long mb) {
        if (off == 0 && on == 0) return Ui.tr("Limpeza: nada mudou", "Cleanup: nothing changed", "Limpieza: nada cambió");
        if (off == 0) return Ui.trf(Ui.tr("Limpeza desfeita: {n} apps ligados de novo", "Cleanup undone: {n} apps back on",
                "Limpieza deshecha: {n} apps encendidas de nuevo"), "n", on);
        String text = Ui.trf(Ui.tr("Limpeza: {n} apps desligados", "Cleanup: {n} apps turned off", "Limpieza: {n} apps apagadas"), "n", off);
        if (mb > 0) text += Ui.trf(Ui.tr(", cerca de {mb} MB de memória livres", ", about {mb} MB of memory freed",
                ", unos {mb} MB de memoria libres"), "mb", mb);
        return text;
    }

    private static void save(Context c, Adb adb, Set<String> cleaned) throws JSONException {
        JSONObject s = HomeMode.state(c);
        s.put("cleaned", new JSONArray(HomeMode.sorted(cleaned)));
        HomeMode.save(c, s);
        try {
            adb.shell(cleaned.isEmpty() ? "settings delete global " + RECORD
                    : "settings put global " + RECORD + " " + String.join(",", HomeMode.sorted(cleaned)));
        } catch (IOException e) {
            AppLog.w("could not save the record of the cleanup: " + e.getMessage());
        }
    }

    /** The plan's package names, only plain ones (they go into shell commands). */
    private static List<String> packages(String csv) {
        List<String> out = new ArrayList<>();
        for (String pkg : csv.split(",")) {
            Matcher m = PACKAGE.matcher(pkg.trim());
            if (m.matches()) out.add(pkg.trim());
        }
        return out;
    }
}
