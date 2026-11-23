package io.freemytv;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Free My TV mode on Fire TV. Fire OS blocks ADB from the device itself (Fire OS 7.7 tested: 127.0.0.1:5555 and the
 * stick's own address accept the socket and never answer), so HomeMode's switch can't run here. It isn't needed
 * either: nothing goes off (Amazon's launcher stays, FireTv opens ours over it), and the one change, our accessibility
 * service, is a secure setting this app writes itself once a computer has granted WRITE_SECURE_SETTINGS (GRANT).
 * The screensaver stays Amazon's.
 */
final class FireTvMode {
    private FireTvMode() {}

    /** Run once from a computer, over USB or the network. READ_LOGS lets FireTv see Home in time (FireCover). */
    static final String GRANT = "adb shell \"pm grant " + HomeMode.PKG + " " + Manifest.permission.WRITE_SECURE_SETTINGS
            + "; pm grant " + HomeMode.PKG + " " + Manifest.permission.READ_LOGS + "\"";

    static boolean allowed(Context c) {
        return c.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
    }

    /** Free My TV mode (`free`) or Original: only the service changes. */
    static void apply(Context c, boolean free) {
        if (!allowed(c)) {
            HomeMode.done(c, Ui.trf("No Fire TV, o computador precisa liberar o Free My TV uma vez. Com o Fire TV conectado ao computador pelo ADB, rode: {command}", "command", GRANT));
            return;
        }
        try {
            Watchdog.clear(c);
            HomeMode.remoteService(c, settings(c), free);
            JSONObject s = HomeMode.state(c);
            s.put("mode", free ? HomeMode.FREE : HomeMode.ORIGINAL).put("since", System.currentTimeMillis())
                    .put("homeReverted", false).put("disabled", new JSONArray());
            HomeMode.save(c, s);
            HomeMode.notice(c, free ? Ui.tr("Free My TV ligado: o botão Home agora abre o Free My TV.")
                    : Ui.tr("Modo original: Início, tela de descanso e apps de fábrica de volta."));
            HomeMode.done(c, null);
            c.startActivity(free ? new Intent(c, FireTv.HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) : amazonHome());
        } catch (Exception e) {
            HomeMode.done(c, free ? Ui.trf("Não deu para ligar o Free My TV: {error}", "error", e.getMessage())
                    : Ui.trf("Não deu para voltar ao original: {error}", "error", e.getMessage()));
        }
    }

    /** The watchdog's revert (our home screen crashed 3 times): the service goes off, Amazon's home stays. */
    static void revertHome(Context c) {
        JSONObject s = HomeMode.state(c);
        if (!HomeMode.FREE.equals(s.optString("mode")) || s.optBoolean("homeReverted") || !allowed(c)) return;
        try {
            HomeMode.remoteService(c, settings(c), false);
            s = HomeMode.state(c);
            s.put("homeReverted", true);
            HomeMode.save(c, s);
            HomeMode.notice(c, Ui.tr("O Início do Free My TV caiu 3 vezes seguidas; voltei para o Início original."));
            c.startActivity(amazonHome());
        } catch (Exception e) {
            AppLog.w("could not give the home screen back: " + e.getMessage());
        }
    }

    /** After a start or an update of this app (Android takes the service out then): back in, in Free My TV mode. */
    static void ensureService(Context c) {
        JSONObject s = HomeMode.state(c);
        if (!HomeMode.FREE.equals(s.optString("mode")) || s.optBoolean("homeReverted") || !allowed(c)) return;
        try {
            HomeMode.remoteService(c, settings(c), true);
        } catch (Exception e) {
            AppLog.w("remote buttons service: " + e.getMessage());
        }
    }

    private static Intent amazonHome() {
        return new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    /** The secure settings, written by this app (WRITE_SECURE_SETTINGS). */
    private static HomeMode.SecureSettings settings(Context c) {
        ContentResolver resolver = c.getContentResolver();
        return new HomeMode.SecureSettings() {
            @Override public String get(String key) {
                String value = Settings.Secure.getString(resolver, key);
                return value == null ? "" : value;
            }

            @Override public void put(String key, String value) {
                Settings.Secure.putString(resolver, key, value);
            }
        };
    }
}
