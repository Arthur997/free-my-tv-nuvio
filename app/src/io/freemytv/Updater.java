package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Updates the app from the fork's GitHub releases (tools/release.sh publishes them): the newest release's FreeMyTV.apk,
 * when its version is above this one. The app downloads it to its folder on the shared storage, where the TV's shell can
 * read it, and the shell installs it (HomeMode.installUpdateNow): installing replaces this app's processes. Android
 * refuses an APK signed with another key, so only builds signed with this one get in.
 */
final class Updater {
    private Updater() {}

    private static final String REPO = "Arthur997/free-my-tv-nuvio";
    private static final String APK = "FreeMyTV.apk";
    private static final long CHECK_EVERY_MS = 6 * 3600_000L;

    /** A release: its tag ("v2.0.1"), the version code it stands for, its APK and the first line of its notes. */
    static final class Release {
        final String tag, url, sha256, notes;
        final int code;
        final long size;

        Release(String tag, int code, String url, long size, String sha256, String notes) {
            this.tag = tag;
            this.notes = notes;
            this.code = code;
            this.url = url;
            this.size = size;
            this.sha256 = sha256;
        }

        String version() {
            return tag.startsWith("v") ? tag.substring(1) : tag;
        }
    }

    /** "v2.0.1" → 20001 (major·10000 + minor·100 + patch, as the manifest's versionCode); 0 when it isn't a version. */
    static int code(String tag) {
        Matcher m = Pattern.compile("^v?(\\d+)\\.(\\d+)\\.(\\d+)$").matcher(tag);
        if (!m.find()) return 0;
        return Integer.parseInt(m.group(1)) * 10000 + Integer.parseInt(m.group(2)) * 100 + Integer.parseInt(m.group(3));
    }

    static int installedCode(Context c) {
        PackageInfo info = installed(c);
        return info == null ? 0 : info.versionCode;
    }

    static String installedName(Context c) {
        PackageInfo info = installed(c);
        return info == null ? "" : info.versionName;
    }

    private static PackageInfo installed(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    /** The newest release with an APK; null when there's none. Network: not on the UI thread. */
    static Release latest() throws IOException {
        HttpURLConnection http = open("https://api.github.com/repos/" + REPO + "/releases/latest");
        http.setRequestProperty("Accept", "application/vnd.github+json");
        try {
            if (http.getResponseCode() == 404) return null;   // no release yet
            if (http.getResponseCode() != 200) throw new IOException("GitHub " + http.getResponseCode());
            JSONObject release = new JSONObject(read(http.getInputStream()));
            String tag = release.getString("tag_name");
            JSONArray assets = release.getJSONArray("assets");
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                if (!APK.equals(a.optString("name"))) continue;
                String digest = a.optString("digest", "");   // "sha256:…", on releases since 2025
                return new Release(tag, code(tag), a.getString("browser_download_url"), a.getLong("size"),
                        digest.startsWith("sha256:") ? digest.substring(7).toLowerCase(Locale.ROOT) : "",
                        firstLine(release.optString("body", "")));
            }
            return null;
        } catch (JSONException e) {
            throw new IOException("GitHub: " + e.getMessage());
        } finally {
            http.disconnect();
        }
    }

    /** A check that couldn't reach GitHub (no internet) tries again after this, not 6 h later. */
    private static final long RETRY_MS = 30 * 60_000L;

    /** From the home screen: a check every 6 h, when updates on their own are on. */
    static void maybeCheck(Context c) {
        if (!Prefs.autoUpdate()) return;
        long now = System.currentTimeMillis();
        if (Math.abs(now - prefs(c).getLong("checkedAt", 0)) < CHECK_EVERY_MS) return;
        // Counts as checked for the next 10 min (the job runs meanwhile); the job then says when the next one is.
        nextCheckIn(c, 10 * 60_000L);
        ModeJob.schedule(c, ModeJob.UPDATE, "auto", 0);
    }

