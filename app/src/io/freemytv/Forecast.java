package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.text.NumberFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.SimpleTimeZone;
import java.util.TimeZone;

/**
 * The weather screen's forecast (WeatherActivity): now in detail, the next 24 hours, the next 7 days and the air
 * quality, from Open-Meteo (no key). Fetched only while that screen is open, and cached in its own file: the screen
 * runs in its own process (:weather), and the home screen's cache (HomeData) belongs to :home.
 */
final class Forecast {
    static final int HOURS = 24, DAYS = 7;
    /** Fetched again after this long while the screen is open (and when it opens). */
    static final long FRESH_FOR = 15 * 60_000L;

    private static final String CURRENT = "temperature_2m,apparent_temperature,relative_humidity_2m,is_day,weather_code,"
            + "precipitation,pressure_msl,wind_speed_10m,wind_direction_10m,wind_gusts_10m,uv_index,visibility";
    private static final String HOURLY = "temperature_2m,precipitation_probability,precipitation,weather_code,is_day,uv_index";
    private static final String DAILY = "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,"
            + "precipitation_sum,wind_speed_10m_max,wind_gusts_10m_max,uv_index_max,sunrise,sunset";

    // Now
    double temp = Double.NaN, feels = Double.NaN, humidity = Double.NaN, precipitation = Double.NaN;
    double pressure = Double.NaN, wind = Double.NaN, windFrom = Double.NaN, gusts = Double.NaN;
    double uv = Double.NaN, visibility = Double.NaN;   // visibility in meters
    int code = -1;
    boolean isDay = true;
    // The next hours, from the current one
    int hourCount;
    final long[] hourAt = new long[HOURS];
    final double[] hourTemp = new double[HOURS], hourRain = new double[HOURS], hourUv = new double[HOURS];
    final int[] hourChance = new int[HOURS], hourCode = new int[HOURS];
    final boolean[] hourDay = new boolean[HOURS];
    // Today and the next days (index 0 = today, in the place's own time zone)
    int dayCount;
    final long[] dayAt = new long[DAYS], sunrise = new long[DAYS], sunset = new long[DAYS];
    final double[] dayMax = new double[DAYS], dayMin = new double[DAYS], dayRain = new double[DAYS];
    final double[] dayWind = new double[DAYS], dayGusts = new double[DAYS], dayUv = new double[DAYS];
    final int[] dayCode = new int[DAYS], dayChance = new int[DAYS];
    // Air quality (US AQI and PM2.5 in µg/m³); -1 / NaN without it
    int airIndex = -1;
    double pm25 = Double.NaN;

    /** The place's time zone (Open-Meteo's offset): the days and their names are local to it. */
    TimeZone zone = TimeZone.getDefault();
    /** When this was fetched (0 = only the home screen's summary, waiting for the full forecast), and for where. */
    long fetchedAt;
    String place = "";

    boolean hasNow() {
        return !Double.isNaN(temp);
    }

    // MARK: - Fetching

    /** Both answers for a place, as they came: the forecast, and the air quality (null when that one failed). */
    private static String[] download(String place) throws Exception {
        String base = "https://api.open-meteo.com/v1/forecast?" + place + "&current=" + CURRENT + "&hourly=" + HOURLY
                + "&daily=" + DAILY + "&timezone=auto&forecast_days=" + (DAYS + 1);
        String body;
        try {
            body = HomeData.get(base + "&forecast_hours=" + (HOURS + 1), null);
        } catch (IOException e) {
            if (!"HTTP 400".equals(e.getMessage())) throw e;
            body = HomeData.get(base, null);   // a server without forecast_hours: every hour of the days instead
        }
        String air = null;
        try {
            air = HomeData.get("https://air-quality-api.open-meteo.com/v1/air-quality?" + place
                    + "&current=us_aqi,pm2_5&timezone=auto", null);
        } catch (IOException e) {
            AppLog.w("air quality: " + e.getMessage());
        }
        return new String[] {body, air};
    }

