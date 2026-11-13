package io.freemytv;

import android.app.Activity;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * The Control Center: the TV's quick settings and tools in one panel of rounded tiles (Wi-Fi, Bluetooth, the speed
 * test, the sleep timer, free memory, recent apps, both settings). The home screen shows it under its button; the
 * remote button picked in Remote Buttons shows it over any app (ControlActivity). The host moves and fades it; this
 * class lays it out, draws it at rest and answers the keys. The home screen's HDMI Inputs button shows the inputs in it
 * instead (showInputs). Measurements in a 1920 × 1080 space.
 */
final class ControlCenter {
    interface Host {
        View view();
        /** The panel's content changed: draw it again. */
        void redraw();
        void toast(String text);
        /** The panel is done (↑ past the top row, or a tile opened something). */
        void close(boolean opened);
        /** 0 = the day look, 1 = night (in between while it changes). */
        float dark();
        /** The panel's glass: the shelf's on the home screen; over another app (no backdrop to bend), a smoked one. */
        void glass(Canvas c, RectF r, float radius);
    }

    private static final int WIFI = 0, BLUETOOTH = 1, SPEED = 2, SLEEP = 3, MEMORY = 4, RECENTS = 5, APP_SETTINGS = 6,
            TV_SETTINGS = 7, NO_INPUTS = 8;
    /** The inputs' tiles (showInputs): INPUT + the input's place in the list. */
    private static final int INPUT = 100;
    /** The Control Center's tiles in reading order: what each does, its column, its row and how many columns it takes. */
    private static final int[][] CONTROLS = {
        {WIFI, 0, 0, 1}, {BLUETOOTH, 1, 0, 1},
        {SPEED, 0, 1, 2},
        {SLEEP, 0, 2, 2},
        {MEMORY, 0, 3, 1}, {RECENTS, 1, 3, 1},
        {APP_SETTINGS, 0, 4, 1}, {TV_SETTINGS, 1, 4, 1},
    };
    // The tiles' corners are concentric with the panel's (panel radius = tile radius + padding), as in the menu.
    // Concentric corners everywhere (outer radius = inner radius + the padding between them): the panel around the
    // tiles, a tile around its time chips.
    static final float TILE_W = 272, TILE_H = 124, GAP = 16, PAD = 28, TILE_RADIUS = 30;
    private static final float TEXT_X = 22, CHIP_H = 40, CHIP_GAP = 8, CHIP_INSET = 14, SHADOW_BLUR = 26;
    private static final int INK = 0xFF1D1D1F, DIM = 0xFF6E6E73, WHITE = 0xFFF5F5F7;
    private static final long LIVE_MS = 2000;   // the memory tile's CPU and free memory, while the panel is open