    /** Not Prefs: no redraw for this. */
    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("update", Context.MODE_PRIVATE);
    }

    private static void nextCheckIn(Context c, long ms) {
        prefs(c).edit().putLong("checkedAt", System.currentTimeMillis() - CHECK_EVERY_MS + ms).apply();
    }

    /** Asked in More Settings > Updates: installs at once, the app starts again. */
    static void requestNow(Context c) {
        ModeJob.schedule(c, ModeJob.UPDATE, "now", 0);
    }

    /** The job: a newer release is downloaded, checked and handed to the TV's shell. `now`: without waiting for the person to leave the app. */
    static void updateNow(Context c, boolean now) {
        Release r;
        try {
            r = latest();
        } catch (IOException e) {
            nextCheckIn(c, RETRY_MS);
            failed(c, now, e);
            return;
        }
        nextCheckIn(c, CHECK_EVERY_MS);
        if (r == null || r.code <= installedCode(c)) {
            AppLog.w("update: " + installedName(c) + " is the newest");
            return;
        }
        try {
            // A version the TV refused (storage, signature) isn't downloaded again every 6 h: only when asked, or a newer one.
            String refused = refusedBy(c, r);
            if (refused != null && !now) {
                AppLog.w("update: " + r.version() + " was refused before, waiting for a newer one");
                noticeRefusedOnce(c, r, refused);
                return;
            }
            File apk = download(c, r);
            // Said by the new version once it's in (announceInstalled): what changed, from the release's notes.
            prefs(c).edit().putString("installing", r.version()).putString("installingNotes", r.notes).apply();
            HomeMode.installUpdateNow(c, apk.getAbsolutePath(), r.version(), now);
        } catch (IOException e) {
            failed(c, now, e);
        }
    }

    /** After this app was replaced: a notice "Updated to 2.3.0: <notes>" when that's the version Updater installed. */
    static void announceInstalled(Context c) {
        String version = prefs(c).getString("installing", "");
        if (version.isEmpty() || !version.equals(installedName(c))) return;
        String notes = prefs(c).getString("installingNotes", "");
        prefs(c).edit().remove("installing").remove("installingNotes").apply();
        String said = Ui.trf(Ui.tr("Atualizado para {version}.", "Updated to {version}.", "Actualizado a {version}."), "version", version);
        HomeMode.notice(c, notes.isEmpty() ? said : said + " " + notes);
    }

    /** The notes' first line, without Markdown marks, at most ~160 characters (a notice, not the changelog). */
    private static String firstLine(String body) {
        for (String line : body.split("\n")) {
            String s = line.replaceAll("^[#*\\-\\s]+", "").trim();
            if (s.isEmpty()) continue;
            return s.length() > 160 ? s.substring(0, 157).trim() + "…" : s;
        }
        return "";
    }

    private static void failed(Context c, boolean now, IOException e) {
        AppLog.w("update: " + e.getMessage());
        if (now) HomeMode.notice(c, Ui.trf(Ui.tr("Não deu para atualizar: {error}", "Couldn't update: {error}",
                "No se pudo actualizar: {error}"), "error", e.getMessage()));
    }

    /** What the TV's installer answered when this release was installed before and failed; null otherwise. */
    private static String refusedBy(Context c, Release r) throws IOException {
        String last = HomeMode.lastUpdateResult(c);   // "2.2.0 Success" or "2.2.0 Failure [INSTALL_FAILED_…]"
        String prefix = r.version() + " ";
        if (!last.startsWith(prefix) || last.contains("Success")) return null;
        return last.substring(prefix.length()).trim();
    }

    private static void noticeRefusedOnce(Context c, Release r, String answer) {
        if (r.tag.equals(prefs(c).getString("refusedNoticed", ""))) return;
        prefs(c).edit().putString("refusedNoticed", r.tag).apply();
        String shortAnswer = answer.length() > 120 ? answer.substring(0, 120) + "…" : answer;
        HomeMode.notice(c, Ui.trf(Ui.tr("A TV recusou a atualização {version}: {error}", "The TV refused update {version}: {error}",
                "La TV rechazó la actualización {version}: {error}"), "version", r.version(), "error", shortAnswer));
    }

    /** The release's APK, checked: its size, its SHA-256 when GitHub gives one, and that it's this app at that version. */
    private static File download(Context c, Release r) throws IOException {
        File dir = c.getExternalFilesDir(null);
        if (dir == null) throw new IOException("no shared storage");
        File part = new File(dir, "update.part"), apk = new File(dir, "update.apk");
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e.getMessage());
        }
        HttpURLConnection http = open(r.url);
        long size = 0;
        try (InputStream in = http.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
            byte[] buffer = new byte[64 * 1024];
            for (int n; (n = in.read(buffer)) > 0; ) {
                out.write(buffer, 0, n);
                sha.update(buffer, 0, n);
                size += n;
            }
        } finally {
            http.disconnect();
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : sha.digest()) hex.append(String.format(Locale.ROOT, "%02x", b));
        PackageInfo info = c.getPackageManager().getPackageArchiveInfo(part.getPath(), 0);
        String problem = size != r.size ? "incomplete download"
                : !r.sha256.isEmpty() && !r.sha256.equals(hex.toString()) ? "the download doesn't match GitHub's checksum"
                : info == null || !c.getPackageName().equals(info.packageName) ? "the release isn't this app"
                : info.versionCode != r.code ? "the release's version doesn't match its tag" : null;
        if (problem != null) {
            part.delete();
            throw new IOException(problem);
        }
        if (!part.renameTo(apk)) throw new IOException("couldn't keep the download");
        return apk;
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection http = (HttpURLConnection) new URL(url).openConnection();
        http.setConnectTimeout(15_000);
        http.setReadTimeout(30_000);
        http.setRequestProperty("User-Agent", "FreeMyTV");   // GitHub's API refuses requests without one
        return http;
    }

    private static String read(InputStream in) throws IOException {
        try (InputStream s = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            for (int n; (n = s.read(buffer)) > 0; ) out.write(buffer, 0, n);
            return out.toString("UTF-8");
        }
    }
}