    /** The query's place, and the cache's key: two forecasts for the same coordinates are the same forecast. */
    static String coordinates(double latitude, double longitude) {
        return String.format(Locale.US, "latitude=%.3f&longitude=%.3f", latitude, longitude);
    }

    // MARK: - Cache (this screen's own file)

    private static SharedPreferences cache(Context c) {
        return c.getSharedPreferences("forecast", Context.MODE_PRIVATE);
    }

    private static void save(Context c, String place, String body, String air, long at) {
        cache(c).edit().putString("place", place).putString("body", body).putString("air", air).putLong("at", at).apply();
    }

    /** The last forecast fetched (for whichever place: `place` says), or null (none yet, or unreadable). */
    static Forecast cached(Context c) {
        SharedPreferences p = cache(c);
        String body = p.getString("body", null);
        if (body == null) return null;
        try {
            Forecast f = parse(body, p.getString("air", null));
            f.fetchedAt = p.getLong("at", 0);
            f.place = p.getString("place", "");
            return f;
        } catch (Exception e) {
            return null;
        }
    }

    /** Fetches and caches; the answers are kept as they came, so a cached forecast is parsed the same way. */
    static Forecast fetchAndSave(Context c, double latitude, double longitude) throws Exception {
        String place = coordinates(latitude, longitude);
        String[] answers = download(place);
        Forecast f = parse(answers[0], answers[1]);
        f.fetchedAt = System.currentTimeMillis();
        f.place = place;
        save(c, place, answers[0], answers[1], f.fetchedAt);
        return f;
    }

    /** Until the first full forecast: what the home screen already knows (now, today's high and low, a few hours). */
    static Forecast fromHome(HomeData.Weather w) {
        Forecast f = new Forecast();
        f.temp = w.temp;
        f.code = w.code;
        f.isDay = w.isDay;
        if (!Double.isNaN(w.max)) {
            f.dayCount = 1;
            f.dayAt[0] = System.currentTimeMillis();
            f.dayMax[0] = w.max;
            f.dayMin[0] = w.min;
            f.dayCode[0] = w.code;
            f.dayChance[0] = -1;
            f.dayRain[0] = f.dayWind[0] = f.dayGusts[0] = f.dayUv[0] = Double.NaN;
            f.sunrise[0] = w.sunrise;
            f.sunset[0] = w.sunset;
        }
        for (int i = 0; i < w.hours.size() && i < HOURS; i++) {
            f.hourAt[i] = w.hours.get(i);
            f.hourTemp[i] = w.hourTemp.get(i);
            f.hourCode[i] = w.hourCode.get(i);
            f.hourDay[i] = w.hourDay.get(i);
            f.hourChance[i] = -1;
            f.hourRain[i] = f.hourUv[i] = Double.NaN;
            f.hourCount = i + 1;
        }
        return f;
    }

    // MARK: - Parsing

    static Forecast parse(String body, String air) throws Exception {
        JSONObject o = new JSONObject(body);
        Forecast f = new Forecast();
        // The offset Open-Meteo sends, not its zone's name: an old TV's time zone database may get that zone wrong.
        if (o.has("utc_offset_seconds")) f.zone = new SimpleTimeZone(o.getInt("utc_offset_seconds") * 1000, "forecast");
        JSONObject now = o.getJSONObject("current");
        f.temp = now.getDouble("temperature_2m");
        f.feels = now.optDouble("apparent_temperature");
        f.humidity = now.optDouble("relative_humidity_2m");
        f.precipitation = now.optDouble("precipitation");
        f.pressure = now.optDouble("pressure_msl");
        f.wind = now.optDouble("wind_speed_10m");
        f.windFrom = now.optDouble("wind_direction_10m");
        f.gusts = now.optDouble("wind_gusts_10m");
        f.uv = now.optDouble("uv_index");
        f.visibility = now.optDouble("visibility");
        f.code = now.optInt("weather_code", -1);
        f.isDay = now.optInt("is_day", 1) == 1;
        parseHours(f, o.optJSONObject("hourly"));
        parseDays(f, o.optJSONObject("daily"));
        if (air != null) {
            try {
                JSONObject a = new JSONObject(air).getJSONObject("current");
                f.airIndex = a.isNull("us_aqi") ? -1 : a.optInt("us_aqi", -1);
                f.pm25 = a.optDouble("pm2_5");
            } catch (Exception ignored) {
            }
        }
        return f;
    }

