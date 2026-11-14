package io.freemytv;

import android.app.Activity;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;

import java.text.SimpleDateFormat;
import java.util.concurrent.ExecutorService;

/**
 * The weather screen, in the home screen's look: light by day, dark at night, every panel made of the shelf's glass
 * (DockGlass), the focused day on a white pill. Two pages, one above the other:
 *  - Today: now in large type with a sentence about the day (WeatherSummary), the next 24 hours (temperature curve,
 *    symbols and the chance of rain), and eight tiles: rain, humidity, wind, UV, air quality, pressure, visibility,
 *    and the sun's arc between sunrise and sunset.
 *  - The next 7 days (↓ or OK): one row per day with its range on the week's scale; the focused day's details beside.
 * Text is made when the forecast, the minute or the focus changes, never while drawing. The pages are layers (like the
 * home screen's): scrolling moves them without redrawing their glass, and a focus move redraws only the days' rows.
 * Measurements are in a 1920×1080 space (k = width / 1920).
 */
final class WeatherView extends ViewGroup implements Choreographer.FrameCallback {
    private static final float M = 80, R = 1840, TITLE_BASE = 116, PANEL_RADIUS = 30;
    private static final int TODAY = 0, DAYS_BACK = 1, DAYS_FRONT = 2;
    // Today
    private static final float HOURS_L = 740, HOURS_T = 160, HOURS_B = 584;
    private static final float TILES_T = 612, TILE_H = 196, TILE_GAP = 24;
    private static final int TILES = 8, TILE_RAIN = 0, TILE_HUMIDITY = 1, TILE_WIND = 2, TILE_UV = 3, TILE_AIR = 4,
            TILE_PRESSURE = 5, TILE_VISIBILITY = 6, TILE_SUN = 7;
    // Next days
    private static final float DAYS_L = 80, DAYS_R = 1060, DAYS_T = 160, DAYS_B = 1028, ROW_TOP = 174, ROW_H = 120, ROW_PILL = 112;
    private static final float DETAIL_L = 1100;
    private static final int FACTS = 7;
    private static final int INK = 0xFF1D1D1F, DIM = 0xFF6E6E73;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Ui.background("weather");
    private final Spring page = new Spring(0.34f, 0.92f, 0);   // 0 = today, 1 = the next days
    private final Spring[] rowFocus = new Spring[Forecast.DAYS];
    private Forecast forecast;
    private WeatherLocation.Place place;
    private boolean fetching, fetchAgain, failed, running, frameQueued, okDown, backDown;
    private int day;   // the focused day on the second page
    private long lastNanos;
    private int w, h;
    private float k = 1;
    private boolean light = true;

    // Paints (made with the size and the theme)
    private Paint title, headerRight, condition, bigTemp, heroLine, panelTitle, hourLabel, hourTemp, chanceLabel;
    private Paint tileTitle, tileValue, tileSub, dayName, dayTemp, dayDim, dayChance, detailDate, detailTemp, detailText,
            factLabel, factValue, message;
    private TextPaint summaryPaint;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint background = new Paint(), shade = new Paint(), shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Shadows shadows = new Shadows();
    private final Paint glassShadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Scene scene = new Scene();
    private final DockGlass glass = new DockGlass(scene, shadows, glassShadow);
    private final Layer today, daysBack, daysFront;
    private final RectF rect = new RectF(), pill = new RectF();
    private final Path curve = new Path();

    // Text, made in prepare()
    private String titleText = "", headerText = "", tempText = "", conditionText = "", lineText = "", messageText = "";
    private String hoursTitle = "", daysTitle = "";
    private StaticLayout summary;
    private final String[] hourLabels = new String[Forecast.HOURS], hourTemps = new String[Forecast.HOURS],
            hourChances = new String[Forecast.HOURS];
    private final float[] curveX = new float[Forecast.HOURS], curveY = new float[Forecast.HOURS];
    private final String[] tileTitles = new String[TILES], tileValues = new String[TILES], tileSubs = new String[TILES];
    private static final String[] TILE_SYMBOLS = {"umbrella", "humidity", "air", "uv", "air_quality", "pressure", "visibility", "sunrise"};
    private float uvAt = -1, airAt = -1, windTo = Float.NaN, sunAt = -1;
    private String sunriseText = "", sunsetText = "";
    private final String[] dayNames = new String[Forecast.DAYS], dayMaxes = new String[Forecast.DAYS],
            dayMins = new String[Forecast.DAYS], dayChances = new String[Forecast.DAYS];
    private double weekLow, weekHigh;
    private String detailDateText = "", detailTempText = "", detailCondition = "";
    private final String[] factLabels = new String[FACTS], factValues = new String[FACTS];
    private static final String[] FACT_SYMBOLS = {"umbrella", "water_drop", "air", "air", "uv", "sunrise", "sunset"};

    private final Runnable minute = new Runnable() {
        @Override public void run() {
            if (!running) return;
            prepare();
            if (forecast == null || System.currentTimeMillis() - forecast.fetchedAt > Forecast.FRESH_FOR) fetch();
            redraw();
            handler.postDelayed(this, 60_000);
        }
    };

