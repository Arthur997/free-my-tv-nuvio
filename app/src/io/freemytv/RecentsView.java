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
 * Recent apps, like the iPhone's app switcher: the open apps as big cards in a row (most recent first), the focused one
 * large in the middle and its neighbors smaller beside it, each with its icon and name above. ← → move along, OK opens,
 * ↑ throws the card up and closes the app (as the swipe up does), ↓ reaches "Close all", Back leaves. The list and the
 * closing go through this app's own debugging connection (HomeMode.openApps, closeApps), on a background thread that
 * keeps one connection while it's open. Measurements in a 1920 × 1080 space.
 */
final class RecentsView extends View implements Choreographer.FrameCallback {
    private static final float CARD_W = 1040, CARD_H = 585, CARD_CY = 540, RADIUS = 40;
    /** How far the neighbors sit from the middle (the first, then each one after it) and how small they get. */
    private static final float NEAR = 700, FAR = 380, NEAR_SCALE = 0.78f, FAR_SCALE = 0.66f;
    private static final float ICON = 60, PILL_H = 72, SHADOW_BLUR = 30;
    private static final int MAX_APPS = 12;
    private static final int WHITE = 0xFFF5F5F7, INK = 0xFF1C1C1E;

    /** One open app: its package, name, big card, icon and the MB it uses (-1 until measured). */
    private static final class App {
        final String pkg, label;
        final Bitmap art, icon;
        long mb = -1;
        String labelShown = "", mbShown = "";
        final Spring gone = new Spring(0.32f, 1f, 0);   // 1: thrown up (closing)

        App(String pkg, String label, Bitmap art, Bitmap icon) {
            this.pkg = pkg;
            this.label = label;
            this.art = art;
            this.icon = icon;
        }
    }

    private final Activity activity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Ui.background("recents");
    private final Click click;
    private final Shadows shadows = new Shadows();
    private final FocusRing focusRing = new FocusRing(shadows);
    private Adb adb;                    // only touched on `io`

    private List<App> apps = new ArrayList<>();
    private final List<App> leaving = new ArrayList<>();   // thrown up, still flying off
    private boolean loaded;
    private String message = "";        // instead of the cards: loading, nothing open, or why it can't
    private String status = "";         // at the top: how many and how much memory; then what just closed
    private int index;
    private boolean onCloseAll;         // the focus is on "Close all", under the cards
    private final Spring scroll = new Spring(0.3f, 1f, 0);          // the card in the middle, as a float index
    private final Spring cardFocus = Spring.focus(1), pillFocus = Spring.focus(0);
    private final Spring appear = new Spring(0.3f, 0.9f, 0);
    private boolean okDown, backDown, running, frameQueued;
    private long lastNanos;