    /** From the current hour on (the one that started before now), up to HOURS. */
    private static void parseHours(Forecast f, JSONObject h) throws ParseException {
        if (h == null) return;
        JSONArray times = h.optJSONArray("time");
        if (times == null) return;
        long now = System.currentTimeMillis();
        int n = 0;
        for (int i = 0; i < times.length() && n < HOURS; i++) {
            long at = localTime(times.optString(i), f.zone);
            if (at + 3600_000L <= now) continue;   // over
            f.hourAt[n] = at;
            f.hourTemp[n] = number(h, "temperature_2m", i);
            f.hourChance[n] = (int) Math.round(number(h, "precipitation_probability", i, -1));
            f.hourRain[n] = number(h, "precipitation", i);
            f.hourCode[n] = (int) number(h, "weather_code", i, -1);
            f.hourDay[n] = number(h, "is_day", i, 1) == 1;
            f.hourUv[n] = number(h, "uv_index", i);
            n++;
        }
        f.hourCount = n;
    }

    private static void parseDays(Forecast f, JSONObject d) throws ParseException {
        if (d == null) return;
        JSONArray times = d.optJSONArray("time");
        if (times == null) return;
        SimpleDateFormat day = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        day.setTimeZone(f.zone);
        long today = startOfDay(System.currentTimeMillis(), f.zone);
        int n = 0;
        for (int i = 0; i < times.length() && n < DAYS; i++) {
            long at = day.parse(times.optString(i)).getTime();
            if (at < today) continue;   // the TV was off past midnight: yesterday isn't "today"
            f.dayAt[n] = at;
            f.dayCode[n] = (int) number(d, "weather_code", i, -1);
            f.dayMax[n] = number(d, "temperature_2m_max", i);
            f.dayMin[n] = number(d, "temperature_2m_min", i);
            f.dayChance[n] = (int) Math.round(number(d, "precipitation_probability_max", i, -1));
            f.dayRain[n] = number(d, "precipitation_sum", i);
            f.dayWind[n] = number(d, "wind_speed_10m_max", i);
            f.dayGusts[n] = number(d, "wind_gusts_10m_max", i);
            f.dayUv[n] = number(d, "uv_index_max", i);
            JSONArray rise = d.optJSONArray("sunrise"), set = d.optJSONArray("sunset");
            f.sunrise[n] = rise == null || rise.isNull(i) ? 0 : localTime(rise.optString(i), f.zone);
            f.sunset[n] = set == null || set.isNull(i) ? 0 : localTime(set.optString(i), f.zone);
            n++;
        }
        f.dayCount = n;
    }

    private static double number(JSONObject o, String key, int i) {
        return number(o, key, i, Double.NaN);
    }

    private static double number(JSONObject o, String key, int i, double missing) {
        JSONArray a = o.optJSONArray(key);
        if (a == null || i >= a.length() || a.isNull(i)) return missing;
        return a.optDouble(i, missing);
    }

