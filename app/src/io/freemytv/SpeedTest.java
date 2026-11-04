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
 * median of a few tiny requests on a warm connection, then a few seconds of download and of upload (measure). One test
 * at a time; the Control Center tile shows the phase and the result.
 */
final class SpeedTest {
    interface Listener { void onSpeed(); }

    static final int IDLE = 0, PING = 1, DOWNLOAD = 2, UPLOAD = 3, DONE = 4, FAILED = 5;
    private static final String BASE = "https://speed.cloudflare.com/";
    private static final int PINGS = 5;
    private static final int STREAMS = 4;
    private static final long WARMUP_MS = 1000, DOWNLOAD_MS = 6000, UPLOAD_MS = 6000;
    private static final long DOWNLOAD_BYTES = 25_000_000L, UPLOAD_BYTES = 256 * 1024L;
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
            downMbps = measure(false, DOWNLOAD_MS, 0.1f, 0.5f);
            phase = UPLOAD;
            notifyNow();
            upMbps = measure(true, UPLOAD_MS, 0.6f, 0.4f);
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

    /**
     * Download and upload are measured the same way, as speed tests do: STREAMS connections at once (one alone can't
     * fill a fast line: TCP's slow start and window), the first WARMUP_MS left out, then the bytes that went through
     * in the next `ms`. Downloads count what arrived; uploads count a body only once the server answered for it.
     */
    private double measure(boolean upload, long ms, float from, float span) throws IOException {
        final long start = SystemClock.elapsedRealtime(), counted = start + WARMUP_MS, end = counted + ms;
        final java.util.concurrent.atomic.AtomicLong bytes = new java.util.concurrent.atomic.AtomicLong();
        final IOException[] failed = new IOException[1];
        Thread[] workers = new Thread[STREAMS];
        for (int i = 0; i < STREAMS; i++) {
            workers[i] = new Thread(() -> {
                byte[] own = new byte[64 * 1024];
                try {
                    while (SystemClock.elapsedRealtime() < end) {
                        if (upload) uploadOnce(own, bytes, counted, end);
                        else downloadOnce(own, bytes, counted, end);
                    }
                } catch (IOException e) {
                    synchronized (failed) {
                        if (failed[0] == null) failed[0] = e;
                    }
                }
            }, upload ? "speed-up" : "speed-down");
            workers[i].start();
        }
        while (SystemClock.elapsedRealtime() < end) {
            SystemClock.sleep(100);
            progress = from + span * Math.min(1, (SystemClock.elapsedRealtime() - start) / (float) (end - start));
            notifySometimes();
        }
        for (Thread t : workers) {
            try {
                t.join(10_000);
            } catch (InterruptedException ignored) {
            }
        }
        if (bytes.get() == 0 && failed[0] != null) throw failed[0];
        return mbps(bytes.get(), ms);
    }

    /** One block (the server refuses more than ~25 MB in one request), read until it ends or the time is up. */
    private static void downloadOnce(byte[] own, java.util.concurrent.atomic.AtomicLong bytes, long counted, long end) throws IOException {
        HttpURLConnection c = open(BASE + "__down?bytes=" + DOWNLOAD_BYTES);
        try {
            check(c);
            try (InputStream in = c.getInputStream()) {
                int n;
                long now;
                while ((now = SystemClock.elapsedRealtime()) < end && (n = in.read(own)) > 0) {
                    if (now >= counted) bytes.addAndGet(n);
                }
            }
        } finally {
            c.disconnect();
        }
    }

    /**
     * One body of UPLOAD_BYTES. It counts only once the server answered for it, and only for the part of its time inside
     * the window (a slow line still gets a fair number: a body in flight at the end is waited for and counted pro rata).
     */
    private static void uploadOnce(byte[] own, java.util.concurrent.atomic.AtomicLong bytes, long counted, long end) throws IOException {
        long began = SystemClock.elapsedRealtime();
        HttpURLConnection c = open(BASE + "__up");
        c.setDoOutput(true);
        c.setRequestMethod("POST");
        c.setFixedLengthStreamingMode(UPLOAD_BYTES);
        c.setRequestProperty("Content-Type", "application/octet-stream");
        try {
            try (OutputStream out = c.getOutputStream()) {
                for (long sent = 0; sent < UPLOAD_BYTES; sent += own.length) {
                    out.write(own, 0, (int) Math.min(own.length, UPLOAD_BYTES - sent));
                }
            }
            check(c);
            long done = SystemClock.elapsedRealtime(), inside = Math.min(done, end) - Math.max(began, counted);
            if (inside > 0) bytes.addAndGet(UPLOAD_BYTES * inside / Math.max(1, done - began));
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
