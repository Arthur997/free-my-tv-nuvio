package io.freemytv;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

/**
 * Where the weather screen asks the forecast for: the app's city (Settings > City), or, closer, the TV's own location
 * (Wi-Fi based, with the location permission the screen asks for once) when it is near that city. A city picked far
 * from the TV wins: it was chosen on purpose.
 */
final class WeatherLocation {
    /** The TV's fix counts only this close to the app's city (km), and only this fresh. */
    private static final double NEAR_KM = 50;
    private static final long FRESH_MS = 6 * 3600_000L, WAIT_MS = 10_000L;

    static final class Place {
        final double latitude, longitude;
        /** From the TV's location, not the city's center (rounded to ~1 km: steady between visits, and still finer than a forecast grid cell). */
        final boolean precise;

        Place(double latitude, double longitude, boolean precise) {
            this.latitude = precise ? Math.round(latitude * 100) / 100.0 : latitude;
            this.longitude = precise ? Math.round(longitude * 100) / 100.0 : longitude;
            this.precise = precise;
        }
    }

    interface Callback { void onPlace(Place place); }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private LocationManager manager;
    private LocationListener listener;
    private Callback waiting;

    WeatherLocation(Context c) {
        context = c.getApplicationContext();
    }

    /** The app's city (its center); null when no coordinates are set. */
    static Place city() {
        try {
            return new Place(Double.parseDouble(Prefs.latitude().trim()), Double.parseDouble(Prefs.longitude().trim()), false);
        } catch (RuntimeException e) {
            return null;
        }
    }

    static boolean allowed(Context c) {
        return c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * The TV's location when it has a recent one near the city, else asks for a new fix (up to 10 s); the city
     * otherwise. Calls back on the main thread, once.
     */
    void find(Callback callback) {
        stop();
        Place city = city();
        if (!allowed(context)) {
            callback.onPlace(city);
            return;
        }
        manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (manager == null) {
            callback.onPlace(city);
            return;
        }
        Location last = lastKnown();
        if (last != null && System.currentTimeMillis() - last.getTime() < FRESH_MS) {
            callback.onPlace(pick(last, city));
            return;
        }
        String provider = liveProvider();
        if (provider == null) {
            callback.onPlace(last != null ? pick(last, city) : city);
            return;
        }
        waiting = callback;
        listener = new LocationListener() {
            @Override public void onLocationChanged(Location l) { finish(pick(l, city)); }
            @Override public void onStatusChanged(String p, int status, Bundle extras) {}
            @Override public void onProviderEnabled(String p) {}
            @Override public void onProviderDisabled(String p) {}
        };
        try {
            manager.requestLocationUpdates(provider, 0, 0, listener, Looper.getMainLooper());
            main.postDelayed(() -> finish(last != null ? pick(last, city) : city), WAIT_MS);
        } catch (RuntimeException e) {   // SecurityException, or a provider that went away
            AppLog.w("location: " + e.getMessage());
            finish(city);
        }
    }

    void stop() {
        main.removeCallbacksAndMessages(null);
        if (manager != null && listener != null) {
            try {
                manager.removeUpdates(listener);
            } catch (RuntimeException ignored) {
            }
        }
        listener = null;
        waiting = null;
    }

    private void finish(Place place) {
        Callback c = waiting;
        stop();
        if (c != null) c.onPlace(place);
    }

    /** The TV's fix if it is near the app's city (or there is no city), else the city. */
    private static Place pick(Location l, Place city) {
        if (city == null || distanceKm(l.getLatitude(), l.getLongitude(), city.latitude, city.longitude) <= NEAR_KM) {
            return new Place(l.getLatitude(), l.getLongitude(), true);
        }
        return city;
    }

    /** The newest fix any provider kept. */
    private Location lastKnown() {
        Location best = null;
        for (String p : manager.getProviders(true)) {
            try {
                Location l = manager.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            } catch (RuntimeException ignored) {
            }
        }
        return best;
    }

    /** Google's fused provider (Android 12+), else the network one (Wi-Fi), else none. A TV has no GPS. */
    private String liveProvider() {
        java.util.List<String> on = manager.getProviders(true);
        if (Build.VERSION.SDK_INT >= 31 && on.contains("fused")) return "fused";
        if (on.contains(LocationManager.NETWORK_PROVIDER)) return LocationManager.NETWORK_PROVIDER;
        return null;
    }

    private static double distanceKm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1), dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 6371 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
