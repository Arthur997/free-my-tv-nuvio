package io.freemytv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;

/**
 * The football screen (FootballView), opened only from the football card in the home screen's drawer. Its own process
 * (:sports), as the weather screen: nothing of it stays in the home screen's memory, it fetches only while open, and
 * its process ends when it closes.
 */
public class FootballActivity extends Activity {
    private FootballView view;

    static boolean open(Context c) {
        try {
            c.startActivity(new Intent(c, FootballActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (RuntimeException e) {
            AppLog.w("football screen: " + e.getMessage());
            return false;
        }
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this);
        view = new FootballView(this);
        setContentView(view);
    }

    @Override protected void onResume() {
        super.onResume();
        view.resume();
    }

    @Override protected void onPause() {
        view.pause();
        super.onPause();
    }

    /** Gone from the screen (Home, another app): closed, so its process has nothing left to keep. */
    @Override protected void onStop() {
        super.onStop();
        finish();
    }

    /** Closed for good: its process (it holds only this screen) ends with it, instead of staying cached. */
    @Override protected void onDestroy() {
        super.onDestroy();
        if (isFinishing()) android.os.Process.killProcess(android.os.Process.myPid());
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (RemoteKeys.onKey(this, event)) return true;
        if (view.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    @Override public void onBackPressed() {
        finish();
    }
}
