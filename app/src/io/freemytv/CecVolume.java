package io.freemytv;

import android.content.Context;
import android.os.SystemClock;

/**
 * Personal (pessoal/volume-cec): a home theater without ARC on HDMI 1, its sound over the optical cable. TCL turns
 * HDMI-CEC System Audio Mode on only with the sound output on ARC, so the remote's volume would stop reaching the home
 * theater; asked for over the TV's own ADB, it stays on with the optical output. The TV drops it when it starts or
 * wakes, so it is asked for again each time the screen comes on (only while CEC and its audio control are on).
 */
final class CecVolume {
    private CecVolume() {}

    /** CEC needs a few seconds after the screen comes on to find the home theater again. */
    private static final long SETTLE_MS = 8_000;

    static void ensure(Context context) {
        Context app = context.getApplicationContext();
        new Thread(() -> {
            SystemClock.sleep(SETTLE_MS);
            try (Adb adb = Adb.connect(app, 0, null)) {
                String cec = adb.shell("cmd hdmi_control cec_setting get hdmi_cec_enabled; "
                        + "cmd hdmi_control cec_setting get system_audio_control");
                if (!cec.contains("hdmi_cec_enabled = 1") || !cec.contains("system_audio_control = 1")) return;
                AppLog.w("cec volume: " + adb.shell("cmd hdmi_control setsam on").trim());
            } catch (Exception e) {
                AppLog.w("cec volume: " + e.getMessage());
            }
        }, "cec-volume").start();
    }
}
