package io.freemytv;

import android.content.Context;
import android.os.SystemClock;
import android.provider.Settings;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * "At power-on" (Settings > System): what the TV shows once it has started. Only after a full start, never on a wake
 * from standby or when the home screen starts again later (an update, a crash): the first time the home screen comes up
 * in a start is kept in a file with the TV's boot count.
 */
final class PowerOn {
    private PowerOn() {}

    static final String HOME = "home", LIVE = "live";
    /** A home screen that comes up later than this after the start isn't the start's own (it was killed and came back). */
    private static final long START_WINDOW_MS = 240_000;

    /** True only once per start, the first time it's asked within the start's first minutes. */
    static boolean firstHomeOfStart(Context c) {
        if (SystemClock.elapsedRealtime() > START_WINDOW_MS) return false;
        int boot = Settings.Global.getInt(c.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        if (boot < 0 || String.valueOf(boot).equals(read(file(c)))) return false;
        try (FileOutputStream out = new FileOutputStream(file(c))) {
            out.write(String.valueOf(boot).getBytes(StandardCharsets.US_ASCII));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static File file(Context c) {
        return new File(c.getFilesDir(), "home-shown-boot");
    }

    private static String read(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] data = new byte[16];
            int n = in.read(data);
            return n > 0 ? new String(data, 0, n, StandardCharsets.US_ASCII).trim() : "";
        } catch (IOException e) {
            return "";
        }
    }
}