    private float k = 1;
    private Paint statusPaint, name, meta, pillText, hint, messagePaint;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG), imageFill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Map<Bitmap, BitmapShader> shaders = new WeakHashMap<>();
    private final Matrix matrix = new Matrix();
    private final RectF card = new RectF(), tmp = new RectF();
    private final int[] order = new int[MAX_APPS];
    private String closeAllLabel = "", hintText = "";
    private float pillHalfCap, nameHalfCap;

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
        // The cards are drawn at their size on screen, read before the view is laid out.
        float scale = getResources().getDisplayMetrics().widthPixels / 1920f;
        int w = Math.round(CARD_W * scale), h = Math.round(CARD_H * scale), icon = Math.round(ICON * scale);
        io.execute(() -> {
            try {
                List<String> pkgs = HomeMode.openApps(connection());
                List<App> list = new ArrayList<>();
                for (String pkg : pkgs) {
                    if (list.size() >= MAX_APPS) break;
                    list.add(new App(pkg, HomeApps.label(getContext(), pkg), HomeApps.art(getContext(), pkg, w, h),
                            HomeApps.iconBitmap(getContext(), pkg, icon)));
                }
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
        onCloseAll = false;
        message = list.isEmpty() ? nothingOpen() : "";
        measureTexts();
        retarget(true);
        updateStatus();
        wake();
        if (!list.isEmpty()) announceForAccessibility(status + ". " + list.get(0).label);
    }

    private static String nothingOpen() {
        return Ui.tr("Nenhum app aberto. Tudo já está encerrado.", "No apps open. Everything is already closed.", "Ninguna app abierta. Todo ya está cerrado.");
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

    /** Closes one app (its card flies up), or all of them (`app` null), and takes them off the row. */
    private void close(App app) {
        List<App> gone = app == null ? new ArrayList<>(apps) : Collections.singletonList(app);
        if (gone.isEmpty()) return;
        List<String> pkgs = new ArrayList<>();
        Map<String, Long> memory = new HashMap<>();
        for (App a : gone) {
            pkgs.add(a.pkg);
            if (a.mb >= 0) memory.put(a.pkg, a.mb);
            a.gone.target = 1;
            leaving.add(a);
        }
        List<App> left = new ArrayList<>(apps);
        left.removeAll(gone);
        apps = left;
        index = Math.min(index, Math.max(0, apps.size() - 1));
        if (apps.isEmpty()) {
            onCloseAll = false;
            message = nothingOpen();
        }
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
        RectF r = cardRect(index - scroll.value, 0, tmp);
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
                    else if (okDown && heldLong(e) && !onCloseAll && index < apps.size()) {
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
        } else if (onCloseAll) {
            close(null);
        } else {
            click.open();
            openApp(apps.get(index));
        }
    }

    /** ← → along the cards; ↑ on a card closes it; ↓ to "Close all" and ↑ back. */
    private void move(int dx, int dy) {
        if (apps.isEmpty()) return;
        if (dx != 0) {
            if (onCloseAll) return;
            int next = Math.max(0, Math.min(apps.size() - 1, index + dx));
            if (next == index) return;
            index = next;
        } else if (dy < 0) {
            if (onCloseAll) onCloseAll = false;
            else {
                close(apps.get(index));
                return;
            }
        } else if (!onCloseAll) {
            onCloseAll = true;
        } else {
            return;
        }
        retarget(false);
        App app = apps.get(index);
        announceForAccessibility(onCloseAll ? closeAllLabel : app.label + (app.mbShown.isEmpty() ? "" : ", " + app.mbShown));
    }

    private void retarget(boolean snap) {
        scroll.target = index;
        cardFocus.target = onCloseAll ? 0 : 1;
        pillFocus.target = onCloseAll ? 1 : 0;
        if (snap) {
            scroll.snap(scroll.target);
            cardFocus.snap(cardFocus.target);
            pillFocus.snap(pillFocus.target);
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
        boolean moving = scroll.step(dt) | cardFocus.step(dt) | pillFocus.step(dt) | appear.step(dt);
        for (int i = leaving.size() - 1; i >= 0; i--) {
            App app = leaving.get(i);
            if (app.gone.step(dt)) moving = true;
            else leaving.remove(i);   // off the screen
        }
        invalidate();
        if (moving) {
            frameQueued = true;
            Choreographer.getInstance().postFrameCallback(this);
        }
    }

    @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        k = w / 1920f;
        statusPaint = Ui.text(Ui.medium, 26 * k, 0xA6FFFFFF);
        statusPaint.setTextAlign(Paint.Align.CENTER);
        name = Ui.text(Ui.semibold, 30 * k, WHITE);
        nameHalfCap = Ui.halfCap(name);
        meta = Ui.numbers(Ui.medium, 24 * k, 0x8CFFFFFF);
        pillText = Ui.text(Ui.semibold, 26 * k, WHITE);
        pillText.setTextAlign(Paint.Align.CENTER);
        pillHalfCap = Ui.halfCap(pillText);
        hint = Ui.text(Ui.medium, 23 * k, 0x80FFFFFF);
        hint.setTextAlign(Paint.Align.CENTER);
        messagePaint = Ui.text(Ui.medium, 32 * k, 0xCCFFFFFF);
        messagePaint.setTextAlign(Paint.Align.CENTER);
        shadow.setMaskFilter(new BlurMaskFilter(SHADOW_BLUR * k, BlurMaskFilter.Blur.NORMAL));
        border.setStrokeWidth(2 * k);
        closeAllLabel = Ui.tr("Encerrar todos", "Close all", "Cerrar todas");
        hintText = Ui.tr("OK abre · ↑ encerra · ↓ Encerrar todos · Voltar sai", "OK opens · ↑ closes · ↓ Close all · Back leaves",
                "OK abre · ↑ cierra · ↓ Cerrar todas · Atrás sale");
        measureTexts();
        retarget(true);
    }

    /** Names cut to fit beside the icon and the MB, made once (never while drawing). */
    private void measureTexts() {
        if (name == null) return;
        TextPaint tp = new TextPaint(name);
        for (App app : apps) {
            app.mbShown = app.mb > 0 ? Ui.trf(Ui.tr("{mb} MB", "{mb} MB", "{mb} MB"), "mb", app.mb) : "";
            float room = (CARD_W - ICON - 40) * k - (app.mbShown.isEmpty() ? 0 : meta.measureText(app.mbShown) + 24 * k);
            app.labelShown = TextUtils.ellipsize(app.label, tp, room, TextUtils.TruncateAt.END).toString();
        }
    }

    // MARK: - Drawing

    /**
     * Where a card is when it's `d` places from the middle (fractions while the row slides): the focused one big in
     * the middle, the next ones smaller and closer together toward the sides; `lift` (0 to 1) throws it up off screen.
     */
    private RectF cardRect(float d, float lift, RectF out) {
        float a = Math.abs(d), sign = Math.signum(d);
        float offset = a <= 1 ? a * NEAR : NEAR + (a - 1) * FAR;
        float s = a <= 1 ? 1 - (1 - NEAR_SCALE) * a : NEAR_SCALE - (NEAR_SCALE - FAR_SCALE) * Math.min(1, a - 1);
        float cx = (960 + sign * offset) * k, cy = CARD_CY * k - lift * 900 * k;
        float w = CARD_W * k * s, h = CARD_H * k * s;
        out.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2);
        return out;
    }

    @Override protected void onDraw(Canvas c) {
        if (name == null) return;
        float a = Ui.clamp(appear.value);
        c.drawColor(Ui.alpha(0xF2000000, a));
        if (!status.isEmpty()) c.drawText(status, getWidth() / 2f, 96 * k, statusPaint);
        for (App app : leaving) drawCard(c, app, 0, app.gone.value);
        if (!message.isEmpty()) {
            c.drawText(message, getWidth() / 2f, 540 * k, messagePaint);
            return;
        }
        // Far ones first: the focused card is drawn last, over its neighbors.
        int n = apps.size();
        for (int i = 0; i < n; i++) {
            // Insertion sort by distance from the middle, farthest first (a dozen at most; nothing allocated).
            int j = i;
            while (j > 0 && Math.abs(order[j - 1] - scroll.value) < Math.abs(i - scroll.value)) {
                order[j] = order[j - 1];
                j--;
            }
            order[j] = i;
        }
        for (int j = 0; j < n; j++) drawCard(c, apps.get(order[j]), order[j] - scroll.value, 0);
        drawCloseAll(c);
        c.drawText(hintText, getWidth() / 2f, 1030 * k, hint);
    }

    /** A card: shadow, art, a veil that deepens away from the middle, the focus ring, and the icon and name above it. */
    private void drawCard(Canvas c, App app, float d, float lift) {
        float a = Math.abs(d);
        if (a > 3) return;
        RectF r = cardRect(d, lift, card);
        if (r.right < 0 || r.left > getWidth()) return;
        float alpha = Ui.clamp(3 - a) * (1 - Ui.clamp(lift * 1.4f)), radius = RADIUS * k * (r.width() / (CARD_W * k));
        if (alpha < 0.01f) return;
        tmp.set(r);
        tmp.offset(0, 20 * k);
        shadow.setColor(Ui.alpha(0xFF000000, 0.6f * alpha));
        shadows.draw(c, tmp, radius, SHADOW_BLUR * k, shadow);
        imageFill.setAlpha(Math.round(255 * alpha));
        drawImage(c, app.art, r, radius);
        float veil = 0.45f * Ui.clamp(a);
        if (veil > 0.01f) {
            fill.setColor(Ui.alpha(0xFF000000, veil * alpha));
            c.drawRoundRect(r, radius, radius, fill);
        }
        border.setColor(Ui.alpha(0x26FFFFFF, alpha));
        c.drawRoundRect(r, radius, radius, border);
        float focus = Ui.clamp(1 - a) * Ui.clamp(cardFocus.value) * (1 - lift);
        focusRing.ring(c, r, radius, focus, k, 0xF2FFFFFF);
        // The icon and the name above the card, as the iPhone's switcher shows them.
        float s = r.width() / (CARD_W * k), icon = ICON * k * s, top = r.top - icon - 18 * k * s;
        if (app.icon != null) {
            iconPaint.setAlpha(Math.round(255 * alpha));
            tmp.set(r.left, top, r.left + icon, top + icon);
            c.drawBitmap(app.icon, null, tmp, iconPaint);
        }
        float textAlpha = alpha * (0.55f + 0.45f * Ui.clamp(1 - a));
        name.setTextSize(30 * k * s);
        name.setAlpha(Math.round(255 * textAlpha));
        float base = top + icon / 2 + nameHalfCap * s, x = r.left + icon + 16 * k * s;
        c.drawText(app.labelShown, x, base, name);
        if (!app.mbShown.isEmpty()) {
            meta.setTextSize(24 * k * s);
            meta.setAlpha(Math.round(255 * 0.55f * textAlpha));
            c.drawText(app.mbShown, x + name.measureText(app.labelShown) + 20 * k * s, base, meta);
        }
        name.setTextSize(30 * k);
        meta.setTextSize(24 * k);
    }

    /** "Close all", a pill under the cards: frosted at rest, white when focused. */
    private void drawCloseAll(Canvas c) {
        if (apps.isEmpty()) return;
        float f = Ui.clamp(pillFocus.value), w = (pillText.measureText(closeAllLabel) + 120 * k) * (1 + 0.06f * f), h = PILL_H * k * (1 + 0.06f * f);
        float cx = getWidth() / 2f, cy = (CARD_CY + CARD_H / 2 + 110) * k;
        tmp.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2);
        fill.setColor(0x2EFFFFFF);
        c.drawRoundRect(tmp, h / 2, h / 2, fill);
        if (f > 0.01f) {
            fill.setColor(Ui.alpha(WHITE, f));
            c.drawRoundRect(tmp, h / 2, h / 2, fill);
        }
        int ink = blend(WHITE, INK, f);
        float tw = pillText.measureText(closeAllLabel);
        Symbols.draw(getContext(), c, "close", cx - tw / 2 - 22 * k, cy, 26 * k, ink);
        pillText.setColor(ink);
        c.drawText(closeAllLabel, cx + 16 * k, cy + pillHalfCap, pillText);
    }

    /** The card's art with rounded corners: a shader on a rounded rectangle (no clip mask, cheap on older GPUs). */
    private void drawImage(Canvas c, Bitmap image, RectF r, float radius) {
        if (image == null) {
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
