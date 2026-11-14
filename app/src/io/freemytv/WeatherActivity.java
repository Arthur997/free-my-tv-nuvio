package io.freemytv;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;

/**
 * The weather screen (WeatherView), opened only from the weather card in the home screen's drawer. Its own process
 * (:weather): nothing of it runs or stays in memory with the home screen, and it fetches only while it's on screen.
 * The first time, it asks for the TV's location (Wi-Fi based) to forecast for where the TV is, not the city's center.
 */
public class WeatherActivity extends Activity {
    private static final int ASK_LOCATION = 1;
    private WeatherView view;
    private WeatherLocation location;
    /** The location question is on screen: this screen is behind it, not gone. */
    private boolean asking;

    static boolean open(Context c) {
        try {
            c.startActivity(new Intent(c, WeatherActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (RuntimeException e) {
            AppLog.w("weather screen: " + e.getMessage());
            return false;
        }
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this);
        location = new WeatherLocation(this);
        view = new WeatherView(this);
        setContentView(view);
    }

    @Override protected void onResume() {
        super.onResume();
        view.resume();
        if (asking) return;   // back from the question: onRequestPermissionsResult finds the place
        if (!WeatherLocation.allowed(this) && !askedBefore()) {
            askLocation();
            return;
        }
        location.find(view::setPlace);
    }

    @Override protected void onPause() {
        location.stop();
        view.pause();
        super.onPause();
    }

    /** Gone from the screen (Home, another app): closed, so its process has nothing left to keep. */
    @Override protected void onStop() {
        super.onStop();
        if (!asking) finish();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (RemoteKeys.onKey(this, event)) return true;
        if (view.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    /** Back on the first page: the screen closes (the view only answers it on the days' page). */
    @Override public void onBackPressed() {
        finish();
    }

    // MARK: - The location question (asked once; Android's own settings change it later)

    private boolean askedBefore() {
        return getSharedPreferences("weather-screen", MODE_PRIVATE).getBoolean("askedLocation", false);
    }

    private void askLocation() {
        getSharedPreferences("weather-screen", MODE_PRIVATE).edit().putBoolean("askedLocation", true).apply();
        asking = true;
        view.setPlace(WeatherLocation.city());   // the city's forecast meanwhile
        requestPermissions(new String[] {Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION},
                ASK_LOCATION);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode != ASK_LOCATION) return;
        asking = false;
        location.find(view::setPlace);
    }
}
