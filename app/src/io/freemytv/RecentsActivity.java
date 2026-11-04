package io.freemytv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;

/**
 * Recent apps: the apps open now, over whatever is on screen, to open one or close it (RecentsView). Opened from the
 * Control Center or from the remote button picked in Settings > Remote Buttons; that button again closes it.
 * In the :home process, translucent, out of the recent tasks itself.
 */
public class RecentsActivity extends Activity {
    private RecentsView view;

    static boolean open(Context c) {
        try {
            c.startActivity(new Intent(c, RecentsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (RuntimeException e) {
            AppLog.w("recent apps: " + e.getMessage());
            return false;
        }
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this);
        view = new RecentsView(this);
        setContentView(view);
    }

    /** The remote's button again while it's open: it closes, as a toggle. */
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        finish();
    }

    /** On screen (the Control Center's button then opens its own panel over it, not the home screen's). */
    static volatile boolean showing;

    @Override protected void onResume() {
        super.onResume();
        showing = true;
        view.resume();
    }

    @Override protected void onPause() {
        showing = false;
        view.pause();
        super.onPause();
    }

    @Override protected void onDestroy() {
        view.destroy();
        super.onDestroy();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (RemoteKeys.onKey(this, event)) return true;
        if (view.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }
}
