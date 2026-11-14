package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/**
 * Team logos, small (the source sends them at the size asked: ~5 KB), downloaded once and kept on disk; the ones on
 * screen also in memory (a few dozen at most). get() never blocks: a logo not ready yet comes on a later frame.
 */
final class FootballLogos {
    /** The size they're fetched at: sharp on the home card and the football screen at 1080p. */
    static final int PX = 128;
    private static final int IN_MEMORY = 48;
    private static final ExecutorService IO = Ui.background("logos");
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<String, Bitmap> CACHE = new LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Bitmap> e) {
            return size() > IN_MEMORY;
        }
    };
    private static final Set<String> LOADING = new HashSet<>();
    /** When a logo last failed (no network, or the team has none): tried again only after a while. */
    private static final Map<String, Long> FAILED = new java.util.HashMap<>();
    private static final long RETRY_MS = 10 * 60_000L;

    private FootballLogos() {}

    /** The logo if it's ready (UI thread); otherwise null, and `ready` runs once it is. */
    static Bitmap get(Context c, String teamId, Runnable ready) {
        if (teamId == null || teamId.isEmpty()) return null;
        Bitmap b = CACHE.get(teamId);
        Long failed = FAILED.get(teamId);
        if (b != null || (failed != null && System.currentTimeMillis() - failed < RETRY_MS) || !LOADING.add(teamId)) return b;
        Context app = c.getApplicationContext();
        IO.execute(() -> {
            Bitmap loaded = load(app, teamId);
            MAIN.post(() -> {
                LOADING.remove(teamId);
                if (loaded == null) FAILED.put(teamId, System.currentTimeMillis());
                else CACHE.put(teamId, loaded);
                if (loaded != null && ready != null) ready.run();
            });
        });
        return null;
    }

    private static Bitmap load(Context c, String teamId) {
        File dir = new File(c.getCacheDir(), "football-logos");
        File file = new File(dir, teamId + ".png");
        if (!file.isFile()) {
            if (!dir.isDirectory() && !dir.mkdirs()) return null;
            if (!download(FootballSource.get().logoUrl(teamId, PX), file)) return null;
        }
        Bitmap b = BitmapFactory.decodeFile(file.getPath());
        if (b != null) b.prepareToDraw();
        return b;
    }

    private static boolean download(String url, File to) {
        File part = new File(to.getPath() + ".part");
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(6000);
            c.setRequestProperty("User-Agent", "FreeMyTV");
            try {
                if (c.getResponseCode() != 200) return false;
                try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
                    byte[] buffer = new byte[8192];
                    int n;
                    while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                }
            } finally {
                c.disconnect();
            }
            return part.renameTo(to);
        } catch (Exception e) {
            AppLog.w("logo " + to.getName() + ": " + e.getMessage());
            part.delete();
            return false;
        }
    }
}
