package io.freemytv;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.SoundPool;
import android.os.SystemClock;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The click of the home screen and the settings: a low, soft "drop" (a note that slides a little down into place,
 * with the high end rolled off) on every move, and on OK the same drop with its fifth right after. The app
 * synthesizes it, writes it once as a WAV in the cache and plays it through SoundPool at the volume picked in More
 * Settings. It only plays in Free My TV's own screens, never inside apps.
 *
 * Every key press clicks. Two things made clicks go missing on TVs: the "system sounds" channel, which TVs turn down
 * or off (the click now plays as media, with the TV's volume), and the audio output going to sleep after a moment of
 * silence (TVs and soundbars cut the start of the next short sound while it wakes). While a Free My TV screen is on,
 * a silent loop keeps the output awake (start/stop).
 */
final class Click {
    private static final int RATE = 48_000;
    /** Sample peak of the WAVs: the loudest setting plays at this, so the sound never clips. */
    private static final double PEAK = 0.6;
    /** More Settings > Sound volume: off, low, medium (the default), high. A move plays a little below an OK. */
    private static final float[] LEVELS = {0f, 0.3f, 0.6f, 1f};
    private static final float MOVE = 0.8f;
    private static final AudioAttributes ATTRIBUTES = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
    /** A key that triggers two clicks on its way (one where it's handled, one for every press) plays one. */
    private static final long SAME_PRESS_MS = 60;
    private SoundPool pool;
    private AudioTrack awake;
    private int move, open;
    private long playedAt;

    Click(Context c) {
        try {
            pool = new SoundPool.Builder().setMaxStreams(4).setAudioAttributes(ATTRIBUTES).build();
            // The version in the names: a new sound replaces the ones cached by earlier versions (gone too).
            for (String old : new String[] {"click-move.wav", "click-open.wav", "tock-move-2.wav", "tock-open-2.wav"}) {
                //noinspection ResultOfMethodCallIgnored
                new File(c.getCacheDir(), old).delete();
            }
            move = pool.load(wav(c, "drop-move-3.wav", moveSound()), 1);
            open = pool.load(wav(c, "drop-open-3.wav", okSound()), 1);
        } catch (IOException | RuntimeException e) {
            AppLog.w("click sound unavailable: " + e.getMessage());
            release();
        }
    }

    void move() {
        play(move, MOVE);
    }

    void open() {
        play(open, 1f);
    }

    private void play(int sound, float share) {
        float v = LEVELS[Prefs.soundLevel()] * share;
        long now = SystemClock.uptimeMillis();
        if (pool == null || v <= 0 || now - playedAt < SAME_PRESS_MS) return;
        playedAt = now;
        pool.play(sound, v, v, 1, 0, 1f);
        start();   // the volume was just turned on: keep the output awake from now on
    }

    /** The screen is on: keeps the audio output awake with silence, so no click loses its start. */
    void start() {
        if (awake != null || pool == null || Prefs.soundLevel() == 0) return;
        try {
            int n = RATE / 4;
            awake = new AudioTrack.Builder().setAudioAttributes(ATTRIBUTES)
                    .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(n * 2).build();
            awake.write(new short[n], 0, n);
            awake.setLoopPoints(0, n, -1);
            awake.play();
        } catch (RuntimeException e) {
            AppLog.w("audio keep-awake unavailable: " + e.getMessage());
            stop();
        }
    }

    /** The screen went away (an app, the screensaver): the output may sleep again. */
    void stop() {
        if (awake == null) return;
        try {
            awake.stop();
        } catch (IllegalStateException ignored) {
        }
        awake.release();
        awake = null;
    }

    void release() {
        stop();
        if (pool != null) pool.release();
        pool = null;
    }

    // MARK: - The sound

    private static double[] moveSound() {
        return lowpass(drop(480, 0.6, 0.017, 1.2, 0.008, 0), 2200);
    }

    /** The same drop a touch longer, then its fifth 45 ms later. */
    private static double[] okSound() {
        double[] note = drop(480, 0.6, 0.026, 1.2, 0.01, 0), fifth = drop(720, 0.3, 0.0286, 1.0, 0.01, 0.045);
        double[] out = new double[Math.max(note.length, fifth.length)];
        for (int i = 0; i < out.length; i++) out[i] = (i < note.length ? note[i] : 0) + (i < fifth.length ? fifth[i] : 0);
        return lowpass(out, 2200);
    }

    /**
     * A sine that starts `rise` times higher and eases down to `freq` in `glide` seconds; 2 ms attack (no pop) and
     * exponential decay. Starts after `delay` seconds of silence.
     */
    private static double[] drop(double freq, double amp, double decay, double rise, double glide, double delay) {
        int start = (int) Math.round(delay * RATE), n = start + (int) Math.round((0.12 + decay * 6) * RATE);
        double[] out = new double[n];
        double phase = 0;
        for (int i = start; i < n; i++) {
            double t = (double) (i - start) / RATE, g = Math.min(1, t / glide);
            double ratio = rise + (1 - rise) * (1 - (1 - g) * (1 - g));
            phase += 2 * Math.PI * freq * ratio / RATE;
            out[i] = amp * Math.sin(phase) * Math.min(1, t / 0.002) * Math.exp(-t / decay);
        }
        return out;
    }

    /** One-pole low-pass: rounds off the top, which made the earlier clicks sound metallic. */
    private static double[] lowpass(double[] in, double cutoff) {
        double a = Math.exp(-2 * Math.PI * cutoff / RATE), y = 0;
        double[] out = new double[in.length];
        for (int i = 0; i < in.length; i++) out[i] = y = (1 - a) * in[i] + a * y;
        return out;
    }

    /** 16-bit mono WAV in the cache, scaled so its loudest sample is PEAK. */
    private static String wav(Context c, String name, double[] s) throws IOException {
        File f = new File(c.getCacheDir(), name);
        if (f.exists() && f.length() > 44) return f.getPath();
        double max = 1e-9;
        for (double v : s) max = Math.max(max, Math.abs(v));
        double gain = PEAK / max;
        int n = s.length;
        ByteBuffer b = ByteBuffer.allocate(44 + n * 2).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes("US-ASCII")).putInt(36 + n * 2).put("WAVE".getBytes("US-ASCII"));
        b.put("fmt ".getBytes("US-ASCII")).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(RATE).putInt(RATE * 2).putShort((short) 2).putShort((short) 16);
        b.put("data".getBytes("US-ASCII")).putInt(n * 2);
        for (double v : s) b.putShort((short) Math.round(v * gain * Short.MAX_VALUE));
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(b.array());
        }
        return f.getPath();
    }
}
