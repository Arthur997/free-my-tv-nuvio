package io.freemytv;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewConfiguration;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Recent apps panel, like tvOS' app switcher: over a dark veil, a row of the open apps' cards (most recent first)
 * with what each uses, and "Close all" at the end. OK opens an app; ↓ reaches its Close button (or hold OK); Back
 * leaves. The list and the closing go through this app's own debugging connection (HomeMode.openApps, closeApps), on a
 * background thread that keeps one connection while the panel is open. Measurements in a 1920 × 1080 space.
 */
final class RecentsView extends View implements Choreographer.FrameCallback {
    private static final float MARGIN = 80, CARD_W = 400, CARD_H = 225, CARD_GAP = 48, CARD_TOP = 360, RADIUS = 24;
    private static final float CLOSE_W = 200, CLOSE_H = 64, CLOSE_GAP = 112;
    private static final float SHADOW_BLUR = 26;
    private static final int WHITE = 0xFFF5F5F7, INK = 0xFF1C1C1E;

    /** One open app: its package, name, card and the MB it uses (-1 until measured). */
    private static final class App {
        final String pkg, label;
        final Bitmap tile;
        long mb = -1;
        String labelShown, mbShown = "";
        final Spring focus = new Spring(0.2f, 0.85f, 0);

        App(String pkg, String label, Bitmap tile) {
            this.pkg = pkg;
            this.label = label;
            this.tile = tile;
        }
    }

    private final Activity activity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Click click;
    private final Shadows shadows = new Shadows();
    private final FocusRing focusRing = new FocusRing(shadows);
    private Adb adb;                    // only touched on `io`

    private List<App> apps = new ArrayList<>();
    private boolean loaded;
    private String message = "";        // instead of the list: loading, nothing open, or why it can't
    private String status = "";         // under the title: how many and how much memory; then what just closed
    private int index;                  // 0..apps.size(): the last one is "Close all"
    private boolean onClose;            // the focus is on the Close button under the card
    private final Spring closeAllFocus = new Spring(0.2f, 0.85f, 0), closeFocus = new Spring(0.2f, 0.85f, 0);
    private final Spring scroll = new Spring(0.26f, 1f, 0);
    private final Spring appear = new Spring(0.3f, 0.9f, 0);
    private boolean okDown, backDown, running, frameQueued;
    private long lastNanos;