    WeatherView(Activity a) {
        super(a);
        setFocusable(true);
        setWillNotDraw(false);   // the background is the group's own
        today = new Layer(a, TODAY);
        daysBack = new Layer(a, DAYS_BACK);
        daysFront = new Layer(a, DAYS_FRONT);
        // On the GPU (not on Android 8 to 11, as the home screen): the glass is drawn once, then only moved.
        for (View v : new View[] {today, daysBack}) v.setLayerType(Ui.LEGACY ? LAYER_TYPE_NONE : LAYER_TYPE_HARDWARE, null);
        for (View v : new View[] {today, daysBack, daysFront}) {
            v.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(v);
        }
        for (int i = 0; i < rowFocus.length; i++) rowFocus[i] = Spring.focus(i == 0 ? 1 : 0);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
    }

    // MARK: - Lifecycle and data

    void resume() {
        running = true;
        if (forecast == null) {
            forecast = Forecast.cached(getContext());
            if (forecast == null) {
                HomeData.Weather home = HomeData.get(getContext()).weather;   // the home screen's own cache, at once
                if (!Double.isNaN(home.temp)) forecast = Forecast.fromHome(home);
            }
        }
        if (w > 0) {
            boolean wasLight = light;
            light = !HomeData.get(getContext()).isNight(System.currentTimeMillis());
            if (light != wasLight) makeTheme();
        }
        prepare();
        handler.postDelayed(minute, 60_000);
        redraw();
    }

    void pause() {
        running = false;
        handler.removeCallbacks(minute);
        Choreographer.getInstance().removeFrameCallback(this);
        frameQueued = false;
    }

    /** Where to forecast for (WeatherLocation): the cached forecast stays if it is for here and still fresh. */
    void setPlace(WeatherLocation.Place p) {
        place = p;
        if (p == null) {
            if (forecast == null || !forecast.hasNow()) messageText = Ui.tr("Escolha a cidade em Ajustes do Free My TV.",
                    "Pick your city in Free My TV Settings.", "Elige tu ciudad en Ajustes de Free My TV.");
            redraw();
            return;
        }
        String key = Forecast.coordinates(p.latitude, p.longitude);
        boolean fresh = forecast != null && forecast.fetchedAt != 0 && key.equals(forecast.place)
                && System.currentTimeMillis() - forecast.fetchedAt < Forecast.FRESH_FOR;
        if (!fresh) fetch();
        prepare();
        redraw();
    }

    private void fetch() {
        WeatherLocation.Place p = place;
        if (p == null) return;
        if (fetching) {   // the place changed while fetching (the TV's spot arrived): once more after this one
            fetchAgain = true;
            return;
        }
        fetching = true;
        prepare();   // "Updating…" in the header
        redraw();
        wake();
        io.execute(() -> {
            try {
                Forecast f = Forecast.fetchAndSave(getContext(), p.latitude, p.longitude);
                handler.post(() -> {
                    forecast = f;
                    fetching = false;
                    failed = false;
                    prepare();
                    redraw();
                    if (fetchAgain) {
                        fetchAgain = false;
                        fetch();
                    }
                });
            } catch (Exception e) {
                AppLog.w("forecast: " + e.getMessage());
                String here = Forecast.coordinates(p.latitude, p.longitude);
                handler.post(() -> {
                    fetching = false;
                    failed = true;
                    // The last forecast was for another city: better none than the wrong one (the TV's own spot is near the city).
                    if (!p.precise && forecast != null && forecast.fetchedAt != 0 && !here.equals(forecast.place)) forecast = null;
                    prepare();
                    redraw();
                    if (fetchAgain) {
                        fetchAgain = false;
                        fetch();
                    }
                });
            }
        });
    }

    // MARK: - Keys: ↓ / OK to the days, ↑ / Back to today; Back on today closes (the activity)

    boolean onKey(KeyEvent e) {
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) moveDay(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down) moveDay(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (down) {
                    if (e.getRepeatCount() == 0) okDown = true;
                } else {
                    if (okDown && !e.isCanceled() && page.target == 0) showDays(true);
                    okDown = false;
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (page.target == 0) return false;   // the activity closes
                if (down) {
                    if (e.getRepeatCount() == 0) backDown = true;
                } else {
                    if (backDown && !e.isCanceled()) showDays(false);
                    backDown = false;
                }
                return true;
            default:
                return false;
        }
    }

    private int dayCount() {
        return forecast == null ? 0 : forecast.dayCount;
    }

    private void moveDay(int dy) {
        if (page.target == 0) {
            if (dy > 0) showDays(true);
            return;
        }
        int next = day + dy;
        if (next < 0) {
            showDays(false);
            return;
        }
        if (next >= dayCount()) return;
        day = next;
        aimRows();
        prepareDay();
        daysFront.invalidate();
        wake();
    }

    private void showDays(boolean on) {
        if (on && dayCount() == 0) return;
        page.target = on ? 1 : 0;
        if (on) {
            day = 0;
            aimRows();
            for (Spring s : rowFocus) s.snap(s.target);
            prepareDay();
            daysFront.invalidate();
        }
        announceForAccessibility(on ? daysTitle : summary != null ? summary.getText().toString() : conditionText);
        wake();
    }