    private static long localTime(String iso, TimeZone zone) throws ParseException {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US);
        f.setTimeZone(zone);
        return f.parse(iso).getTime();
    }

    static long startOfDay(long at, TimeZone zone) {
        long offset = zone.getOffset(at);
        return Math.floorDiv(at + offset, 86_400_000L) * 86_400_000L - offset;
    }

    // MARK: - Units (Open-Meteo answers in metric; Fahrenheit means the US units)

    static String speed(double kmh) {
        if (Double.isNaN(kmh)) return "—";
        return HomeData.fahrenheit() ? Math.round(kmh / 1.609344) + " mph" : Math.round(kmh) + " km/h";
    }

    static String distance(double meters) {
        if (Double.isNaN(meters)) return "—";
        double v = HomeData.fahrenheit() ? meters / 1609.344 : meters / 1000;
        String unit = HomeData.fahrenheit() ? " mi" : " km";
        return (v >= 10 ? String.valueOf(Math.round(v)) : decimal(v, 1)) + unit;
    }

    static String rain(double mm) {
        if (Double.isNaN(mm)) return "—";
        if (HomeData.fahrenheit()) return decimal(mm / 25.4, 2) + " in";
        return decimal(mm, mm >= 10 ? 0 : 1) + " mm";
    }

    static String pressure(double hpa) {
        return Double.isNaN(hpa) ? "—" : Math.round(hpa) + " hPa";
    }

    static String percent(double v) {
        return Double.isNaN(v) || v < 0 ? "—" : Math.round(v) + "%";
    }

    private static String decimal(double v, int digits) {
        NumberFormat f = NumberFormat.getNumberInstance(Ui.locale());
        f.setMinimumFractionDigits(digits);
        f.setMaximumFractionDigits(digits);
        return f.format(v);
    }

    /** N, NE, E… where the wind comes from (in Portuguese, L for east and O for west). */
    static String compass(double degrees) {
        if (Double.isNaN(degrees)) return "";
        String[] names = Ui.en || Ui.language.equals("de") ? new String[] {"N", "NE", "E", "SE", "S", "SW", "W", "NW"}
                : Ui.es ? new String[] {"N", "NE", "E", "SE", "S", "SO", "O", "NO"}
                : new String[] {"N", "NE", "L", "SE", "S", "SO", "O", "NO"};
        return names[(int) Math.round(((degrees % 360) + 360) % 360 / 45) % 8];
    }

    // MARK: - In words

    /** The UV index's band, as the WHO names it. */
    static String uvLevel(double uv) {
        if (Double.isNaN(uv)) return "";
        if (uv < 3) return Ui.tr("Baixo", "Low", "Bajo");
        if (uv < 6) return Ui.tr("Moderado", "Moderate", "Moderado");
        if (uv < 8) return Ui.tr("Alto", "High", "Alto");
        if (uv < 11) return Ui.tr("Muito alto", "Very High", "Muy alto");
        return Ui.tr("Extremo", "Extreme", "Extremo");
    }

    /** The US AQI's band. */
    static String airLevel(int aqi) {
        if (aqi < 0) return "";
        if (aqi <= 50) return Ui.tr("Boa", "Good", "Buena");
        if (aqi <= 100) return Ui.tr("Moderada", "Moderate", "Moderada");
        if (aqi <= 150) return Ui.tr("Ruim para sensíveis", "Unhealthy for Some", "Mala para sensibles");
        if (aqi <= 200) return Ui.tr("Ruim", "Unhealthy", "Mala");
        if (aqi <= 300) return Ui.tr("Muito ruim", "Very Unhealthy", "Muy mala");
        return Ui.tr("Perigosa", "Hazardous", "Peligrosa");
    }

    static String humidityLevel(double h) {
        if (Double.isNaN(h)) return "";
        if (h < 30) return Ui.tr("Ar seco", "Dry air", "Aire seco");
        if (h < 70) return Ui.tr("Confortável", "Comfortable", "Confortable");
        return Ui.tr("Ar úmido", "Humid air", "Aire húmedo");
    }

    static String visibilityLevel(double meters) {
        if (Double.isNaN(meters)) return "";
        if (meters >= 10_000) return Ui.tr("Ótima", "Excellent", "Excelente");
        if (meters >= 4_000) return Ui.tr("Boa", "Good", "Buena");
        if (meters >= 1_000) return Ui.tr("Moderada", "Moderate", "Moderada");
        return Ui.tr("Baixa", "Poor", "Baja");
    }
}
