package io.freemytv;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.view.InputDevice;
import android.view.KeyEvent;

/**
 * The remote's app buttons on TCL TVs. The stock home screen opens the apps for Netflix, YouTube and Prime (and TCL
 * Channel); with it off, the keys still arrive, so we open the app the person picked (Settings > Remote Buttons).
 * The system opens Disney+ and Globoplay itself, and ≡, 123 and ⓘ are the TV's: they're ours only once given an app,
 * the Control Center or Recent apps (until then "Nothing" leaves them to the TV). The YouTube button (BUTTON_3) never
 * reaches an app's window; only the accessibility service's key filter (RemoteKeyService) sees it.
 * On Vestel TVs and Formovie (Xiaomi) projectors only the Netflix button is ours: the system opens Netflix itself (a
 * global key, after the filter).
 * On KTC TVs (NextStar) only the Tabii button is ours, a global key too: the TV's hot-key receiver opens Tabii with it.
 * On Sony TVs, the app buttons (and the Google Play button of older remotes) are global keys for Sony's own receiver;
 * the filter sees them first, the same way.
 */
final class RemoteKeys {
    private RemoteKeys() {}

    /** Some TCL remotes (BT_RC833) send TCL's own YouTube key instead of BUTTON_3: the same button. */
    private static final int KEY_YOUTUBE_TCL = 4065;

    /**
     * The key codes are the brand's own: on other brands they can be other buttons, so nothing here runs there.
     * Vestel builds TVs sold under other brands (F&U...), so BRAND varies: check the maker too.
     */
    static final boolean TCL = is("TCL"), VESTEL = is("Vestel"), KTC = is("KTC"), SONY = is("Sony"), FORMOVIE = is("Formovie");
    /** Brands whose Netflix button is STEM_1, a global key (MediaTek): only that button is ours. */
    static final boolean STEM_NETFLIX = VESTEL || FORMOVIE;

    static boolean supported() {
        return TCL || STEM_NETFLIX || KTC || SONY;
    }

    private static boolean is(String maker) {
        return maker.equalsIgnoreCase(Build.MANUFACTURER) || maker.equalsIgnoreCase(Build.BRAND);
    }

    /** A button with no key code of its own (KEYCODE_UNKNOWN): told apart by its scan code, as UNKNOWN_KEY + scan code. */
    static final int UNKNOWN_KEY = 200_000;

    /**
     * Opens what this key was given on its first press. True if the key is ours and in use (it is then consumed). While
     * Remote Buttons waits for a button (Prefs.learning), the next one that isn't for moving around is taken instead.
     */
    static boolean onKey(Context c, KeyEvent e) {
        if (!supported()) return false;
        // BUTTON_3 is also a game controller's button: keys from a gamepad or a joystick are left alone.
        int source = e.getSource();
        if ((source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) return false;
        int keyCode = identity(e);
        boolean first = e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0;
        if (!reserved(e.getKeyCode()) && Prefs.learning()) {
            if (first) {
                AppLog.w("remote key " + keyCode + " taught (scan " + e.getScanCode() + ")");
                Prefs.learned(keyCode);
            }
            return true;
        }
        boolean brand = false;
        for (int key : Prefs.KEYS) brand |= key == keyCode;
        String pkg = Prefs.keyApp(keyCode);
        if (!brand && pkg.isEmpty()) return false;
        if (first) Prefs.keySeen(keyCode);
        if (pkg.isEmpty()) return false;   // "Nothing": the key goes on as if we weren't here
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
            AppLog.w("remote key " + e.getKeyCode() + " -> " + pkg);
            open(c, pkg);
        }
        return true;
    }

    /**
     * The button, as Remote Buttons stores it: its key code, with the cases where one code means several buttons
     * (Sony's app buttons, a button without a code) told apart by scan code, and TCL's second YouTube code folded in.
     */
    static int identity(KeyEvent e) {
        int keyCode = e.getKeyCode() == KEY_YOUTUBE_TCL ? Prefs.KEY_YOUTUBE : e.getKeyCode();
        // Sony sends BUTTON_9 for five app buttons (BUTTON_4 for Netflix, BUTTON_8 for Google Play): only the scan code
        // tells them apart.
        if (SONY && (keyCode == KeyEvent.KEYCODE_BUTTON_4 || keyCode == KeyEvent.KEYCODE_BUTTON_8
                || keyCode == KeyEvent.KEYCODE_BUTTON_9)) {
            return Prefs.SONY_KEY + e.getScanCode();
        }
        return keyCode == KeyEvent.KEYCODE_UNKNOWN ? UNKNOWN_KEY + e.getScanCode() : keyCode;
    }

    /** The keys for moving around, the TV's power and the volume: never taught, so the way back always works. */
    static boolean reserved(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_DPAD_DOWN: case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT: case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER: case KeyEvent.KEYCODE_BACK: case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_HOME: case KeyEvent.KEYCODE_POWER: case KeyEvent.KEYCODE_TV_POWER:
            case KeyEvent.KEYCODE_SLEEP: case KeyEvent.KEYCODE_WAKEUP: case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN: case KeyEvent.KEYCODE_VOLUME_MUTE:
                return true;
            default:
                return false;
        }
    }

    /**
     * What the button was given: the Control Center or Recent apps over any app (the same button again closes them), or
     * an app.
     */
    private static void open(Context c, String target) {
        if (Prefs.OPEN_CONTROL.equals(target)) {
            ControlActivity.open(c);
        } else if (Prefs.OPEN_RECENTS.equals(target)) {
            RecentsActivity.open(c);
        } else {
            Intent intent = launchIntent(c.getPackageManager(), target);
            if (intent == null) AppLog.w("remote key: " + target + " is not installed");
            else c.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }

    /** The app's TV launcher, or its plain one (kept here, not in HomeApps, so the key process stays small). */
    private static Intent launchIntent(PackageManager pm, String pkg) {
        Intent intent = pm.getLeanbackLaunchIntentForPackage(pkg);
        return intent != null ? intent : pm.getLaunchIntentForPackage(pkg);
    }
}