    private void aimRows() {
        for (int i = 0; i < rowFocus.length; i++) rowFocus[i].target = i == day ? 1 : 0;
    }

    // MARK: - Frames: only while the page or the focus moves

    private void wake() {
        if (!running || frameQueued) return;
        frameQueued = true;
        lastNanos = 0;
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override public void doFrame(long frameTimeNanos) {
        frameQueued = false;
        if (!running) return;
        float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.05f, (frameTimeNanos - lastNanos) / 1e9f);
        lastNanos = frameTimeNanos;
        boolean paging = page.step(dt), rows = false;
        for (Spring s : rowFocus) rows |= s.step(dt);
        if (paging) place();
        if (rows) daysFront.invalidate();
        boolean spinning = fetching && (forecast == null || !forecast.hasNow());
        if (spinning) today.invalidate();
        if (paging || rows || spinning) {
            frameQueued = true;
            Choreographer.getInstance().postFrameCallback(this);
        }
    }

    // MARK: - Size, theme and paints

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        w = width;
        h = height;
        k = w / 1920f;
        light = !HomeData.get(getContext()).isNight(System.currentTimeMillis());
        scene.setSize(w, h);
        makeTheme();
        prepare();
        place();
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec);
        for (View v : new View[] {today, daysBack, daysFront}) {
            v.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
        }
        setMeasuredDimension(width, height);
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        for (View v : new View[] {today, daysBack, daysFront}) v.layout(0, 0, r - l, b - t);
    }

    /** The pages where the scroll has them (only moved, not redrawn). */
    private void place() {
        float scroll = page.value * h;
        today.setTranslationY(-scroll);
        daysBack.setTranslationY(h - scroll);
        daysFront.setTranslationY(h - scroll);
    }

    private void redraw() {
        today.invalidate();
        daysBack.invalidate();
        daysFront.invalidate();
    }

    /**
     * The paints, and what the glass refracts: this screen's own background (no photo here), as the home screen does
     * without one.
     */
    private void makeTheme() {
        makePaints();
        glass.refract(light ? 0xFFF5F5F7 : 0xFF1C1C1E, light ? 0xFFE2E2E7 : 0xFF0B0B0D);
    }

    /** One page, drawn in its own place (scrolling moves it). */
    private final class Layer extends View {
        private final int kind;

        Layer(android.content.Context c, int kind) {
            super(c);
            this.kind = kind;
        }

        @Override protected void onDraw(Canvas c) {
            if (w == 0) return;
            if (kind == TODAY) drawToday(c);
            else if (kind == DAYS_BACK) drawDaysBack(c);
            else drawDaysFront(c);
        }
    }

    private int ink(float alpha) {
        return Ui.alpha(light ? INK : 0xFFFFFFFF, alpha);
    }

    private int dim() {
        return light ? DIM : 0xADFFFFFF;
    }

    private int blue() {
        return light ? 0xFF0071E3 : 0xFF64D2FF;
    }

    private void makePaints() {
        background.setShader(new LinearGradient(0, 0, 0, h, light ? 0xFFF5F5F7 : 0xFF1C1C1E, light ? 0xFFE2E2E7 : 0xFF0B0B0D,
                Shader.TileMode.CLAMP));
        shade.setShader(new RadialGradient(w * 0.5f, h * 0.42f, Math.max(w, h) * 0.75f, 0x00000000,
                light ? 0x0F000000 : 0x33000000, Shader.TileMode.CLAMP));
        title = Ui.text(Ui.displaySemibold, 50 * k, ink(1));
        headerRight = Ui.text(Ui.medium, 24 * k, dim());
        headerRight.setTextAlign(Paint.Align.RIGHT);
        condition = Ui.text(Ui.semibold, 34 * k, ink(1));
        bigTemp = Ui.numbers(Ui.thin, 180 * k, ink(1));
        heroLine = Ui.numbers(Ui.medium, 28 * k, dim());
        summaryPaint = new TextPaint(Ui.text(Ui.regular, 28 * k, ink(0.9f)));
        panelTitle = Ui.text(Ui.semibold, 23 * k, dim());
        hourLabel = Ui.numbers(Ui.medium, 22 * k, dim());
        hourLabel.setTextAlign(Paint.Align.CENTER);
        hourTemp = Ui.numbers(Ui.medium, 24 * k, ink(0.95f));
        hourTemp.setTextAlign(Paint.Align.CENTER);
        chanceLabel = Ui.numbers(Ui.medium, 20 * k, blue());
        chanceLabel.setTextAlign(Paint.Align.CENTER);
        tileTitle = Ui.text(Ui.semibold, 21 * k, dim());
        tileValue = Ui.numbers(Ui.displayMedium, 48 * k, ink(1));
        tileSub = Ui.text(Ui.medium, 23 * k, dim());
        dayName = Ui.text(Ui.medium, 30 * k, ink(0.95f));
        dayTemp = Ui.numbers(Ui.medium, 30 * k, ink(0.95f));
        dayDim = Ui.numbers(Ui.medium, 30 * k, dim());
        dayDim.setTextAlign(Paint.Align.RIGHT);
        dayChance = Ui.numbers(Ui.semibold, 22 * k, blue());
        detailDate = Ui.text(Ui.semibold, 30 * k, ink(1));
        detailTemp = Ui.numbers(Ui.displayMedium, 72 * k, ink(1));
        detailText = Ui.text(Ui.medium, 30 * k, dim());
        factLabel = Ui.text(Ui.medium, 27 * k, dim());
        factValue = Ui.numbers(Ui.medium, 27 * k, ink(0.95f));
        factValue.setTextAlign(Paint.Align.RIGHT);
        message = Ui.text(Ui.medium, 30 * k, dim());
        message.setTextAlign(Paint.Align.CENTER);
        shadow.setMaskFilter(new BlurMaskFilter(22 * k, BlurMaskFilter.Blur.NORMAL));
        glassShadow.setMaskFilter(new BlurMaskFilter(DockGlass.SHADOW_BLUR * k, BlurMaskFilter.Blur.NORMAL));
        summary = null;   // laid out again with the new paint
    }

    // MARK: - Text (made here, not while drawing)

    private void prepare() {
        if (w == 0) return;
        Forecast f = forecast;
        long now = System.currentTimeMillis();
        titleText = Prefs.city().isEmpty() ? Ui.tr("Previsão do tempo", "Forecast", "Pronóstico") : Prefs.city();
        hoursTitle = Ui.tr("Próximas 24 horas", "Next 24 Hours", "Próximas 24 horas");
        daysTitle = Ui.tr("Próximos 7 dias", "Next 7 Days", "Próximos 7 días");
        headerText = header(f);
        if (f == null || !f.hasNow()) {
            if (messageText.isEmpty() || failed || fetching) messageText = failed
                    ? Ui.tr("Sem conexão com a previsão agora.", "Can't reach the forecast right now.", "No se puede cargar el pronóstico ahora.")
                    : Ui.tr("Buscando a previsão…", "Getting the forecast…", "Buscando el pronóstico…");
            setContentDescription(titleText + ". " + messageText);
            return;
        }
        messageText = "";
        tempText = HomeData.degrees(f.temp);
        conditionText = HomeData.condition(f.code, f.isDay);
        lineText = heroLine(f);
        String sentence = WeatherSummary.build(f, now);
        summary = sentence.isEmpty() ? null : StaticLayout.Builder.obtain(sentence, 0, sentence.length(), summaryPaint,
                Math.round(600 * k)).setMaxLines(3).setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(6 * k, 1)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).build();
        prepareHours(f);
        prepareTiles(f, now);
        prepareDays(f);
        prepareDay();
        setContentDescription(titleText + ", " + tempText + ", " + conditionText + ". " + lineText + ". " + sentence);
    }

    /** "TV location · Updated 14:20", or that it's updating. */
    private String header(Forecast f) {
        String where = place == null ? "" : place.precise ? Ui.tr("Localização da TV", "TV's location", "Ubicación de la TV")
                : Ui.tr("Centro da cidade", "City center", "Centro de la ciudad");
        String when = fetching ? Ui.tr("Atualizando…", "Updating…", "Actualizando…")
                : f != null && f.fetchedAt != 0 ? Ui.trf(Ui.tr("Atualizado às {time}", "Updated {time}", "Actualizado a las {time}"),
                        "time", Ui.time(f.fetchedAt)) : "";
        return where.isEmpty() ? when : when.isEmpty() ? where : where + "  ·  " + when;
    }

    private static String heroLine(Forecast f) {
        StringBuilder b = new StringBuilder();
        if (!Double.isNaN(f.feels)) b.append(Ui.trf(Ui.tr("Sensação {t}", "Feels like {t}", "Sensación {t}"), "t", HomeData.degrees(f.feels)));
        if (f.dayCount > 0 && !Double.isNaN(f.dayMax[0])) {
            if (b.length() > 0) b.append("  ·  ");
            b.append(Ui.trf(Ui.tr("Máx. {max}  Mín. {min}", "H:{max}  L:{min}", "Máx. {max}  Mín. {min}"),
                    "max", HomeData.degrees(f.dayMax[0]), "min", HomeData.degrees(f.dayMin[0])));
        }
        return b.toString();
    }

    private void prepareHours(Forecast f) {
        int n = f.hourCount;
        if (n == 0) return;
        double low = Double.MAX_VALUE, high = -Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            low = Math.min(low, f.hourTemp[i]);
            high = Math.max(high, f.hourTemp[i]);
            hourLabels[i] = i == 0 ? Ui.tr("Agora", "Now", "Ahora") : HomeData.hour(f.hourAt[i]);
            hourTemps[i] = HomeData.degrees(f.hourTemp[i]);
            hourChances[i] = f.hourChance[i] >= 10 ? f.hourChance[i] + "%" : "";
        }
        float left = (HOURS_L + 34) * k, width = (R - 34 - HOURS_L - 34) * k, col = width / Forecast.HOURS;
        float top = 368 * k, bottom = 440 * k;
        double span = Math.max(1, high - low);   // a flat day stays a flat line in the middle
        curve.rewind();
        for (int i = 0; i < n; i++) {
            curveX[i] = left + col * (i + 0.5f);
            curveY[i] = high - low < 1 ? (top + bottom) / 2 : (float) (bottom - (f.hourTemp[i] - low) / span * (bottom - top));
            if (i == 0) curve.moveTo(curveX[i], curveY[i]);
            else {
                float mid = (curveX[i - 1] + curveX[i]) / 2;   // a soft curve: each step eased through the middle
                curve.cubicTo(mid, curveY[i - 1], mid, curveY[i], curveX[i], curveY[i]);
            }
        }
    }

    private void prepareTiles(Forecast f, long now) {
        double rainToday = f.dayCount > 0 ? f.dayRain[0] : Double.NaN;
        int chance = f.hourCount > 0 ? f.hourChance[0] : -1;
        set(TILE_RAIN, Ui.tr("Chuva", "Rain", "Lluvia"), Forecast.percent(chance),
                Double.isNaN(rainToday) ? "" : Ui.trf(Ui.tr("{v} hoje", "{v} today", "{v} hoy"), "v", Forecast.rain(rainToday)));
        set(TILE_HUMIDITY, Ui.tr("Umidade", "Humidity", "Humedad"), Forecast.percent(f.humidity), Forecast.humidityLevel(f.humidity));
        String from = Forecast.compass(f.windFrom);
        String gusts = Double.isNaN(f.gusts) ? "" : Ui.trf(Ui.tr("Rajadas {v}", "Gusts {v}", "Rachas {v}"), "v", Forecast.speed(f.gusts));
        set(TILE_WIND, Ui.tr("Vento", "Wind", "Viento"), Forecast.speed(f.wind),
                from.isEmpty() ? gusts : gusts.isEmpty() ? from : gusts + "  ·  " + from);
        windTo = Double.isNaN(f.windFrom) ? Float.NaN : (float) (f.windFrom + 180);   // the arrow points where it blows
        set(TILE_UV, Ui.tr("Índice UV", "UV Index", "Índice UV"), Double.isNaN(f.uv) ? "—" : String.valueOf(Math.round(f.uv)),
                Forecast.uvLevel(f.uv));
        uvAt = Double.isNaN(f.uv) ? -1 : Ui.clamp((float) (f.uv / 11));
        set(TILE_AIR, Ui.tr("Qualidade do ar", "Air Quality", "Calidad del aire"), f.airIndex < 0 ? "—" : String.valueOf(f.airIndex),
                f.airIndex < 0 ? "" : Forecast.airLevel(f.airIndex));
        airAt = f.airIndex < 0 ? -1 : Ui.clamp(f.airIndex / 300f);
        set(TILE_PRESSURE, Ui.tr("Pressão", "Pressure", "Presión"), Forecast.pressure(f.pressure),
                Ui.tr("Ao nível do mar", "At sea level", "Al nivel del mar"));
        set(TILE_VISIBILITY, Ui.tr("Visibilidade", "Visibility", "Visibilidad"), Forecast.distance(f.visibility),
                Forecast.visibilityLevel(f.visibility));
        long rise = f.dayCount > 0 ? f.sunrise[0] : 0, set = f.dayCount > 0 ? f.sunset[0] : 0;
        set(TILE_SUN, Ui.tr("Nascer e pôr do sol", "Sunrise and Sunset", "Amanecer y atardecer"), "", "");
        sunriseText = rise == 0 ? "" : Ui.time(rise);
        sunsetText = set == 0 ? "" : Ui.time(set);
        sunAt = rise == 0 || set <= rise ? -1 : (now - rise) / (float) (set - rise);
    }

    private void set(int tile, String name, String value, String sub) {
        tileTitles[tile] = name;
        tileValues[tile] = value;
        tileSubs[tile] = sub;
    }

    private void prepareDays(Forecast f) {
        weekLow = Double.MAX_VALUE;
        weekHigh = -Double.MAX_VALUE;
        SimpleDateFormat weekday = new SimpleDateFormat("EEEE", Ui.locale());
        weekday.setTimeZone(f.zone);
        for (int i = 0; i < f.dayCount; i++) {
            weekLow = Math.min(weekLow, f.dayMin[i]);
            weekHigh = Math.max(weekHigh, f.dayMax[i]);
            String name = i == 0 ? Ui.tr("Hoje", "Today", "Hoy") : i == 1 ? Ui.tr("Amanhã", "Tomorrow", "Mañana")
                    : weekday.format(f.dayAt[i] + 12 * 3600_000L);
            dayNames[i] = name.isEmpty() ? name : name.substring(0, 1).toUpperCase(Ui.locale()) + name.substring(1);
            dayMaxes[i] = HomeData.degrees(f.dayMax[i]);
            dayMins[i] = HomeData.degrees(f.dayMin[i]);
            dayChances[i] = f.dayChance[i] >= 20 ? f.dayChance[i] + "%" : "";
        }
    }

    /** The focused day's details (the second page's right panel). */
    private void prepareDay() {
        Forecast f = forecast;
        if (f == null || day >= f.dayCount) return;
        int d = day;
        detailDateText = Ui.date(f.dayAt[d] + 12 * 3600_000L);
        detailTempText = HomeData.degrees(f.dayMax[d]) + " / " + HomeData.degrees(f.dayMin[d]);
        detailCondition = HomeData.condition(f.dayCode[d], true);
        fact(0, Ui.tr("Chance de chuva", "Chance of Rain", "Probabilidad de lluvia"), Forecast.percent(f.dayChance[d]));
        fact(1, Ui.tr("Chuva no dia", "Rain in the Day", "Lluvia en el día"), Forecast.rain(f.dayRain[d]));
        fact(2, Ui.tr("Vento máximo", "Strongest Wind", "Viento máximo"), Forecast.speed(f.dayWind[d]));
        fact(3, Ui.tr("Rajadas", "Gusts", "Rachas"), Forecast.speed(f.dayGusts[d]));
        fact(4, Ui.tr("UV máximo", "Highest UV", "UV máximo"), Double.isNaN(f.dayUv[d]) ? "—"
                : Math.round(f.dayUv[d]) + "  " + Forecast.uvLevel(f.dayUv[d]));
        fact(5, Ui.tr("Nascer do sol", "Sunrise", "Amanecer"), f.sunrise[d] == 0 ? "—" : Ui.time(f.sunrise[d]));
        fact(6, Ui.tr("Pôr do sol", "Sunset", "Atardecer"), f.sunset[d] == 0 ? "—" : Ui.time(f.sunset[d]));
    }

    private void fact(int i, String label, String value) {
        factLabels[i] = label;
        factValues[i] = value;
    }

    // MARK: - Drawing

    @Override protected void onDraw(Canvas c) {
        if (w == 0) return;
        c.drawRect(0, 0, w, h, background);
        c.drawRect(0, 0, w, h, shade);
    }

    private void drawHeader(Canvas c, String text) {
        c.drawText(text, M * k - Ui.leftBearing(title, text), TITLE_BASE * k, title);
        c.drawText(headerText, R * k, TITLE_BASE * k, headerRight);
    }

    /** A panel of the shelf's glass (DockGlass; flat in performance mode). */
    private void panel(Canvas c, float left, float top, float right, float bottom) {
        rect.set(left * k, top * k, right * k, bottom * k);
        glass.draw(c, rect, PANEL_RADIUS * k, light ? 0 : 1, k, 1);
    }

    private void drawToday(Canvas c) {
        drawHeader(c, titleText);
        Forecast f = forecast;
        if (f == null || !f.hasNow()) {
            drawMessage(c);
            return;
        }
        drawHero(c, f);
        drawHours(c, f);
        for (int i = 0; i < TILES; i++) drawTile(c, i);
    }

    private void drawMessage(Canvas c) {
        if (fetching) {
            stroke.setStrokeWidth(4 * k);
            stroke.setColor(ink(0.7f));
            Ui.spinner(c, w / 2f, h * 0.44f, 22 * k, System.nanoTime() / 1e9f, stroke);
        }
        c.drawText(messageText, w / 2f, h * 0.44f + 80 * k, message);
    }

    private void drawHero(Canvas c, Forecast f) {
        String symbol = Symbols.weather(f.code, f.isDay);
        float sw = Symbols.width(getContext(), symbol, 44 * k);
        Symbols.draw(getContext(), c, symbol, M * k + sw / 2, 220 * k - Ui.halfCap(condition), 44 * k, ink(1));
        c.drawText(conditionText, M * k + sw + 16 * k, 220 * k, condition);
        c.drawText(tempText, M * k - Ui.leftBearing(bigTemp, tempText), 404 * k, bigTemp);
        c.drawText(lineText, M * k, 458 * k, heroLine);
        if (summary != null) {
            c.save();
            c.translate(M * k, 488 * k);
            summary.draw(c);
            c.restore();
        }
    }

    private void drawHours(Canvas c, Forecast f) {
        panel(c, HOURS_L, HOURS_T, R, HOURS_B);
        float x = (HOURS_L + 34) * k;
        c.drawText(hoursTitle, x, 210 * k, panelTitle);
        int n = f.hourCount;
        if (n == 0) return;
        float col = (R - 34 - HOURS_L - 34) * k / Forecast.HOURS;
        // Rain chance: a bar per hour along the bottom.
        float barBottom = 548 * k, barMax = 40 * k, barW = Math.max(2 * k, col - 12 * k);
        fill.setShader(null);
        for (int i = 0; i < n; i++) {
            if (f.hourChance[i] < 0) continue;
            float hgt = Math.max(3 * k, barMax * f.hourChance[i] / 100f);
            fill.setColor(Ui.alpha(blue(), f.hourChance[i] >= 10 ? 0.55f : 0.18f));
            rect.set(curveX[i] - barW / 2, barBottom - hgt, curveX[i] + barW / 2, barBottom);
            c.drawRoundRect(rect, 3 * k, 3 * k, fill);
        }
        stroke.setStrokeWidth(3 * k);
        stroke.setColor(ink(0.8f));
        c.drawPath(curve, stroke);
        // Every third hour: its time, symbol, temperature (on its point) and chance of rain.
        for (int i = 0; i < n; i += 3) {
            c.drawText(hourLabels[i], curveX[i], 262 * k, hourLabel);
            Symbols.draw(getContext(), c, Symbols.weather(f.hourCode[i], f.hourDay[i]), curveX[i], 304 * k, 28 * k, ink(0.9f));
            fill.setColor(ink(1));
            c.drawCircle(curveX[i], curveY[i], 6 * k, fill);
            c.drawText(hourTemps[i], curveX[i], curveY[i] - 18 * k, hourTemp);
            if (!hourChances[i].isEmpty()) c.drawText(hourChances[i], curveX[i], 574 * k, chanceLabel);
        }
    }

    private void tileRect(int i) {
        float tw = (R - M - 3 * TILE_GAP) / 4;
        float left = M + (i % 4) * (tw + TILE_GAP), top = TILES_T + (i / 4) * (TILE_H + TILE_GAP);
        rect.set(left * k, top * k, (left + tw) * k, (top + TILE_H) * k);
    }

    private void drawTile(Canvas c, int i) {
        tileRect(i);
        panel(c, rect.left / k, rect.top / k, rect.right / k, rect.bottom / k);
        tileRect(i);
        float x = rect.left + 30 * k, top = rect.top, right = rect.right - 30 * k;
        float sw = Symbols.width(getContext(), TILE_SYMBOLS[i], 21 * k);
        Symbols.draw(getContext(), c, TILE_SYMBOLS[i], x + sw / 2, top + 48 * k - Ui.halfCap(tileTitle), 21 * k, dim());
        c.drawText(tileTitles[i], x + sw + 10 * k, top + 48 * k, tileTitle);
        if (i == TILE_SUN) {
            drawSun(c, x, top, right);
            return;
        }
        String value = tileValues[i];
        c.drawText(value, x - Ui.leftBearing(tileValue, value), top + 118 * k, tileValue);
        c.drawText(tileSubs[i], x, top + 162 * k, tileSub);
        float after = x + tileValue.measureText(value) + 24 * k, mid = top + 118 * k - Ui.halfCap(tileValue);
        if (i == TILE_UV && uvAt >= 0) scale(c, after, right, mid, uvAt);
        if (i == TILE_AIR && airAt >= 0) scale(c, after, right, mid, airAt);
        if (i == TILE_WIND && !Float.isNaN(windTo) && !tileValues[i].equals("—")) {
            float size = 30 * k, cx = after + Symbols.width(getContext(), "navigation", size) / 2;
            c.save();
            c.rotate(windTo, cx, mid);
            Symbols.draw(getContext(), c, "navigation", cx, mid, size, ink(0.85f));
            c.restore();
        }
    }

    /** A thin track with a dot where the value sits (UV, air quality): no colors to decode, just how far along. */
    private void scale(Canvas c, float left, float right, float mid, float at) {
        if (right - left < 40 * k) return;
        fill.setShader(null);
        fill.setColor(ink(0.14f));
        rect.set(left, mid - 3 * k, right, mid + 3 * k);
        c.drawRoundRect(rect, 3 * k, 3 * k, fill);
        fill.setColor(ink(0.55f));
        rect.set(left, mid - 3 * k, left + (right - left) * at, mid + 3 * k);
        c.drawRoundRect(rect, 3 * k, 3 * k, fill);
        fill.setColor(ink(1));
        c.drawCircle(left + (right - left) * at, mid, 8 * k, fill);
    }

    /** The day's arc from sunrise (left) to sunset (right), with the sun on it while it's up. */
    private void drawSun(Canvas c, float left, float top, float right) {
        float base = top + 146 * k, height = 62 * k, mid = (left + right) / 2, half = (right - left) / 2;
        stroke.setStrokeWidth(Math.max(1.5f, 2 * k));
        stroke.setColor(ink(0.18f));
        c.drawLine(left, base, right, base, stroke);
        stroke.setStrokeWidth(3 * k);
        stroke.setColor(ink(0.4f));
        rect.set(left, base - height, right, base + height);
        c.drawArc(rect, 180, 180, false, stroke);
        if (sunAt >= 0 && sunAt <= 1) {   // the sun on its arc; at night, none
            double angle = Math.PI * (1 - sunAt);
            float sx = mid + (float) Math.cos(angle) * half, sy = base - (float) Math.sin(angle) * height;
            fill.setShader(null);
            fill.setColor(light ? 0xFFFF9F0A : 0xFFFFD60A);
            c.drawCircle(sx, sy, 10 * k, fill);
        }
        c.drawText(sunriseText, left, top + 182 * k, tileSub);
        tileSub.setTextAlign(Paint.Align.RIGHT);
        c.drawText(sunsetText, right, top + 182 * k, tileSub);
        tileSub.setTextAlign(Paint.Align.LEFT);
    }

    /** The days page's still part: the title and the two glass panels. */
    private void drawDaysBack(Canvas c) {
        drawHeader(c, daysTitle);
        Forecast f = forecast;
        if (f == null || f.dayCount == 0) return;
        panel(c, DAYS_L, DAYS_T, DAYS_R, DAYS_B);
        panel(c, DETAIL_L, DAYS_T, R, DAYS_B);
    }

    /** What the focus changes: the rows (and the pill) and the focused day's details. */
    private void drawDaysFront(Canvas c) {
        Forecast f = forecast;
        if (f == null || f.dayCount == 0) return;
        for (int i = 0; i < f.dayCount; i++) drawDay(c, f, i);
        drawDetail(c, f);
    }

    private void drawDay(Canvas c, Forecast f, int i) {
        float fc = Ui.clamp(rowFocus[i].value);
        float top = (ROW_TOP + i * ROW_H) * k, mid = top + ROW_PILL / 2 * k;
        if (fc > 0.01f) {
            pill.set((DAYS_L + 14) * k, top, (DAYS_R - 14) * k, top + ROW_PILL * k);
            float r = 24 * k;
            rect.set(pill);
            rect.offset(0, 8 * k * fc);
            shadow.setColor(Ui.alpha(0xFF000000, (light ? 0.16f : 0.4f) * fc));
            shadows.draw(c, rect, r, 22 * k, shadow);
            fill.setShader(null);
            fill.setColor(Ui.alpha(light ? 0xFFFFFFFF : 0xFFF5F5F7, fc));
            c.drawRoundRect(pill, r, r, fill);
        }
        // On the white pill the text turns dark (in the dark theme too).
        int on = blend(ink(0.95f), INK, light ? 0 : fc), onDim = blend(dim(), DIM, light ? 0 : fc);
        dayName.setColor(on);
        dayTemp.setColor(on);
        dayDim.setColor(onDim);
        dayChance.setColor(blend(blue(), 0xFF0071E3, light ? 0 : fc));   // the day blue on the white pill
        float half = Ui.halfCap(dayName);
        c.drawText(dayNames[i], (DAYS_L + 44) * k, mid + half, dayName);
        Symbols.draw(getContext(), c, Symbols.weather(f.dayCode[i], true), (DAYS_L + 340) * k, mid, 34 * k, on);
        if (!dayChances[i].isEmpty()) c.drawText(dayChances[i], (DAYS_L + 384) * k, mid + Ui.halfCap(dayChance), dayChance);
        c.drawText(dayMins[i], (DAYS_L + 570) * k, mid + half, dayDim);
        // The day's range on the week's scale (where it sits between the week's lowest and highest).
        float bl = (DAYS_L + 600) * k, br = (DAYS_L + 840) * k;
        double span = Math.max(1, weekHigh - weekLow);
        fill.setShader(null);
        fill.setColor(Ui.alpha(on, 0.14f));
        rect.set(bl, mid - 4 * k, br, mid + 4 * k);
        c.drawRoundRect(rect, 4 * k, 4 * k, fill);
        float from = bl + (float) ((f.dayMin[i] - weekLow) / span) * (br - bl);
        float to = bl + (float) ((f.dayMax[i] - weekLow) / span) * (br - bl);
        fill.setColor(Ui.alpha(on, 0.75f));
        rect.set(from, mid - 4 * k, Math.max(from + 8 * k, to), mid + 4 * k);
        c.drawRoundRect(rect, 4 * k, 4 * k, fill);
        c.drawText(dayMaxes[i], (DAYS_L + 864) * k, mid + half, dayTemp);
    }

    private void drawDetail(Canvas c, Forecast f) {
        if (day >= f.dayCount) return;
        float x = (DETAIL_L + 40) * k, right = (R - 40) * k;
        c.drawText(detailDateText, x, 226 * k, detailDate);
        String symbol = Symbols.weather(f.dayCode[day], true);
        float sw = Symbols.width(getContext(), symbol, 64 * k);
        float tempBase = 340 * k;
        Symbols.draw(getContext(), c, symbol, x + sw / 2, tempBase - Ui.halfCap(detailTemp), 64 * k, ink(1));
        c.drawText(detailTempText, x + sw + 24 * k, tempBase, detailTemp);
        c.drawText(detailCondition, x, 404 * k, detailText);
        for (int i = 0; i < FACTS; i++) {
            float rowTop = (452 + i * 78) * k, mid = rowTop + 39 * k;
            if (i > 0) {
                fill.setShader(null);
                fill.setColor(ink(0.1f));
                c.drawRect(x, rowTop, right, rowTop + Math.max(1, k), fill);
            }
            float fw = Symbols.width(getContext(), FACT_SYMBOLS[i], 26 * k);
            Symbols.draw(getContext(), c, FACT_SYMBOLS[i], x + fw / 2, mid, 26 * k, dim());
            c.drawText(factLabels[i], x + 48 * k, mid + Ui.halfCap(factLabel), factLabel);
            c.drawText(factValues[i], right, mid + Ui.halfCap(factValue), factValue);
        }
    }

    private static int blend(int a, int b, float f) {
        int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return (Math.round(aa + (ba - aa) * f) << 24) | (Math.round(ar + (br - ar) * f) << 16)
                | (Math.round(ag + (bg - ag) * f) << 8) | Math.round(ab + (bb - ab) * f);
    }
}
