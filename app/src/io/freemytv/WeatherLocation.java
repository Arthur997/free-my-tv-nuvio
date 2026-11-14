package io.freemytv;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
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
 * The screen never waits for it: it gets the city (or the TV's spot found on an earlier visit) at once, and the TV's
 * spot later if one comes. A TV doesn't move, so a spot found once is kept; and many TVs never give one (Google's
 * location service on a TCL C6K had no fix at all), so after a try without one the next try waits a day.
 */
final class WeatherLocation {
    /** The TV's fix counts only this close to the app's city (km). */
    private static final double NEAR_KM = 50;
    private static final long WAIT_MS = 15_000L, RETRY_AFTER_MS = 24 * 3600_000L, REFRESH_AFTER_MS = 7 * 24 * 3600_000L;

    static final class Place {
        final double latitude, longitude;
        /** From the TV's location (rounded to ~1 km: steady between visits, finer than a forecast grid cell). */
        final boolean precise;

        Place(double latitude, double longitude, boolean precise) {
            this.latitude = precise ? Math.round(latitude * 100) / 100.0 : latitude;
            this.longitude = precise ? Math.round(longitude * 100) / 100.0 : longitude;
            this.precise = precise;
        }

        boolean same(Place o) {
            return o != null && o.precise == precise && o.latitude == latitude && o.longitude == longitude;
        }
    }

    /** Called with the place to use now, and again if a better one turns up. */
    interface Callback { void onPlace(Place place); }

    private final Context context;
    private final SharedPreferences saved;
    private final Handler main = new Handler(Looper.getMainLooper());
    private LocationManager manager;
    private LocationListener listener;

    WeatherLocation(Context c) {
        context = c.getApplicationContext();
        saved = context.getSharedPreferences("weather-screen", Context.MODE_PRIVATE);
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

    /** The place to use now; then, in the background, the TV's spot (a callback again only if it's a different one). */
    void find(Callback callback) {
        stop();
        Place city = city();
        Place known = savedSpot(city);
        Place now = known != null ? known : city;
        callback.onPlace(now);
        if (!allowed(context) || !worthLooking(known)) return;
        manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (manager == null) return;
        Location last = lastKnown();
        if (last != null) {
            found(last, city, now, callback);
            return;
        }
        String provider = liveProvider();
        if (provider == null) {
            saved.edit().putLong("triedAt", System.currentTimeMillis()).apply();
            return;
        }
        listener = new LocationListener() {
            @Override public void onLocationChanged(Location l) {
                stop();
                found(l, city, now, callback);
            }
            @Override public void onStatusChanged(String p, int status, Bundle extras) {}
            @Override public void onProviderEnabled(String p) {}
            @Override public void onProviderDisabled(String p) {}
        };
        try {
            manager.requestLocationUpdates(provider, 0, 0, listener, Looper.getMainLooper());
            main.postDelayed(() -> {
                stop();
                saved.edit().putLong("triedAt", System.currentTimeMillis()).apply();
                AppLog.w("location: no fix from " + provider + " in " + WAIT_MS / 1000 + " s, using the city");
            }, WAIT_MS);
        } catch (RuntimeException e) {   // SecurityException, or a provider that went away
            AppLog.w("location: " + e.getMessage());
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
    }

    /** A fix: kept (near the city), and given to the screen if it changes the place. */
    private void found(Location l, Place city, Place now, Callback callback) {
        Place spot = pick(l, city);
        if (spot.precise) {
            saved.edit().putString("latitude", String.valueOf(spot.latitude)).putString("longitude", String.valueOf(spot.longitude))
                    .putLong("foundAt", System.currentTimeMillis()).remove("triedAt").apply();
            AppLog.w("location: the TV's spot, " + Math.round(distanceKm(l.getLatitude(), l.getLongitude(),
                    city == null ? l.getLatitude() : city.latitude, city == null ? l.getLongitude() : city.longitude)) + " km from the city's center");
        }
        if (!spot.same(now)) callback.onPlace(spot);
    }

    /** The TV's spot found before, if it is still near the app's city. */
    private Place savedSpot(Place city) {
        try {
            double lat = Double.parseDouble(saved.getString("latitude", "")), lon = Double.parseDouble(saved.getString("longitude", ""));
            if (city != null && distanceKm(lat, lon, city.latitude, city.longitude) > NEAR_KM) return null;
            return new Place(lat, lon, true);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Look again: never found (but not tried in the last day), or found over a week ago. */
    private boolean worthLooking(Place known) {
        long now = System.currentTimeMillis();
        if (known != null) return now - saved.getLong("foundAt", 0) > REFRESH_AFTER_MS;
        long tried = saved.getLong("triedAt", 0);
        return tried == 0 || now < tried || now - tried > RETRY_AFTER_MS;
    }

    /** The TV's fix if it is near the app's city (or there is no city), else the city. */
    private static Place pick(Location l, Place city) {
        if (city == null || distanceKm(l.getLatitude(), l.getLongitude(), city.latitude, city.longitude) <= NEAR_KM) {
            return new Place(l.getLatitude(), l.getLongitude(), true);
        }
        return city;
    }

    /** The newest fix any provider kept (a TV stays put: its age doesn't matter). */
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
