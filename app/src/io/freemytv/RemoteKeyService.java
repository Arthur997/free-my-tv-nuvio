package io.freemytv;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Sees the remote's keys before any app does, so the app buttons work from inside Netflix, Disney and the rest. The
 * system waits for it on every key (up to half a second), so it runs in a process of its own and does next to nothing.
 */
public class RemoteKeyService extends AccessibilityService {
    /** Always running in Free My TV mode: here the home theater's CEC volume comes back after a start or a wake. */
    private final BroadcastReceiver screenOn = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            CecVolume.ensure(context);
        }
    };

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        registerReceiver(screenOn, new IntentFilter(Intent.ACTION_SCREEN_ON));
        CecVolume.ensure(this);
    }

    @Override public void onDestroy() {
        unregisterReceiver(screenOn);
        super.onDestroy();
    }

    @Override protected boolean onKeyEvent(KeyEvent event) {
        return RemoteKeys.onKey(this, event);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override public void onInterrupt() {}
}