    private float k = 1;
    private Paint title, statusPaint, label, meta, closeText, hint, messagePaint;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG), imageFill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Map<Bitmap, BitmapShader> shaders = new WeakHashMap<>();
    private final Matrix matrix = new Matrix();
    private final RectF card = new RectF(), cell = new RectF(), tmp = new RectF();
    private String closeLabel = "", closeAllLabel = "", hintText = "";
    private float closeHalfCap;

    RecentsView(Activity a) {
        super(a);
        activity = a;
        click = new Click(a);
        setFocusable(true);
        message = Ui.tr("Procurando os apps abertos…", "Looking for open apps…", "Buscando las apps abiertas…");
        border.setStyle(Paint.Style.STROKE);
    }

    // MARK: - Lifecycle

    void resume() {
        running = true;
        click.start();
        appear.target = 1;
        if (!loaded) load();
        wake();
    }

    void pause() {
        running = false;
        click.stop();
        Choreographer.getInstance().removeFrameCallback(this);
        frameQueued = false;
    }

    void destroy() {
        pause();
        click.release();
        io.execute(this::disconnect);
        io.shutdown();
    }

    // MARK: - The list (background thread)

    private void load() {
        loaded = true;
        io.execute(() -> {
            try {
                List<String> pkgs = HomeMode.openApps(connection());
                List<App> list = new ArrayList<>();
                for (String pkg : pkgs) list.add(new App(pkg, HomeApps.label(getContext(), pkg), HomeApps.tile(getContext(), pkg)));
                main.post(() -> showList(list));
                Map<String, Long> memory = HomeMode.appMemoryMB(connection());
                main.post(() -> showMemory(memory));
            } catch (IOException e) {
                AppLog.w("recent apps: " + e.getMessage());
                disconnect();
                main.post(() -> {
                    message = Ui.trf(Ui.tr("Não deu para ler os apps abertos: {error}", "Couldn't read the open apps: {error}", "No se pudieron leer las apps abiertas: {error}"), "error", e.getMessage());
                    invalidate();
                });
            }
        });
    }

    /** One connection while the panel is open (each new one costs a handshake); made again if it broke. */
    private Adb connection() throws IOException {
        // The first time (a TV that never allowed this app), the TV asks "Allow USB debugging?": the panel says so.
        if (adb == null) adb = Adb.connect(getContext(), 60_000, () -> main.post(() -> {
            message = Ui.tr("Aceite “Permitir depuração USB?” na TV (marque “Sempre permitir”).",
                    "Accept “Allow USB debugging?” on the TV (tick “Always allow”).",
                    "Acepta “¿Permitir depuración USB?” en la TV (marca “Permitir siempre”).");
            invalidate();
        }));
        return adb;
    }

    private void disconnect() {
        if (adb == null) return;
        adb.close();
        adb = null;
    }

    private void showList(List<App> list) {
        apps = list;
        index = 0;
        onClose = false;
        message = list.isEmpty() ? Ui.tr("Nenhum app aberto. Tudo já está encerrado.", "No apps open. Everything is already closed.", "Ninguna app abierta. Todo ya está cerrado.") : "";
        measureTexts();
        retarget(true);
        updateStatus();
        wake();
        if (!list.isEmpty()) announceForAccessibility(status + ". " + list.get(0).label);
    }

    private void showMemory(Map<String, Long> memory) {
        for (App app : apps) {
            Long mb = memory.get(app.pkg);
            app.mb = mb == null ? -1 : mb;
        }
        measureTexts();
        updateStatus();
        invalidate();
    }

    private long totalMb() {
        long sum = 0;
        for (App app : apps) if (app.mb > 0) sum += app.mb;
        return sum;
    }

    private void updateStatus() {
        if (apps.isEmpty()) {
            status = "";
            return;
        }
        String open = Ui.plural(Ui.tr("plural|one:{n} app aberto|other:{n} apps abertos", "plural|one:{n} app open|other:{n} apps open", "plural|one:{n} app abierta|other:{n} apps abiertas"), apps.size());
        long mb = totalMb();
        status = mb > 0 ? Ui.trf(Ui.tr("{open} · {mb} MB em uso", "{open} · {mb} MB in use", "{open} · {mb} MB en uso"), "open", open, "mb", mb) : open;
    }

    /** Closes one app, or all of them (`app` null), and takes them off the row. */
    private void close(App app) {
        List<App> gone = app == null ? new ArrayList<>(apps) : Collections.singletonList(app);
        if (gone.isEmpty()) return;
        List<String> pkgs = new ArrayList<>();
        Map<String, Long> memory = new HashMap<>();
        for (App a : gone) {
            pkgs.add(a.pkg);
            if (a.mb >= 0) memory.put(a.pkg, a.mb);
        }
        List<App> left = new ArrayList<>(apps);
        left.removeAll(gone);
        apps = left;
        index = Math.min(index, apps.size());
        onClose = onClose && index < apps.size();
        if (apps.isEmpty()) message = Ui.tr("Nenhum app aberto. Tudo já está encerrado.", "No apps open. Everything is already closed.", "Ninguna app abierta. Todo ya está cerrado.");
        retarget(false);
        updateStatus();
        click.open();
        String what = gone.size() == 1 ? gone.get(0).label : null;
        io.execute(() -> {
            String done;
            try {
                long mb = HomeMode.closeApps(connection(), pkgs, memory);
                AppLog.w("recent apps: closed " + pkgs + ", " + mb + " MB");
                done = what != null ? (mb > 0 ? Ui.trf(Ui.tr("{app} encerrado, {mb} MB liberados", "{app} closed, {mb} MB freed", "{app} cerrada, {mb} MB liberados"), "app", what, "mb", mb) : Ui.trf(Ui.tr("{app} encerrado", "{app} closed", "{app} cerrada"), "app", what))
                        : Ui.plural(Ui.tr("plural|one:{n} app encerrado, {mb} MB liberados|other:{n} apps encerrados, {mb} MB liberados", "plural|one:Closed {n} app, {mb} MB freed|other:Closed {n} apps, {mb} MB freed", "plural|one:{n} app cerrada, {mb} MB liberados|other:{n} apps cerradas, {mb} MB liberados"), pkgs.size())
                                .replace("{mb}", String.valueOf(mb));
            } catch (IOException e) {
                AppLog.w("recent apps: close failed: " + e.getMessage());
                disconnect();
                done = what != null ? Ui.trf(Ui.tr("Não deu para encerrar {app}", "Couldn't close {app}", "No se pudo cerrar {app}"), "app", what) : Ui.tr("Não deu para encerrar os apps.", "Couldn't close the apps.", "No se pudieron cerrar las apps.");
            }
            String text = done;
            main.post(() -> {
                status = text;
                announceForAccessibility(text);
                invalidate();
            });
        });
    }

    private void openApp(App app) {
        RectF r = cardRect(apps.indexOf(app), tmp);
        HomeApps.open(activity, HomeApps.app(app.pkg, app.label), this, Math.round(r.left), Math.round(r.top),
                Math.round(r.width()), Math.round(r.height()));
        activity.finish();
    }

    // MARK: - Keys

    boolean onKey(KeyEvent e) {
        wake();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) {
                    click.move();
                    int code = e.getKeyCode();
                    move(code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : 0,
                            code == KeyEvent.KEYCODE_DPAD_UP ? -1 : code == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : 0);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                // Acts on release, as the home screen; held, it closes the focused app instead.
                if (down) {
                    if (e.getRepeatCount() == 0) okDown = true;
                    else if (okDown && heldLong(e) && !onClose && index < apps.size()) {
                        okDown = false;
                        close(apps.get(index));
                    }
                } else {
                    if (okDown && !e.isCanceled()) press();
                    okDown = false;
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (down) {
                    if (e.getRepeatCount() == 0) backDown = true;
                } else {
                    if (backDown && !e.isCanceled()) {
                        click.move();
                        activity.finish();
                    }
                    backDown = false;
                }
                return true;
            default:
                return false;
        }
    }

    private static boolean heldLong(KeyEvent e) {
        return e.isLongPress() || e.getEventTime() - e.getDownTime() >= ViewConfiguration.getLongPressTimeout();
    }

    private void press() {
        if (apps.isEmpty()) {
            activity.finish();
            return;
        }
        if (index == apps.size()) close(null);
        else if (onClose) close(apps.get(index));
        else {
            click.open();
            openApp(apps.get(index));
        }
    }

    private void move(int dx, int dy) {
        if (apps.isEmpty()) return;
        if (dx != 0) {
            int next = Math.max(0, Math.min(apps.size(), index + dx));
            if (next == index) return;
            index = next;
            if (index == apps.size()) onClose = false;
        } else if (dy > 0 && !onClose && index < apps.size()) {
            onClose = true;
        } else if (dy < 0 && onClose) {
            onClose = false;
        } else {
            return;
        }
        retarget(false);
        announceForAccessibility(index == apps.size() ? closeAllLabel : onClose ? closeLabel + ", " + apps.get(index).label
                : apps.get(index).label + (apps.get(index).mbShown.isEmpty() ? "" : ", " + apps.get(index).mbShown));
    }

    private void retarget(boolean snap) {
        for (int i = 0; i < apps.size(); i++) {
            Spring f = apps.get(i).focus;
            f.target = i == index ? 1 : 0;
            if (snap) f.snap(f.target);
        }
        closeAllFocus.target = index == apps.size() && !apps.isEmpty() ? 1 : 0;
        closeFocus.target = onClose ? 1 : 0;
        // The row scrolls so the focused card stays on screen, a card's width from the edges when it can.
        float step = (CARD_W + CARD_GAP) * k, x = index * step, view = getWidth() - 2 * MARGIN * k;
        float target = scroll.target;
        if (x - target > view - step) target = x - view + step;
        if (x - target < 0) target = x;
        scroll.target = Math.max(0, target);
        if (snap) {
            closeAllFocus.snap(closeAllFocus.target);
            closeFocus.snap(closeFocus.target);
            scroll.snap(scroll.target);
        }
        wake();
    }

    // MARK: - Frames

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
        boolean moving = closeAllFocus.step(dt) | closeFocus.step(dt) | scroll.step(dt) | appear.step(dt);
        for (App app : apps) moving |= app.focus.step(dt);
        invalidate();
        if (moving) {
            frameQueued = true;
            Choreographer.getInstance().postFrameCallback(this);
        }
    }

    @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        k = w / 1920f;
        title = Ui.text(Ui.displaySemibold, 56 * k, WHITE);
        statusPaint = Ui.text(Ui.medium, 27 * k, 0xA6FFFFFF);
        label = Ui.text(Ui.semibold, 27 * k, 0xE6FFFFFF);
        meta = Ui.numbers(Ui.medium, 23 * k, 0x8CFFFFFF);
        closeText = Ui.text(Ui.semibold, 25 * k, WHITE);
        closeHalfCap = Ui.halfCap(closeText);
        hint = Ui.text(Ui.medium, 23 * k, 0x80FFFFFF);
        hint.setTextAlign(Paint.Align.CENTER);
        messagePaint = Ui.text(Ui.medium, 32 * k, 0xCCFFFFFF);
        messagePaint.setTextAlign(Paint.Align.CENTER);
        shadow.setMaskFilter(new BlurMaskFilter(SHADOW_BLUR * k, BlurMaskFilter.Blur.NORMAL));
        border.setStrokeWidth(2 * k);
        closeLabel = Ui.tr("Encerrar", "Close", "Cerrar");
        closeAllLabel = Ui.tr("Encerrar todos", "Close all", "Cerrar todas");
        hintText = Ui.tr("OK abre · ↓ ou segurar OK encerra · Voltar sai", "OK opens · ↓ or hold OK closes · Back leaves", "OK abre · ↓ o mantener OK cierra · Atrás sale");
        measureTexts();
        retarget(true);
    }

    /** Names cut to the card's width and the MB, made once (never while drawing). */
    private void measureTexts() {
        if (label == null) return;
        TextPaint tp = new TextPaint(label);
        for (App app : apps) {
            app.labelShown = TextUtils.ellipsize(app.label, tp, CARD_W * k, TextUtils.TruncateAt.END).toString();
            app.mbShown = app.mb > 0 ? Ui.trf(Ui.tr("{mb} MB", "{mb} MB", "{mb} MB"), "mb", app.mb) : "";
        }
    }

    // MARK: - Drawing

    private RectF cardRect(int i, RectF out) {
        float x = MARGIN * k + i * (CARD_W + CARD_GAP) * k - scroll.value;
        out.set(x, CARD_TOP * k, x + CARD_W * k, (CARD_TOP + CARD_H) * k);
        return out;
    }

    @Override protected void onDraw(Canvas c) {
        if (title == null) return;
        float a = Ui.clamp(appear.value);
        c.drawColor(Ui.alpha(0xF2000000, a));
        c.drawText(Ui.tr("Apps recentes", "Recent apps", "Apps recientes"), MARGIN * k, 150 * k, title);
        if (!status.isEmpty()) c.drawText(status, MARGIN * k, 204 * k, statusPaint);
        if (!message.isEmpty()) {
            c.drawText(message, getWidth() / 2f, 540 * k, messagePaint);
            return;
        }
        int focused = -1;
        for (int i = 0; i < apps.size(); i++) {
            if (i == index) focused = i;
            else drawApp(c, i);
        }
        drawCloseAll(c);
        if (focused >= 0) drawApp(c, focused);   // last: it grows over its neighbors
        c.drawText(hintText, getWidth() / 2f, 1010 * k, hint);
    }

    private void drawApp(Canvas c, int i) {
        App app = apps.get(i);
        RectF base = cardRect(i, card);
        if (base.right < 0 || base.left > getWidth()) return;
        float f = Ui.clamp(app.focus.value), s = 1 + 0.08f * f, lift = f * (1 - Ui.clamp(closeFocus.value) * 0.5f);
        float cx = base.centerX(), cy = base.centerY();
        cell.set(cx - base.width() * s / 2, cy - base.height() * s / 2, cx + base.width() * s / 2, cy + base.height() * s / 2);
        float r = RADIUS * k * s;
        if (f > 0.01f) {
            tmp.set(cell);
            tmp.offset(0, 16 * k * f);
            shadow.setColor(Ui.alpha(0xFF000000, 0.55f * f));
            shadows.draw(c, tmp, r, SHADOW_BLUR * k, shadow);
        }
        focusRing.glow(c, cell, r, lift, k, 0x4DFFFFFF);
        drawImage(c, app.tile, cell, r);
        border.setColor(0x1FFFFFFF);
        c.drawRoundRect(cell, r, r, border);
        focusRing.ring(c, cell, r, lift, k, 0xF2FFFFFF);
        float below = base.bottom + (40 + 14 * f) * k;
        label.setAlpha(Math.round(255 * (0.75f + 0.25f * f)));
        c.drawText(app.labelShown, base.left, below + 8 * k, label);
        if (!app.mbShown.isEmpty()) c.drawText(app.mbShown, base.left, below + 44 * k, meta);
        // The Close button, under the focused card only.
        if (f > 0.01f) {
            float cf = Ui.clamp(closeFocus.value);
            float top = base.bottom + CLOSE_GAP * k + 24 * k, w = CLOSE_W * k, h = CLOSE_H * k;
            tmp.set(base.left, top, base.left + w, top + h);
            fill.setShader(null);
            fill.setColor(Ui.alpha(0x2EFFFFFF, f * (1 - cf)));
            c.drawRoundRect(tmp, h / 2, h / 2, fill);
            if (cf > 0.01f) {
                fill.setColor(Ui.alpha(WHITE, f * cf));
                c.drawRoundRect(tmp, h / 2, h / 2, fill);
            }
            int ink = blend(WHITE, INK, cf);
            ink = Ui.alpha(ink, f);
            Symbols.draw(getContext(), c, "close", tmp.left + 36 * k, tmp.centerY(), 25 * k, ink);
            closeText.setColor(ink);
            c.drawText(closeLabel, tmp.left + 62 * k, tmp.centerY() + closeHalfCap, closeText);
        }
    }

    /** "Close all", the row's last card: a frosted card with the symbol, white when focused. */
    private void drawCloseAll(Canvas c) {
        if (apps.isEmpty()) return;
        RectF base = cardRect(apps.size(), card);
        if (base.left > getWidth()) return;
        float f = Ui.clamp(closeAllFocus.value), s = 1 + 0.08f * f;
        float cx = base.centerX(), cy = base.centerY();
        cell.set(cx - base.width() * s / 2, cy - base.height() * s / 2, cx + base.width() * s / 2, cy + base.height() * s / 2);
        float r = RADIUS * k * s;
        fill.setShader(null);
        fill.setColor(0x24FFFFFF);
        c.drawRoundRect(cell, r, r, fill);
        if (f > 0.01f) {
            fill.setColor(Ui.alpha(WHITE, f));
            c.drawRoundRect(cell, r, r, fill);
        }
        int ink = blend(WHITE, INK, f);
        Symbols.draw(getContext(), c, "close", cx, cy - 22 * k, 44 * k * s, ink);
        closeText.setColor(ink);
        float tw = closeText.measureText(closeAllLabel);
        c.drawText(closeAllLabel, cx - tw / 2, cy + 46 * k, closeText);
    }

    /** The card's art with rounded corners: a shader on a rounded rectangle (no clip mask, cheap on older GPUs). */
    private void drawImage(Canvas c, Bitmap image, RectF r, float radius) {
        if (image == null) {
            fill.setShader(null);
            fill.setColor(0xFF15161C);
            c.drawRoundRect(r, radius, radius, fill);
            return;
        }
        BitmapShader shader = shaders.get(image);
        if (shader == null) {
            shader = new BitmapShader(image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            shaders.put(image, shader);
        }
        matrix.setScale(r.width() / image.getWidth(), r.height() / image.getHeight());
        matrix.postTranslate(r.left, r.top);
        shader.setLocalMatrix(matrix);
        imageFill.setShader(shader);
        c.drawRoundRect(r, radius, radius, imageFill);
        imageFill.setShader(null);
    }

    private static int blend(int a, int b, float f) {
        f = Ui.clamp(f);
        int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return Math.round(aa + (ba - aa) * f) << 24 | Math.round(ar + (br - ar) * f) << 16
                | Math.round(ag + (bg - ag) * f) << 8 | Math.round(ab + (bb - ab) * f);
    }
}
