package io.freemytv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.TextView;

/**
 * The Free My TV home screen (Android's "Home" role in Free My TV mode). It runs in the :home process: if it crashes,
 * the safety net in the main process keeps working. On the 3rd crash within 5 min, the Watchdog gives the home screen
 * back to the TV's original one.
 */
public class HomeActivity extends Activity {
    private HomeView home;
    private int prefsVersion = Prefs.version;   // settings changed while we were away: start over

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Watchdog.install(this);
        if (Watchdog.crashedTooOften(this)) {
            HomeMode.requestRevertHome(this);
            showCrashScreen();
            return;
        }
        Ui.init(this);
        home = new HomeView(this);
        setContentView(home);
        getWindow().setBackgroundDrawable(null);
    }

    /** How long the crash screen waits for the main process to give the home screen back before saying it couldn't. */
    private static final long REVERT_WAIT_MS = 20_000;
    private TextView crashText;

    /**
     * Doesn't try to draw again: a notice while the main process restores the original home screen. OK always opens
     * the TV's settings, so a failed revert (the TV's debugging off) never leaves a screen nothing answers on.
     */
    private void showCrashScreen() {
        crashText = new TextView(this);
        crashText.setText(Ui.tr("A tela inicial do Free My TV teve um problema.\nVoltando para a tela inicial original da TV…",
                "Free My TV's home screen ran into a problem.\nGoing back to the TV's original home screen…",
                "La pantalla de inicio de Free My TV tuvo un problema.\nVolviendo a la pantalla de inicio original de la TV…"));
        crashText.setTextColor(Color.WHITE);
        crashText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        crashText.setGravity(Gravity.CENTER);
        crashText.setBackgroundColor(Color.rgb(7, 11, 30));
        crashText.setFocusable(true);
        setContentView(crashText);
        crashText.postDelayed(this::checkReverted, REVERT_WAIT_MS);
    }

    private void checkReverted() {
        if (isFinishing() || HomeMode.state(this).optBoolean("homeReverted")) return;
        crashText.setText(Ui.tr("Não deu para voltar à tela inicial original da TV.\nOK abre os Ajustes da TV.",
                "Couldn't go back to the TV's original home screen.\nOK opens the TV's settings.",
                "No se pudo volver a la pantalla de inicio original de la TV.\nOK abre los Ajustes de la TV."));
    }

    /** On the crash screen: OK, Enter or Back open the TV's settings. */
    private boolean onCrashScreenKey(KeyEvent event) {
        int key = event.getKeyCode();
        boolean opens = key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER || key == KeyEvent.KEYCODE_BACK;
        if (opens && event.getAction() == KeyEvent.ACTION_UP) HomeApps.openSettings(this, android.provider.Settings.ACTION_SETTINGS);
        return opens;
    }

    @Override protected void onResume() {
        super.onResume();
        if (prefsVersion != Prefs.version) {
            prefsVersion = Prefs.version;
            HomeData.get(this).settingsChanged();
            recreate();   // a new language or city: draw everything again
            return;
        }
        if (home != null) home.resume();
        Updater.maybeCheck(this);
    }

    /** A setting the home screen changed itself (the shelf, from the app options): no need to start over. */
    void settingsSeen() {
        prefsVersion = Prefs.version;
    }

    @Override protected void onPause() {
        if (home != null) home.pause();
        super.onPause();
    }

    /**
     * Home button pressed on the home screen: back to the entry state (focus on the first app, sheet closed). From the
     * remote's Control Center button (ControlActivity, with OPEN_CONTROL): the panel under its button opens or closes.
     */
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (home == null) return;
        if (intent.getBooleanExtra(OPEN_CONTROL, false)) home.toggleControl();
        else home.reset(true);
    }

    static final String OPEN_CONTROL = "io.freemytv.OPEN_CONTROL";
    /** The home screen is on screen (resumed, or only covered by a translucent window): set on resume, cleared on stop. */
    static volatile boolean inFront;

    @Override protected void onStart() {
        super.onStart();
        inFront = true;
    }

    @Override protected void onStop() {
        inFront = false;
        super.onStop();
    }

    @Override protected void onDestroy() {
        if (home != null) home.destroy();
        super.onDestroy();
    }

    /**
     * Android asks for memory back (a TV has little): in another app (YouTube, Nuvio) the photos built for the cards go,
     * except the three on screen when it's only that we're hidden; they come back from disk when needed.
     */
    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        // The five cards on screen stay (coming back shows them at once); the TV really short of memory: those only.
        if (level >= TRIM_MEMORY_BACKGROUND) Recs.get(this).trim(Recs.ON_SCREEN);
        else if (level >= TRIM_MEMORY_RUNNING_LOW) Recs.get(this).trim(Recs.ON_SCREEN + 2);   // also when we are just hidden
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (crashText != null && onCrashScreenKey(event)) return true;
        if (RemoteKeys.onKey(this, event)) return true;
        if (home != null && home.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

}