    private final Activity activity;
    private final Host host;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Shadows shadows = new Shadows();
    /** What the panel shows now: CONTROLS, or the inputs (showInputs). The arrays below follow it. */
    private int[][] tiles = CONTROLS;
    private final List<HomeApps.Item> inputs = new ArrayList<>();
    private Spring[] focus;
    private RectF[] rects;
    private String[] titles, details, symbols;
    private final RectF panel = new RectF(), tmp = new RectF();
    private final RectF[] chips = new RectF[SleepTimer.OPTIONS.length];
    private final String[] chipLabels = new String[SleepTimer.OPTIONS.length];
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG), cursor = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Paint title, detail, chipText, status;
    private float k = 1, chipHalfCap, titleHalfCap, right, top;
    private int index, sleepAt, sleepCursor;
    private boolean open;
    private final CpuMonitor cpu = new CpuMonitor();
    private int cpuPercent = -1;   // -1 until the first reading
    private final Runnable liveRead = new Runnable() {
        @Override public void run() {
            if (!open) return;
            updateMemoryText();
            hostRedraw();
            handler.postDelayed(this, LIVE_MS);
        }
    };

    ControlCenter(Activity activity, Host host) {
        this.activity = activity;
        this.host = host;
        for (int i = 0; i < chips.length; i++) chips[i] = new RectF();
        border.setStyle(Paint.Style.STROKE);
        setTiles(CONTROLS);
    }

    private void setTiles(int[][] t) {
        tiles = t;
        focus = new Spring[t.length];
        rects = new RectF[t.length];
        titles = new String[t.length];
        details = new String[t.length];
        symbols = new String[t.length];
        for (int i = 0; i < t.length; i++) {
            focus[i] = Spring.focus(0);
            rects[i] = new RectF();
        }
        index = 0;
        if (title != null) layout(k, right, top);
    }

    /** The Control Center's tiles (before open()). */
    void showControls() {
        if (tiles != CONTROLS) setTiles(CONTROLS);
    }

    /**
     * The inputs instead (before open()), for the HDMI Inputs button: live TV and each HDMI input, two to a row, the
     * last one across when they're odd; OK switches to it.
     */
    void showInputs(List<HomeApps.Item> list) {
        inputs.clear();
        inputs.addAll(list);
        int n = inputs.size();
        if (n == 0) {
            setTiles(new int[][] {{NO_INPUTS, 0, 0, 2}});
            return;
        }
        int[][] t = new int[n][];
        for (int i = 0; i < n; i++) t[i] = new int[] {INPUT + i, i % 2, i / 2, i == n - 1 && n % 2 == 1 ? 2 : 1};
        setTiles(t);
    }

    boolean showsInputs() {
        return tiles != CONTROLS;
    }

    /** The panel's height for what it shows (the host places it by its top). */
    private float height() {
        int rows = tiles[tiles.length - 1][2] + 1;
        return (2 * PAD + rows * TILE_H + (rows - 1) * GAP) * k;
    }

    static float width(float k) {
        return (2 * PAD + 2 * TILE_W + GAP) * k;
    }

    /** The panel with its top right corner at (right, top). */
    void layout(float k, float right, float top) {
        this.k = k;
        this.right = right;
        this.top = top;
        title = Ui.text(Ui.semibold, 27 * k, INK);
        detail = Ui.text(Ui.medium, 22 * k, DIM);
        status = Ui.text(Ui.medium, 22 * k, DIM);
        status.setTextAlign(Paint.Align.RIGHT);
        chipText = Ui.numbers(Ui.semibold, 22 * k, INK);
        chipText.setTextAlign(Paint.Align.CENTER);
        chipHalfCap = Ui.halfCap(chipText);
        titleHalfCap = Ui.halfCap(title);
        shadow.setMaskFilter(new BlurMaskFilter(SHADOW_BLUR * k, BlurMaskFilter.Blur.NORMAL));
        border.setStrokeWidth(2 * k);
        cursor.setStyle(Paint.Style.STROKE);
        cursor.setStrokeWidth(2.5f * k);
        float pad = PAD * k, tw = TILE_W * k, th = TILE_H * k, gap = GAP * k;
        panel.set(right - width(k), top, right, top + height());
        for (int i = 0; i < tiles.length; i++) {
            float x = panel.left + pad + tiles[i][1] * (tw + gap), y = top + pad + tiles[i][2] * (th + gap);
            rects[i].set(x, y, x + tiles[i][3] * tw + (tiles[i][3] - 1) * gap, y + th);
            if (tiles[i][0] == SLEEP) layoutChips(rects[i]);
        }
        if (open) updateTexts();
    }

    /** The sleep timer's choices, side by side along the bottom of its tile. */
    private void layoutChips(RectF r) {
        float inset = CHIP_INSET * k, x = r.left + inset, room = r.width() - 2 * inset, gap = CHIP_GAP * k;
        float w = (room - (chips.length - 1) * gap) / chips.length, bottom = r.bottom - inset;
        for (int i = 0; i < chips.length; i++) {
            chips[i].set(x, bottom - CHIP_H * k, x + w, bottom);
            x += w + gap;
        }
    }

    RectF bounds() {
        return panel;
    }

    boolean isOpen() {
        return open;
    }

    void open() {
        open = true;
        index = 0;
        updateTexts();
        aim(true);
        SpeedTest.get().setListener(this::onSpeed);
        handler.removeCallbacks(liveRead);
        if (!showsInputs()) {
            cpuPercent = -1;
            cpu.start(activity, percent -> cpuPercent = percent);
            handler.postDelayed(liveRead, LIVE_MS);
        }
        host.view().announceForAccessibility(showsInputs() ? Ui.tr("Entradas HDMI", "HDMI Inputs")
                : Ui.tr("Central de controle", "Control Center", "Centro de control"));
    }

    /** Closed: nothing it started waits on it any more (the speed test and the timer go on). */
    void stop() {
        open = false;
        handler.removeCallbacks(liveRead);
        cpu.stop();
        SpeedTest.get().setListener(null);
    }

    /** The focus springs; true while they move. */
    boolean step(float dt) {
        boolean moving = false;
        for (Spring s : focus) moving |= s.step(dt);
        return moving;
    }

    /** A new minute: the timer's "Turns off at" may have passed. */
    void minuteTick() {
        if (!open) return;
        updateTexts();
        hostRedraw();
    }

    private void hostRedraw() {
        host.redraw();
    }

    private void aim(boolean snap) {
        for (int i = 0; i < focus.length; i++) {
            focus[i].target = i == index ? 1 : 0;
            if (snap) focus[i].snap(focus[i].target);
        }
        hostRedraw();
    }

    // MARK: - Texts (read when the panel opens and when what a tile shows changes, never while drawing)

    private void updateTexts() {
        if (title == null) return;   // not laid out yet: layout() reads them once it is
        sleepAt = optionIndex(SleepTimer.minutes(activity));
        for (int i = 0; i < chipLabels.length; i++) {
            int m = SleepTimer.OPTIONS[i];
            chipLabels[i] = m == 0 ? "Off" : String.valueOf(m);
        }
        for (int i = 0; i < tiles.length; i++) {
            String t, d, symbol;
            int kind = tiles[i][0];
            if (kind >= INPUT) {
                HomeApps.Item it = inputs.get(kind - INPUT);
                symbol = it.symbol;
                t = it.label;
                d = it.kind == HomeApps.LIVE_TV ? Ui.tr("Canais da TV", "The TV's channels", "Canales de la TV")
                        : it.connected ? Ui.tr("Com sinal", "Signal", "Con señal") : Ui.tr("Sem sinal", "No signal", "Sin señal");
            } else switch (kind) {
                case NO_INPUTS:
                    symbol = "hdmi";
                    t = Ui.tr("Nenhuma entrada", "No inputs", "Ninguna entrada");
                    d = Ui.tr("A TV não informou entradas HDMI", "The TV reported no HDMI inputs", "La TV no informó entradas HDMI");
                    break;
                case WIFI: {
                    String[] net = networkStatus();
                    symbol = net[0];
                    t = net[1];
                    d = net[2];
                    break;
                }
                case BLUETOOTH:
                    symbol = "bluetooth";
                    t = "Bluetooth";
                    d = Ui.tr("Controles e acessórios", "Remotes and accessories", "Controles y accesorios");
                    break;
                case SPEED:
                    symbol = "speed";
                    t = Ui.tr("Teste de velocidade", "Speed test", "Prueba de velocidad");
                    d = speedText();
                    break;
                case SLEEP:
                    symbol = "bedtime";
                    t = Ui.tr("Desligar a TV (min)", "Sleep timer (min)", "Apagar la TV (min)");
                    d = sleepText();
                    break;
                case MEMORY:
                    symbol = "clean";
                    t = Ui.tr("Liberar memória", "Free memory", "Liberar memoria");
                    d = "";   // memoryText, which fits it to the tile
                    break;
                case RECENTS:
                    symbol = "recents";
                    t = Ui.tr("Apps recentes", "Recent apps", "Apps recientes");
                    d = Ui.tr("Ver e encerrar", "See and close", "Ver y cerrar");
                    break;
                case APP_SETTINGS:
                    symbol = "settings";
                    t = "Free My TV";
                    d = Ui.tr("Ajustes do Início", "Home Settings", "Ajustes del Inicio");
                    break;
                default:
                    symbol = "tv_settings";
                    t = Ui.tr("Ajustes da TV", "TV Settings", "Ajustes de la TV");
                    d = Ui.tr("Rede, imagem e som", "Network, picture and sound", "Red, imagen y sonido");
                    break;
            }
            float room = rects[i].width() - 2 * TEXT_X * k;
            symbols[i] = symbol;
            if (tiles[i][0] == MEMORY) {
                titles[i] = ellipsize(t, title, room);
                details[i] = memoryText(room);
            } else if (tiles[i][0] == SLEEP) {
                // Title and status share the top line; the choices take the bottom.
                details[i] = d;
                titles[i] = ellipsize(t, title, room - 48 * k - status.measureText(d) - 16 * k);
            } else {
                titles[i] = ellipsize(t, title, room);
                details[i] = ellipsize(d, detail, room);
            }
        }
    }

    private static String ellipsize(String text, Paint paint, float room) {
        if (paint.measureText(text) <= room) return text;
        return TextUtils.ellipsize(text, new TextPaint(paint), Math.max(0, room), TextUtils.TruncateAt.END).toString();
    }

    /** The connection: {symbol, title, detail}. Only read: the network settings stay the person's. */
    private String[] networkStatus() {
        android.net.ConnectivityManager cm = activity.getSystemService(android.net.ConnectivityManager.class);
        android.net.Network net = cm == null ? null : cm.getActiveNetwork();
        android.net.NetworkCapabilities caps = net == null ? null : cm.getNetworkCapabilities(net);
        if (caps == null) return new String[] {"wifi_off", "Wi-Fi", Ui.tr("Sem conexão", "Not connected", "Sin conexión")};
        boolean online = caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        String connected = Ui.tr("Conectado", "Connected", "Conectado"), offline = Ui.tr("Sem internet", "No internet", "Sin internet");
        if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)) {
            return new String[] {"ethernet", Ui.tr("Cabo de rede", "Ethernet", "Cable de red"), online ? connected : offline};
        }
        if (!online) return new String[] {"wifi", "Wi-Fi", offline};
        String d = connected;
        if (Build.VERSION.SDK_INT >= 29) {
            int rssi = caps.getSignalStrength();   // dBm for Wi-Fi; unspecified on some TVs
            if (rssi > -127 && rssi < 0) {
                d = rssi >= -60 ? Ui.tr("Sinal ótimo", "Excellent signal", "Señal excelente")
                        : rssi >= -70 ? Ui.tr("Sinal bom", "Good signal", "Señal buena") : Ui.tr("Sinal fraco", "Weak signal", "Señal débil");
            }
        }
        return new String[] {"wifi", "Wi-Fi", d};
    }

    private static String speedText() {
        SpeedTest st = SpeedTest.get();
        switch (st.phase) {
            case SpeedTest.PING: return Ui.tr("Medindo o ping…", "Measuring ping…", "Midiendo el ping…");
            case SpeedTest.DOWNLOAD:
                return Ui.trf(Ui.tr("Ping {ping} ms · medindo o download…", "Ping {ping} ms · measuring download…",
                        "Ping {ping} ms · midiendo la descarga…"), "ping", number(st.pingMs));
            case SpeedTest.UPLOAD:
                return Ui.trf(Ui.tr("↓ {down} Mbps · medindo o upload…", "↓ {down} Mbps · measuring upload…",
                        "↓ {down} Mbps · midiendo la subida…"), "down", number(st.downMbps));
            case SpeedTest.DONE:
                return Ui.trf("↓ {down}  ↑ {up} Mbps · ping {ping} ms", "down", number(st.downMbps), "up", number(st.upMbps),
                        "ping", number(st.pingMs));
            case SpeedTest.FAILED:
                return Ui.tr("Não deu para medir. OK tenta de novo.", "Couldn't measure. OK to try again.",
                        "No se pudo medir. OK para intentar de nuevo.");
            default: return Ui.tr("Download, upload e ping", "Download, upload and ping", "Descarga, subida y ping");
        }
    }

    /** 0.5, 9.4, 87, 512: one decimal below 10. */
    private static String number(double v) {
        if (Double.isNaN(v)) return "–";
        return v < 10 ? String.format(Ui.locale(), "%.1f", v) : String.format(Ui.locale(), "%.0f", v);
    }

    private String sleepText() {
        long at = SleepTimer.endsAt(activity);
        if (at == 0) return Ui.tr("Sem timer", "No timer", "Sin temporizador");
        return Ui.trf(Ui.tr("Desliga às {time}", "Off at {time}", "Se apaga a las {time}"), "time", Ui.clock(at));
    }

    /**
     * What Android counts as available now and, once read, how busy the processor is: "CPU 23% · 1.2 GB free", shorter
     * when that doesn't fit ("CPU 23% · 1.2 GB"); before the first reading, "1.2 GB free of 2 GB".
     */
    private String memoryText(float room) {
        android.app.ActivityManager am = activity.getSystemService(android.app.ActivityManager.class);
        if (am == null) return "";
        android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        String free = gigabytes(mi.availMem), text;
        if (cpuPercent < 0) {
            text = Ui.trf(Ui.tr("{free} livres de {total}", "{free} free of {total}", "{free} libres de {total}"),
                    "free", free, "total", gigabytes(Math.round(mi.totalMem / 1073741824.0) * 1073741824L));
        } else {
            text = Ui.trf(Ui.tr("CPU {cpu}% · {free} livres", "CPU {cpu}% · {free} free", "CPU {cpu}% · {free} libres"),
                    "cpu", cpuPercent, "free", free);
            if (detail.measureText(text) > room) text = Ui.trf("CPU {cpu}% · {free}", "cpu", cpuPercent, "free", free);
        }
        return ellipsize(text, detail, room);
    }

    /** The memory tile's line again (every LIVE_MS while open). */
    private void updateMemoryText() {
        if (title == null) return;
        for (int i = 0; i < tiles.length; i++) {
            if (tiles[i][0] == MEMORY) details[i] = memoryText(rects[i].width() - 2 * TEXT_X * k);
        }
    }

    private static String gigabytes(long bytes) {
        double gb = bytes / 1073741824.0;
        return String.format(Ui.locale(), gb >= 10 || gb == Math.rint(gb) ? "%.0f GB" : "%.1f GB", gb);
    }

    private static int optionIndex(int minutes) {
        for (int i = 0; i < SleepTimer.OPTIONS.length; i++) if (SleepTimer.OPTIONS[i] == minutes) return i;
        return 0;
    }

    // MARK: - Keys

    /**
     * The arrows go to the nearest tile that way; on the sleep timer ← → move along its times (nothing changes yet, no
     * list opens) and OK picks the one under the cursor. ↑ from the top row closes the panel.
     */
    void move(int dx, int dy) {
        if (dx != 0 && tiles[index][0] == SLEEP) {
            int next = Math.max(0, Math.min(SleepTimer.OPTIONS.length - 1, sleepCursor + dx));
            if (next == sleepCursor) return;
            sleepCursor = next;
            hostRedraw();
            host.view().announceForAccessibility(chipLabels[next]);
            return;
        }
        RectF from = rects[index];
        int best = -1;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < rects.length; i++) {
            RectF r = rects[i];
            float ddx = r.centerX() - from.centerX(), ddy = r.centerY() - from.centerY();
            boolean ahead = dx != 0 ? Math.signum(ddx) == dx && r.top < from.bottom && r.bottom > from.top
                    : Math.signum(ddy) == dy;
            if (i == index || !ahead) continue;
            float d = dx != 0 ? Math.abs(ddx) : Math.abs(ddy) * 4 + Math.abs(ddx);
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        if (best < 0) {
            if (dy < 0) host.close(false);
            return;
        }
        index = best;
        if (tiles[index][0] == SLEEP) sleepCursor = sleepAt;   // the cursor starts on the time in use
        aim(false);
        host.view().announceForAccessibility(titles[best] + ", " + details[best]);
    }

    /**
     * The sleep timer at choice `at` (0 = off), from now (picking the same time again starts it over), kept by
     * SleepTimer, which turns the TV off when it ends. Said in a notice, as Free memory's.
     */
    private void setSleep(int at) {
        int minutes = SleepTimer.OPTIONS[at];
        SleepTimer.set(activity, minutes);
        updateTexts();
        hostRedraw();
        String said = SleepTimer.describe(activity);
        host.toast(said);
        host.view().announceForAccessibility(said);
    }

    void press() {
        int action = tiles[index][0];
        RectF r = rects[index];
        if (action == NO_INPUTS) return;
        if (action >= INPUT) {
            HomeApps.Item it = inputs.get(action - INPUT);
            if (HomeApps.open(activity, it, host.view(), Math.round(r.left), Math.round(r.top), Math.round(r.width()), Math.round(r.height()))) {
                host.close(true);
            } else {
                host.toast(Ui.trf(Ui.tr("Não deu para abrir {app}.", "Couldn't open {app}.", "No se pudo abrir {app}."), "app", it.label));
            }
            return;
        }
        switch (action) {
            case SPEED:
                SpeedTest.get().start();
                return;
            case SLEEP:
                setSleep(sleepCursor);
                return;
            case MEMORY:
                HomeMode.requestFreeMemory(activity);
                host.toast(Ui.tr("Liberando memória…", "Freeing memory…", "Liberando memoria…"));
                return;
            default:
                break;
        }
        View from = host.view();
        int x = Math.round(r.left), y = Math.round(r.top), w = Math.round(r.width()), h = Math.round(r.height());
        boolean opened;
        switch (action) {
            case WIFI: opened = HomeApps.openSettings(activity, android.provider.Settings.ACTION_WIFI_SETTINGS); break;
            case BLUETOOTH: opened = HomeApps.openSettings(activity, android.provider.Settings.ACTION_BLUETOOTH_SETTINGS); break;
            case RECENTS: opened = RecentsActivity.open(activity); break;
            case APP_SETTINGS: opened = HomeApps.open(activity, HomeApps.settings(), from, x, y, w, h); break;
            default: opened = HomeApps.open(activity, HomeApps.action(HomeApps.ANDROID_SETTINGS, "settings", "", ""), from, x, y, w, h); break;
        }
        if (opened) host.close(true);
        else host.toast(Ui.trf(Ui.tr("Não deu para abrir {app}.", "Couldn't open {app}.", "No se pudo abrir {app}."), "app", titles[index]));
    }

    /** A speed test moved on: the tile shows it. */
    private void onSpeed() {
        if (!open) return;
        updateTexts();
        hostRedraw();
    }

    // MARK: - Drawing

    private int tone(int onLight, int onDark) {
        return blend(onLight, onDark, host.dark());
    }

    /**
     * The panel in the menu's look (white by day, dark gray at night), with rounded tiles: a symbol, a title and what it
     * shows now. The focused tile takes the opposite color and grows a little, like the menu's pill.
     */
    void draw(Canvas c) {
        if (titles[0] == null) return;
        float pad = PAD * k, tileRadius = TILE_RADIUS * k, radius = tileRadius + pad, dk = host.dark();
        tmp.set(panel);
        tmp.offset(0, 12 * k);
        shadow.setColor(Ui.alpha(0xFF000000, 0.16f + 0.24f * dk));
        shadows.draw(c, tmp, radius, SHADOW_BLUR * k, shadow);
        host.glass(c, panel, radius);
        border.setColor(tone(0x14000000, 0x1FFFFFFF));
        c.drawRoundRect(panel, radius, radius, border);
        int restInk = tone(INK, WHITE), litInk = tone(WHITE, INK);
        int restDim = tone(DIM, 0xFF98989D), litDim = tone(0xB3FFFFFF, 0xB31D1D1F);
        SpeedTest st = SpeedTest.get();
        for (int i = 0; i < tiles.length; i++) {
            RectF r = rects[i];
            float f = Ui.clamp(focus[i].value), s = 1 + 0.04f * f;
            c.save();
            c.scale(s, s, r.centerX(), r.centerY());
            fill.setColor(tone(0x0F000000, 0x17FFFFFF));
            c.drawRoundRect(r, tileRadius, tileRadius, fill);
            if (f > 0.01f) {
                fill.setColor(Ui.alpha(tone(INK, WHITE), f));
                c.drawRoundRect(r, tileRadius, tileRadius, fill);
            }
            int ink = blend(restInk, litInk, f), dim = blend(restDim, litDim, f);
            if (tiles[i][0] == SPEED && st.running()) {
                // How far the test got: a faint fill from the left, clipped to the tile's corners.
                tmp.set(r.left, r.top, r.left + r.width() * st.progress, r.bottom);
                c.save();
                c.clipRect(tmp);
                fill.setColor(Ui.alpha(ink, 0.1f));
                c.drawRoundRect(r, tileRadius, tileRadius, fill);
                c.restore();
            }
            float x = r.left + TEXT_X * k;
            if (tiles[i][0] == SLEEP) {
                drawSleep(c, i, r, x, ink, dim, f);
            } else {
                Symbols.draw(activity, c, symbols[i], x + 17 * k, r.top + 36 * k, 30 * k, ink);
                title.setColor(ink);
                c.drawText(titles[i], x, r.bottom - 46 * k, title);
                detail.setColor(dim);
                c.drawText(details[i], x, r.bottom - 18 * k, detail);
            }
            c.restore();
        }
    }

    /**
     * The sleep timer: symbol, title and when the TV turns off on top; the times below, the one in use filled and, while
     * the tile has the focus, a ring around the one OK would pick.
     */
    private void drawSleep(Canvas c, int i, RectF r, float x, int ink, int dim, float f) {
        float line = r.top + 46 * k;
        Symbols.draw(activity, c, symbols[i], x + 17 * k, line - titleHalfCap, 28 * k, ink);
        title.setColor(ink);
        c.drawText(titles[i], x + 48 * k, line, title);
        status.setColor(dim);
        c.drawText(details[i], r.right - TEXT_X * k, line, status);
        float h = CHIP_H * k, chipRadius = (TILE_RADIUS - CHIP_INSET) * k;
        for (int j = 0; j < chips.length; j++) {
            boolean chosen = j == sleepAt;
            fill.setColor(chosen ? ink : Ui.alpha(ink, 0.1f));
            c.drawRoundRect(chips[j], chipRadius, chipRadius, fill);
            chipText.setColor(chosen ? contrast(ink) : ink);
            c.drawText(chipLabels[j], chips[j].centerX(), chips[j].centerY() + chipHalfCap, chipText);
            if (j == sleepCursor && f > 0.01f) {
                float out = 4 * k;
                tmp.set(chips[j]);
                tmp.inset(-out, -out);
                cursor.setColor(Ui.alpha(ink, f));
                c.drawRoundRect(tmp, chipRadius + out, chipRadius + out, cursor);
            }
        }
    }

    /** Text on a fill of this color: dark on a light one, white on a dark one. */
    static int contrast(int color) {
        int r = (color >> 16) & 255, g = (color >> 8) & 255, b = color & 255;
        return r * 299 + g * 587 + b * 114 > 128_000 ? INK : WHITE;
    }

    static int blend(int a, int b, float f) {
        f = Ui.clamp(f);
        int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return Math.round(aa + (ba - aa) * f) << 24 | Math.round(ar + (br - ar) * f) << 16
                | Math.round(ag + (bg - ag) * f) << 8 | Math.round(ab + (bb - ab) * f);
    }
}
