package io.freemytv;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The click of the home screen and the settings (moving the focus, and OK). The app synthesizes the sound itself, a
 * low, soft "tock" whose pitch slides down a little (what modern interfaces use: felt more than heard), writes it once
 * as a WAV in the cache and plays it through SoundPool. It only plays in Free My TV's own screens, never inside apps.
 */
final class Click {
    private static final int RATE = 48_000;
    private SoundPool pool;
    private int move, open;

    Click(Context c) {
        try {
            pool = new SoundPool.Builder().setMaxStreams(3).setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
            // The version in the name: a new sound replaces the one cached by an earlier version (gone too).
            new File(c.getCacheDir(), "click-move.wav").delete();
            new File(c.getCacheDir(), "click-open.wav").delete();
            move = pool.load(wav(c, "tock-move-2.wav", 640f, 470f, 0.009f, 0.035f), 1);
            open = pool.load(wav(c, "tock-open-2.wav", 420f, 300f, 0.030f, 0.12f), 1);
        } catch (IOException | RuntimeException e) {
            AppLog.w("click sound unavailable: " + e.getMessage());
            release();
        }
    }

    void move() {
        if (pool != null && Prefs.sounds()) pool.play(move, 0.32f, 0.32f, 1, 0, 1f);
    }

    void open() {
        if (pool != null && Prefs.sounds()) pool.play(open, 0.42f, 0.42f, 1, 0, 1f);
    }

    void release() {
        if (pool != null) pool.release();
        pool = null;
    }

    /**
     * A sine whose pitch slides from `from` to `to` Hz in the first 25 ms, with a soft octave below (body) and a faint
     * fifth above (color); 2 ms attack (no pop) and exponential decay.
     */
    private static String wav(Context c, String name, float from, float to, float decay, float seconds) throws IOException {
        File f = new File(c.getCacheDir(), name);
        if (f.exists() && f.length() > 44) return f.getPath();
        int n = Math.round(RATE * seconds);
        ByteBuffer b = ByteBuffer.allocate(44 + n * 2).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes("US-ASCII")).putInt(36 + n * 2).put("WAVE".getBytes("US-ASCII"));
        b.put("fmt ".getBytes("US-ASCII")).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(RATE).putInt(RATE * 2).putShort((short) 2).putShort((short) 16);
        b.put("data".getBytes("US-ASCII")).putInt(n * 2);
        double phase = 0;
        for (int i = 0; i < n; i++) {
            double t = (double) i / RATE;
            double glide = Math.min(1, t / 0.025);
            double freq = from + (to - from) * (1 - (1 - glide) * (1 - glide));   // eases into the lower pitch
            phase += 2 * Math.PI * freq / RATE;
            double env = Math.min(1, t / 0.002) * Math.exp(-t / decay);
            double s = 0.72 * Math.sin(phase) + 0.2 * Math.sin(phase / 2) + 0.08 * Math.sin(phase * 1.5);
            b.putShort((short) Math.round(s * env * 0.8 * Short.MAX_VALUE));
        }
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(b.array());
        }
        return f.getPath();
    }
}
