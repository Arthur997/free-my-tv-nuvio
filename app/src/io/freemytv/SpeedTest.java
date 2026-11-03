package io.freemytv;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The connection's speed and ping, measured against Cloudflare's speed test (speed.cloudflare.com, no key): ping is the
 * median of a few tiny requests on a warm connection, then a few seconds of download and of upload. One buffer for the
 * whole test (weak TVs), and one test at a time; the Control Center tile shows the phase and the result.
 */
final class SpeedTest {
    interface Listener { void onSpeed(); }

    static final int IDLE = 0, PING = 1, DOWNLOAD = 2, UPLOAD = 3, DONE = 4, FAILED = 5;
    private static final String BASE = "https://speed.cloudflare.com/";
    private static final int PINGS = 5;
    private static final long DOWNLOAD_MS = 7000, UPLOAD_MS = 5000;
    private static final long DOWNLOAD_BYTES = 25_000_000L, UPLOAD_BYTES = 50_000_000L;
    private static final SpeedTest INSTANCE = new SpeedTest();

    volatile int phase = IDLE;
    volatile float progress;   // 0 to 1, of the whole test
    volatile double pingMs = Double.NaN, downMbps = Double.NaN, upMbps = Double.NaN;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final byte[] buffer = new byte[64 * 1024];
    private Listener listener;
    private long notifiedAt;

    private SpeedTest() {}

    static SpeedTest get() {
        return INSTANCE;
    }

    boolean running() {
        return phase == PING || phase == DOWNLOAD || phase == UPLOAD;
    }

    void setListener(Listener l) {
        listener = l;
    }

    /** Starts a test unless one is running. */
    void start() {
        if (running()) return;
        pingMs = downMbps = upMbps = Double.NaN;
        progress = 0;
        phase = PING;
        notifyNow();
        io.execute(this::run);
    }

    private void run() {
        try {
            pingMs = ping();
            phase = DOWNLOAD;
            notifyNow();
            downMbps = download();
            phase = UPLOAD;
            notifyNow();
            upMbps = upload();
            progress = 1;
            phase = DONE;
        } catch (IOException e) {
            AppLog.w("speed test: " + e.getMessage());
            phase = FAILED;
        }
        AppLog.w("speed test: ping " + pingMs + " ms, down " + downMbps + " Mbps, up " + upMbps + " Mbps");
        notifyNow();
    }

    private double ping() throws IOException {
        request();   // the first one pays for DNS and TLS: not counted
        long[] times = new long[PINGS];
        for (int i = 0; i < PINGS; i++) {
            long at = SystemClock.elapsedRealtimeNanos();
            request();
            times[i] = SystemClock.elapsedRealtimeNanos() - at;
            progress = 0.1f * (i + 1) / PINGS;
            notifySometimes();
        }
        Arrays.sort(times);
        return times[PINGS / 2] / 1e6;
    }

    /** An empty download on the kept-alive connection: about one round trip. */
    private void request() throws IOException {
        HttpURLConnection c = open(BASE + "__down?bytes=0");
        try {
            check(c);
            try (InputStream in = c.getInputStream()) {
                while (in.read(buffer) > 0) { /* drain it, so the connection can be reused */ }
            }
        } finally {
            c.disconnect();
        }
    }

    /** Blocks one after another for a few seconds (the server refuses more than ~25 MB in one request). */
    private double download() throws IOException {
        long start = SystemClock.elapsedRealtime(), bytes = 0, elapsed = 0;
        while (elapsed < DOWNLOAD_MS) {
            HttpURLConnection c = open(BASE + "__down?bytes=" + DOWNLOAD_BYTES);
            try {
                check(c);
                try (InputStream in = c.getInputStream()) {
                    int n;
                    while (elapsed < DOWNLOAD_MS && (n = in.read(buffer)) > 0) {
                        bytes += n;
                        elapsed = SystemClock.elapsedRealtime() - start;
                        progress = 0.1f + 0.5f * Math.min(1, elapsed / (float) DOWNLOAD_MS);
                        notifySometimes();
                    }
                }
            } finally {
                c.disconnect();   // past the time: stops the rest of the block
            }
        }
        return mbps(bytes, elapsed);
    }

    private double upload() throws IOException {
        HttpURLConnection c = open(BASE + "__up");
        c.setDoOutput(true);
        c.setRequestMethod("POST");
        c.setChunkedStreamingMode(buffer.length);
        c.setRequestProperty("Content-Type", "application/octet-stream");
        try {
            long start = SystemClock.elapsedRealtime(), bytes = 0, elapsed = 0;
            try (OutputStream out = c.getOutputStream()) {
                while (elapsed < UPLOAD_MS && bytes < UPLOAD_BYTES) {
                    out.write(buffer);
                    bytes += buffer.length;
                    elapsed = SystemClock.elapsedRealtime() - start;
                    progress = 0.6f + 0.4f * Math.min(1, elapsed / (float) UPLOAD_MS);
                    notifySometimes();
                }
            }
            check(c);   // the server got it all: only now the time counts
            elapsed = SystemClock.elapsedRealtime() - start;
            return mbps(bytes, elapsed);
        } finally {
            c.disconnect();
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(10000);
        c.setUseCaches(false);
        c.setRequestProperty("User-Agent", "FreeMyTV");
        return c;
    }

    private static void check(HttpURLConnection c) throws IOException {
        int code = c.getResponseCode();
        if (code != 200) throw new IOException("HTTP " + code);
    }

    private static double mbps(long bytes, long ms) {
        return ms <= 0 ? Double.NaN : bytes * 8.0 / ms / 1000;
    }

    /** At most a few times a second: the tile redraws on each one. */
    private void notifySometimes() {
        long now = SystemClock.elapsedRealtime();
        if (now - notifiedAt < 250) return;
        notifiedAt = now;
        notifyNow();
    }

    private void notifyNow() {
        main.post(() -> {
            if (listener != null) listener.onSpeed();
        });
    }
}
