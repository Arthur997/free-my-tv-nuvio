package io.freemytv;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Free My TV's settings (the app's icon, "All Apps", or the gear on the home screen), in the style of Apple TV's
 * settings: the big symbol and title on the left, the list on the right with the focused row in white. "Home screen"
 * opens the Free My TV / Original choice (with a check on the current one); the switch runs in the main process
 * (HomeMode) and this screen only asks for it and follows the state file.
 */
public class SettingsActivity extends Activity {
    private SettingsView view;
    private DreamView preview;   // "Screensaver": the screensaver right here, without changing the system setting

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Watchdog.install(this);
        Ui.init(this);
        view = new SettingsView(this);
        setContentView(view);
    }

    @Override protected void onDestroy() {
        view.release();
        super.onDestroy();
    }

    @Override protected void onResume() {
        super.onResume();
        view.resume();
        // From ADB: am start -n io.freemytv/.SettingsActivity --ez screensaver true (straight to the preview).
        if (preview == null && getIntent().getBooleanExtra("screensaver", false)) {
            getIntent().removeExtra("screensaver");
            view.pause();
            showPreview();
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    @Override protected void onPause() {
        closePreview();     // before pausing the view: closing the preview resumes it (view.resume)
        view.pause();
        super.onPause();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (preview != null) {
            // Like the real screensaver: arrows, OK or Back close it (other keys go on to the system).
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_BACK:
                    if (event.getAction() == KeyEvent.ACTION_UP) closePreview();
                    return true;
                default:
                    return super.dispatchKeyEvent(event);
            }
        }
        if (view.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    void showPreview() {
        preview = new DreamView(this);
        setContentView(preview);
        preview.start();
    }

    /**
     * More Settings > TMDB key: a text field with the system keyboard (the Google TV app on a phone types into it, and
     * can paste). The current key is never shown; saving an empty field goes back to config.properties' key.
     */
    void askTmdbKey() {
        android.widget.EditText input = new android.widget.EditText(this);
        input.setSingleLine(true);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        input.setHint(Ui.tr("API Key ou Read Access Token", "API Key or Read Access Token"));
        android.widget.FrameLayout box = new android.widget.FrameLayout(this);
        int pad = Math.round(24 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, 0, pad, 0);
        box.addView(input);
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(Ui.tr("Chave do TMDB", "TMDB key"))
                .setMessage(Ui.tr("Crie uma de graça em themoviedb.org → Settings → API e cole aqui. Dica: o app Google TV no "
                        + "celular serve de teclado (e cola).", "Create one for free at themoviedb.org → Settings → API and paste it "
                        + "here. Tip: the Google TV app on a phone works as a keyboard (and pastes)."))
                .setView(box)
                .setPositiveButton(Ui.tr("Salvar", "Save"), (d, w) -> saveTmdbKey(input.getText().toString()))
                .setNegativeButton(Ui.tr("Cancelar", "Cancel"), null);
        if (Prefs.typedTmdbKey()) builder.setNeutralButton(Ui.tr("Apagar", "Remove"), (d, w) -> saveTmdbKey(""));
        android.app.AlertDialog dialog = builder.create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
        dialog.show();
        input.requestFocus();
    }

    /** Spaces and line breaks from a paste are dropped; the picks are fetched again with the new key. */
    private void saveTmdbKey(String typed) {
        Prefs.setTmdbKey(typed.replaceAll("\\s+", ""));
        Recs.get(this).refreshIfStale();
        view.invalidate();
    }

    private void closePreview() {
        if (preview == null) return;
        preview.stop();
        preview = null;
        setContentView(view);
        view.resume();
    }

    /**
     * The settings, as pages of rows: the main menu, then one page per topic (Appearance, Picks, Weather and prices,
     * Screensaver, Remote Buttons), the shelf editor and the lists of choices. Every page is the same kind of list: a
     * row has a name, a value, what OK does and what ← → do, and a list longer than the screen scrolls with the focus.
     */
    static final class SettingsView extends View implements Choreographer.FrameCallback {
        /** A page of rows; the Home Screen choice and Status are drawn their own way. */
        private static final int PAGE = 0, HOME_CHOICE = 1, STATUS = 2;
        private static final float ROW_H = 68, ROW_GAP = 6, STEP = ROW_H + ROW_GAP, CHOICE_H = 104;
        private static final float LIST_X = 1000, LIST_R = 1824, LIST_TOP = 186, LIST_BOTTOM = 930;
        private static final int AMBER = 0xFFFFC266, INK = 0xFF1C1C1E;

        /** One row: its name and value (read when drawn), OK, ← → (null: none), and whether it's greyed out. */
        private static final class Row {
            final java.util.function.Supplier<String> label, value;
            final Runnable ok;
            final java.util.function.IntConsumer adjust;
            final java.util.function.BooleanSupplier dim;
            boolean opens;   // OK opens a page or a list: a chevron instead of a value
            boolean check;   // the choice in use, in a list of choices

            Row(java.util.function.Supplier<String> label, java.util.function.Supplier<String> value, Runnable ok,
                java.util.function.IntConsumer adjust, java.util.function.BooleanSupplier dim) {
                this.label = label;
                this.value = value;
                this.ok = ok;
                this.adjust = adjust;
                this.dim = dim;
            }
        }

        /** A page: its title (none on the main menu), the rows, the hint below, and how to read its rows again. */
        private static final class Page {
            final String title;
            final List<Row> rows = new ArrayList<>();
            java.util.function.Supplier<String> hint = () -> "", hint2 = () -> "";
            Runnable rebuild;   // lists that change (the shelf): rows read again after a change

            Page(String title) {
                this.title = title;
            }
        }

        /** Where Back goes: the page (or screen) and the focus and scroll it had. */
        private static final class Place {
            final int screen;
            final Page page;
            final int focus;
            final float scroll;

            Place(int screen, Page page, int focus, float scroll) {
                this.screen = screen;
                this.page = page;
                this.focus = focus;
                this.scroll = scroll;
            }
        }

        private final SettingsActivity activity;
        // The background: the home screen's (light by day, dark at night, or as the theme says), with a soft shade.
        private boolean lightTheme = true;
        private final Paint backdrop = new Paint(), shade = new Paint();
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final List<Spring> pop = new ArrayList<>();   // each row's focus, by its index in the page
        private final Spring scroll = new Spring(0.24f, 1f, 0);   // the first row shown (fractional while it moves)
        private final java.util.ArrayDeque<Place> back = new java.util.ArrayDeque<>();
        private final List<String> appPkgs = new ArrayList<>(), appLabels = new ArrayList<>();
        private final List<String[]> shelfRows = new ArrayList<>();
        private final List<String> shelfOrder = new ArrayList<>();
        private final RectF tmp = new RectF(), rowRect = new RectF(), focusBox = new RectF();
        private JSONObject state = new JSONObject(), recs = new JSONObject();
        private boolean hasKey;
        private int screen = PAGE, focus;
        private Page page, main;
        private boolean running, okDown, backDown, wasBusy, framePosted;
        private StaticLayout errorLayout;   // the last error, wrapped (rebuilt only when it changes)
        private String errorShown;
        private final Spoken spoken;
        private long startedAt, lastNanos, pendingAt;
        private int w, h;
        private float k = 1;
        private Paint title, subtitle, version, rowLabel, rowValue, rowSub, header, cellLabel, cellValue, warn, spinner, white, shadow;
        private TextPaint notice;
        // Made with the size and the theme, not on every frame: a list redraws on every frame of its focus animation.
        private Paint heading, hint;
        private final TextPaint fit = new TextPaint();   // for cutting a long name to its row
        private final Click click;   // the same click as the home screen's
        private String cityStatus = "";

        /** The files and the system setting the poll reads: off the UI thread (two files and a binder call). */
        private final java.util.concurrent.ExecutorService pollIo = Ui.background("settings");
        private String drawnSignature = "";

        /** Every 500 ms: reads the state in the background, then applies it here. */
        private final Runnable poll = new Runnable() {
            @Override public void run() {
                if (!running) return;
                Context c = getContext();
                pollIo.execute(() -> {
                    JSONObject newState = HomeMode.state(c);
                    boolean newHasKey = Recs.hasKey(c);
                    JSONObject newRecs = Recs.status(c);
                    String services = android.provider.Settings.Secure.getString(c.getContentResolver(),
                            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
                    boolean serviceOn = services != null && services.contains(HomeMode.PKG + "/");
                    handler.post(() -> applyPoll(newState, newHasKey, newRecs, serviceOn));
                });
            }
        };

        private void applyPoll(JSONObject newState, boolean newHasKey, JSONObject newRecs, boolean serviceOn) {
            if (!running) return;
            state = newState;
            hasKey = newHasKey;
            recs = newRecs;
            keysServiceOn = serviceOn;
            boolean nowBusy = !HomeMode.busyText(state).isEmpty();
            if (nowBusy) pendingAt = 0;
            // A switch just ended: TalkBack reads how it went (the newest notice).
            JSONArray notices = state.optJSONArray("notices");
            if (wasBusy && !nowBusy && notices != null && notices.length() > 0) {
                JSONObject newest = notices.optJSONObject(0);
                if (newest != null) announceForAccessibility(newest.optString("text"));
            }
            wasBusy = nowBusy;
            checkLearned();
            checkUpdating();
            // Still: no redraw. What's on screen comes from the state and the rows' values, so a change shows in either.
            String signature = screenSignature();
            if (!signature.equals(drawnSignature)) {
                drawnSignature = signature;
                invalidate();
            }
            handler.postDelayed(poll, 500);
        }

        private String screenSignature() {
            StringBuilder b = new StringBuilder(512).append(screen).append('|').append(state).append('|').append(recs)
                    .append('|').append(hasKey).append(keysServiceOn).append('|').append(page.hint.get()).append(page.hint2.get());
            int first = firstShown(), last = Math.min(rowCount(), first + visibleRows() + 1);
            for (int i = first; i < last; i++) b.append('|').append(rowText(i));
            return b.toString();
        }

        SettingsView(SettingsActivity a) {
            super(a);
            activity = a;
            click = new Click(a);
            setFocusable(true);
            setContentDescription("Free My TV");
            main = mainPage();
            page = main;
            // TalkBack: one item per row on screen, id = row + 1.
            spoken = new Spoken(this, new Spoken.Source() {
                @Override public void items(List<Integer> out) {
                    int first = firstShown(), last = Math.min(rowCount(), first + visibleRows() + 1);
                    for (int i = first; i < last; i++) out.add(i + 1);
                }
                @Override public String text(int id) { return id >= 1 && id <= rowCount() ? rowText(id - 1) : ""; }
                @Override public boolean bounds(int id, Rect out) {
                    if (id < 1 || id > rowCount()) return false;
                    rowBox(id - 1).round(out);
                    return out.height() > 0;
                }
                @Override public int focused() { return rowCount() == 0 ? -1 : focus + 1; }
                @Override public void focus(int id) {
                    focus = id - 1;
                    keepVisible(false);
                    invalidate();
                }
                @Override public void click(int id) { activate(); }
            });
        }

        @Override public AccessibilityNodeProvider getAccessibilityNodeProvider() {
            return spoken;
        }

        // MARK: - The pages

        /** The main menu: the home screen, one row per topic, then the shelf, the remote, the status and the TV's own. */
        private Page mainPage() {
            Page p = new Page(null);
            p.rows.add(new Row(() -> Ui.tr("Início", "Home Screen"),
                    () -> busy() ? "" : freeActive() ? "Free My TV" : Ui.tr("Original", "Original"), this::openHomeChoice, null, null));
            p.rows.add(opens(() -> Ui.tr("Aparência", "Appearance", "Apariencia"), () -> open(appearancePage())));
            p.rows.add(opens(() -> Ui.tr("Desempenho", "Performance", "Rendimiento"), () -> open(performancePage())));
            p.rows.add(opens(() -> Ui.tr("Recomendações", "Picks"), () -> open(picksPage())));
            p.rows.add(opens(() -> Ui.tr("Clima e cotações", "Weather and prices", "Clima y cotizaciones"), () -> open(weatherPage())));
            p.rows.add(opens(() -> Ui.tr("Relógio", "Clock", "Reloj"), () -> open(clockPage())));
            p.rows.add(opens(() -> Ui.tr("Tela de descanso", "Screensaver"), () -> open(screensaverPage())));
            p.rows.add(opens(() -> Ui.tr("Prateleira", "Shelf"), this::openShelfEditor));
            p.rows.add(opens(() -> Ui.tr("Apps da TV", "TV Apps", "Apps de la TV"), this::openTvApps));
            p.rows.add(opens(() -> Ui.tr("Botões do Controle", "Remote Buttons"), () -> {
                HomeMode.ensureRemoteService(getContext(), 0);   // the buttons work from inside other apps through this service
                loadApps();
                open(keysPage());
            }));
            p.rows.add(opens(() -> Ui.tr("Atualizações", "Updates", "Actualizaciones"), () -> open(updatesPage())));
            p.rows.add(opens(() -> Ui.tr("Estado", "Status", "Estado"), () -> go(STATUS, null)));
            // The last switch failed because the TV's debugging is off (a TV update can do that): this row then goes
            // straight to where it's turned on.
            p.rows.add(opens(() -> debuggingOff() ? Ui.tr("Ligar a depuração USB", "Turn on USB debugging", "Activar la depuración USB")
                    : Ui.tr("Ajustes da TV", "TV Settings"), () -> HomeApps.openSettings(activity,
                    debuggingOff() ? Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS : Settings.ACTION_SETTINGS)));
            return p;
        }

        /** Language, theme, units, the top of the home screen, the shelf's art and the sounds. */
        private Page appearancePage() {
            Page p = new Page(Ui.tr("Aparência", "Appearance", "Apariencia"));
            p.rows.add(choice(() -> Ui.tr("Idioma", "Language"), this::languageNames, () -> languageIndex(),
                    at -> Prefs.setLanguage(languageChoices()[at])));
            p.rows.add(choice(() -> Ui.tr("Tema", "Theme"),
                    () -> list(Ui.tr("Automático", "Automatic"), Ui.tr("Claro", "Light"), Ui.tr("Escuro", "Dark")),
                    () -> Math.max(0, java.util.Arrays.asList(THEMES).indexOf(Prefs.theme())), at -> Prefs.setTheme(THEMES[at])));
            p.rows.add(choice(() -> Ui.tr("Temperatura", "Temperature"), () -> list(Ui.tr("Automática", "Automatic"), "°C", "°F"),
                    () -> Math.max(0, java.util.Arrays.asList(UNITS).indexOf(Prefs.temperature())), at -> Prefs.setTemperature(UNITS[at])));
            p.rows.add(choice(() -> Ui.tr("Topo do Início", "Top of Home"),
                    () -> list(Ui.tr("Relógio e clima", "Clock and weather"), Ui.tr("Cotações", "Prices")),
                    () -> Prefs.topClock() ? 0 : 1, at -> Prefs.setTopClock(at == 0)));
            p.rows.add(choice(() -> Ui.tr("Capa dos apps na prateleira", "Shelf app art"),
                    () -> list(Ui.tr("Original do app", "The app's own"), Ui.tr("Logo", "Logo")),
                    () -> Prefs.originalArt() ? 0 : 1, at -> Prefs.setOriginalArt(at == 0)));
            p.rows.add(choice(() -> Ui.tr("Volume dos sons", "Sound volume", "Volumen de los sonidos"), SettingsView::soundLevels,
                    Prefs::soundLevel, Prefs::setSoundLevel));
            p.hint = () -> Ui.tr("OK abre as opções; ← e → mudam direto.", "OK opens the choices; ← and → change it right away.");
            return p;
        }

        // MARK: - Updates: the newest GitHub release (Updater), now or on its own

        private Updater.Release release;   // the newest, once read
        private boolean releaseRead, releaseFailed, readingRelease, updating;

        private Page updatesPage() {
            Page p = new Page(Ui.tr("Atualizações", "Updates", "Actualizaciones"));
            p.rows.add(new Row(() -> Ui.tr("Versão instalada", "Installed version", "Versión instalada"),
                    () -> Updater.installedName(getContext()), () -> {}, null, null));
            p.rows.add(new Row(() -> Ui.tr("Procurar atualização", "Check for updates", "Buscar actualización"), this::releaseState,
                    this::askUpdate, null, () -> updating));
            p.rows.add(onOff(() -> Ui.tr("Atualizar sozinho", "Update on its own", "Actualizar solo"), Prefs::autoUpdate,
                    Prefs::setAutoUpdate, null));
            p.hint = () -> focus == 2
                    ? Ui.tr("Procura a cada 6 horas e instala quando você sai do app.", "Checks every 6 hours and installs once you leave the app.",
                            "Busca cada 6 horas e instala cuando sales de la app.")
                    : Ui.tr("As versões novas vêm do GitHub do projeto.", "New versions come from the project's GitHub.",
                            "Las versiones nuevas vienen del GitHub del proyecto.");
            p.hint2 = () -> Ui.tr("Ao atualizar, o app reinicia sozinho.", "When it updates, the app starts again by itself.",
                    "Al actualizar, la app se reinicia sola.");
            readRelease();
            return p;
        }

        private String releaseState() {
            if (updating) return Ui.tr("Atualizando…", "Updating…", "Actualizando…");
            if (!releaseRead) return "…";
            if (releaseFailed) return Ui.tr("Sem conexão: OK tenta de novo", "No connection: OK tries again", "Sin conexión: OK lo intenta de nuevo");
            if (release == null || release.code <= Updater.installedCode(getContext())) return Ui.tr("Em dia", "Up to date", "Al día");
            return Ui.trf(Ui.tr("{version} disponível", "{version} available", "{version} disponible"), "version", release.version());
        }

        /** A newer release: asks, then installs it now. Otherwise OK reads GitHub again. */
        private void askUpdate() {
            if (updating) return;
            if (release == null || release.code <= Updater.installedCode(getContext())) {
                releaseRead = false;
                readRelease();
                return;
            }
            openPicker(Ui.trf(Ui.tr("Atualizar para {version}? O app reinicia.", "Update to {version}? The app starts again.",
                    "¿Actualizar a {version}? La app se reinicia."), "version", release.version()),
                    list(Ui.tr("Atualizar", "Update", "Actualizar"), Ui.tr("Cancelar", "Cancel")), 1, at -> {
                        if (at != 0) return;
                        Updater.requestNow(getContext());
                        updating = true;
                        updatingSince = SystemClock.uptimeMillis();
                        noticeBeforeUpdate = newestNoticeAt();
                    });
        }

        /** A working update restarts the app; if it's still here, a new notice (the failure) or 3 min end "Updating…". */
        private static final long UPDATE_WAIT_MS = 3 * 60_000L;
        private long updatingSince, noticeBeforeUpdate;

        private void checkUpdating() {
            if (!updating) return;
            if (newestNoticeAt() != noticeBeforeUpdate || SystemClock.uptimeMillis() - updatingSince > UPDATE_WAIT_MS) {
                updating = false;
            }
        }

        private long newestNoticeAt() {
            JSONArray notices = state.optJSONArray("notices");
            JSONObject newest = notices == null ? null : notices.optJSONObject(0);
            return newest == null ? 0 : newest.optLong("at");
        }

        private void readRelease() {
            if (readingRelease) return;
            readingRelease = true;
            new Thread(() -> {
                Updater.Release newest = null;
                boolean failed = false;
                try {
                    newest = Updater.latest();
                } catch (java.io.IOException e) {
                    AppLog.w("update check: " + e.getMessage());
                    failed = true;
                }
                Updater.Release read = newest;
                boolean readFailed = failed;
                handler.post(() -> {
                    readingRelease = false;
                    releaseRead = true;
                    releaseFailed = readFailed;
                    release = read;
                    invalidate();
                });
            }, "free-my-tv-update-check").start();
        }

        // MARK: - Performance: the light look, fast animations, optimized apps, and the background services to turn off

        private boolean optimizeRead, optimizeRunning, readingOptimize;
        private long optimizedAt;

        private Page performancePage() {
            Page p = new Page(Ui.tr("Desempenho", "Performance", "Rendimiento"));
            p.rows.add(choice(() -> Ui.tr("Modo desempenho", "Performance mode"),
                    () -> list(Ui.tr("Desligado", "Off"), Ui.tr("Ligado (sem vidro)", "On (no glass)")),
                    () -> Prefs.performance() ? 1 : 0, at -> Prefs.setPerformance(at == 1)));
            p.rows.add(onOff(() -> Ui.tr("Animações rápidas", "Fast animations", "Animaciones rápidas"),
                    () -> HomeMode.fastAnimations(state), on -> HomeMode.requestFastAnimations(getContext(), on), null));
            p.rows.add(new Row(() -> Ui.tr("Otimizar apps", "Optimize apps", "Optimizar apps"), this::optimizeState,
                    this::askOptimize, null, null));
            Row background = new Row(() -> Ui.tr("Serviços em segundo plano", "Background services", "Servicios en segundo plano"),
                    () -> "", this::openBackgroundApps, null, null);
            background.opens = true;
            p.rows.add(background);
            // Two short lines: a hint isn't wrapped.
            p.hint = () -> {
                switch (focus) {
                    case 1: return Ui.tr("Janelas e transições na metade do tempo.", "Windows and transitions in half the time.",
                            "Ventanas y transiciones en la mitad del tiempo.");
                    case 2: return Ui.tr("Compila o código mais usado: os apps abrem mais rápido.", "Compiles the most used code: apps open faster.",
                            "Compila el código más usado: las apps abren más rápido.");
                    case 3: return Ui.tr("Apps da TV que rodam escondidos e ocupam memória.", "The TV's apps that run out of sight and hold memory.",
                            "Apps de la TV que corren ocultas y ocupan memoria.");
                    default: return Ui.tr("OK abre as opções; ← e → mudam direto.", "OK opens the choices; ← and → change it right away.");
                }
            };
            p.hint2 = () -> {
                switch (focus) {
                    case 1: return Ui.tr("O modo Original volta como era.", "Original brings them back as they were.", "El modo Original las deja como estaban.");
                    case 2: return Ui.tr("A TV só faz isso parada. Enquanto roda, ela fica lenta.", "The TV only does it when idle. While it runs, it's slower.",
                            "La TV solo lo hace en reposo. Mientras corre, va más lenta.");
                    case 3: return Ui.tr("Você escolhe o que fica ligado.", "You pick what stays on.", "Tú eliges qué queda encendido.");
                    default: return "";
                }
            };
            readOptimize();
            return p;
        }

        private String optimizeState() {
            if (!optimizeRead) return "…";
            if (optimizeRunning) return Ui.tr("Otimizando…", "Optimizing…", "Optimizando…");
            if (optimizedAt == 0) return Ui.tr("Nunca", "Never", "Nunca");
            long days = (System.currentTimeMillis() - optimizedAt) / 86_400_000L;
            return days == 0 ? Ui.tr("Hoje", "Today", "Hoy") : Ui.plural(Ui.tr("plural|one:Há {n} dia|other:Há {n} dias",
                    "plural|one:{n} day ago|other:{n} days ago", "plural|one:Hace {n} día|other:Hace {n} días"), (int) days);
        }

        private void askOptimize() {
            if (optimizeRunning) return;
            openPicker(Ui.tr("Otimizar os apps agora? A TV fica lenta por alguns minutos.", "Optimize the apps now? The TV is slow for a few minutes.",
                    "¿Optimizar las apps ahora? La TV va lenta unos minutos."),
                    list(Ui.tr("Otimizar", "Optimize", "Optimizar"), Ui.tr("Cancelar", "Cancel")), 1, at -> {
                        if (at != 0) return;
                        HomeMode.requestOptimizeApps(getContext());
                        optimizeRunning = true;
                        handler.postDelayed(this::readOptimize, 5000);
                    });
        }

        /** Whether it runs and when it last ended, from the TV's shell; again every 10 s while it runs and the page is open. */
        private void readOptimize() {
            if (readingOptimize) return;
            readingOptimize = true;
            new Thread(() -> {
                long[] status = null;
                try {
                    status = HomeMode.optimizeStatus(getContext());
                } catch (java.io.IOException e) {
                    AppLog.w("optimize status: " + e.getMessage());
                }
                long[] read = status;
                handler.post(() -> {
                    readingOptimize = false;
                    optimizeRead = true;
                    if (read != null) {
                        optimizeRunning = read[0] == 1;
                        optimizedAt = read[1];
                    }
                    if (optimizeRunning && running) handler.postDelayed(this::readOptimize, 10_000);
                    invalidate();
                });
            }, "free-my-tv-optimize-status").start();
        }

        private List<HomeMode.BackgroundApp> backgroundApps;   // null until read
        private final java.util.Map<String, Boolean> backgroundKeep = new java.util.HashMap<>();   // the person's pick
        private boolean readingBackground;
        private Page backgroundPage;

        /**
         * The optional services this TV has (HomeMode.OPTIONAL_SERVICES), the most memory first, each marked to keep or
         * turn off as the list suggests; OK changes the pick, the hint says what each does. Nothing changes until Apply.
         */
        private void openBackgroundApps() {
            Page p = new Page(Ui.tr("Serviços em segundo plano", "Background services", "Servicios en segundo plano"));
            p.rebuild = () -> {
                p.rows.clear();
                if (backgroundApps == null || backgroundApps.isEmpty()) {
                    p.rows.add(new Row(() -> backgroundApps == null ? Ui.tr("Lendo os serviços…", "Reading the services…", "Leyendo los servicios…")
                            : Ui.tr("Nenhum serviço para desligar.", "No services to turn off.", "Ningún servicio para apagar."),
                            () -> "", () -> {}, null, () -> true));
                } else {
                    Row auto = new Row(() -> Ui.tr("Automático", "Automatic", "Automático"), this::automaticText, this::applyAutomatic, null,
                            () -> automaticPlan()[0].isEmpty() && automaticPlan()[1].isEmpty());
                    p.rows.add(auto);
                    for (HomeMode.BackgroundApp app : backgroundApps) {
                        p.rows.add(new Row(() -> serviceText(app.pkg)[0], () -> backgroundValue(app),
                                () -> backgroundKeep.put(app.pkg, !keeps(app)), dir -> backgroundKeep.put(app.pkg, !keeps(app)), null));
                    }
                    p.rows.add(new Row(() -> Ui.tr("Aplicar", "Apply", "Aplicar"), this::backgroundChanges, this::askApplyBackground, null,
                            () -> backgroundPlan()[0].isEmpty() && backgroundPlan()[1].isEmpty()));
                }
                if (page == p && focus >= p.rows.size()) focus = p.rows.size() - 1;
            };
            // Row 0 is Automatic, then one per service, then Apply.
            p.hint = () -> backgroundApps != null && focus == 0 && !backgroundApps.isEmpty()
                    ? Ui.tr("Desliga o que não faz falta; mantém o essencial.", "Turns off what isn't missed; keeps what matters.", "Apaga lo que no hace falta; mantiene lo esencial.")
                    : backgroundApps == null || focus < 1 || focus > backgroundApps.size()
                    ? Ui.tr("Nada é desinstalado: o modo Original liga todos de volta.", "Nothing is uninstalled: Original turns them all back on.",
                            "Nada se desinstala: el modo Original las vuelve a encender.")
                    : serviceText(backgroundApps.get(focus - 1).pkg)[1];
            p.hint2 = () -> Ui.tr("OK escolhe manter ou desligar; nada muda antes de Aplicar.",
                    "OK picks keep or turn off; nothing changes before Apply.", "OK elige mantener o apagar; nada cambia antes de Aplicar.");
            p.rebuild.run();
            backgroundPage = p;
            open(p);
            readBackgroundApps();
        }

        /** {name, what it does and what turning it off costs}, short enough for the hint's line. */
        private static String[] serviceText(String pkg) {
            switch (pkg) {
                case "com.google.android.katniss": return new String[] {Ui.tr("Google (busca e Assistente)", "Google (search and Assistant)", "Google (búsqueda y Asistente)"),
                        Ui.tr("Desligado, o microfone do controle e a busca param.", "Off, the remote's mic and search stop working.", "Apagado, el micrófono del control y la búsqueda no funcionan.")};
                case "com.google.android.speech.pumpkin": return new String[] {Ui.tr("Voz sem internet", "Offline voice", "Voz sin internet"),
                        Ui.tr("Comandos de voz sem internet, para o Assistente.", "Voice commands without the internet, for the Assistant.", "Comandos de voz sin internet, para el Asistente.")};
                case "com.google.android.backdrop": return new String[] {Ui.tr("Modo ambiente", "Ambient mode", "Modo ambiente"),
                        Ui.tr("A tela de descanso de fotos do Google.", "Google's photo screensaver.", "El protector de fotos de Google.")};
                case "com.google.android.tts": return new String[] {Ui.tr("Leitura em voz alta", "Text-to-speech", "Lectura en voz alta"),
                        Ui.tr("Lê textos em voz alta; o TalkBack usa.", "Reads text aloud; TalkBack uses it.", "Lee textos en voz alta; TalkBack lo usa.")};
                case "com.google.android.marvin.talkback": return new String[] {"TalkBack",
                        Ui.tr("O leitor de tela, para quem não enxerga bem.", "The screen reader, for people who don't see well.", "El lector de pantalla, para quien no ve bien.")};
                case "com.google.android.videos": return new String[] {Ui.tr("Google TV (filmes)", "Google TV (movies)", "Google TV (películas)"),
                        Ui.tr("Comprar e alugar filmes e séries do Google.", "Buying and renting movies and shows from Google.", "Comprar y alquilar películas y series de Google.")};
                case "com.android.gallery3d": return new String[] {Ui.tr("Galeria", "Gallery", "Galería"),
                        Ui.tr("Fotos de um pendrive. A TV tem seu próprio leitor.", "Photos from a USB drive. The TV has its own viewer.", "Fotos de un pendrive. La TV tiene su propio visor.")};
                case "com.google.android.music": return new String[] {"Google Play Music",
                        Ui.tr("Serviço encerrado pelo Google.", "A service Google closed.", "Servicio cerrado por Google.")};
                case "com.google.android.youtube.tvmusic": return new String[] {"YouTube Music",
                        Ui.tr("Música do YouTube, num app à parte.", "Music from YouTube, in an app of its own.", "Música de YouTube, en una app aparte.")};
                case "com.google.android.play.games": return new String[] {"Play Games",
                        Ui.tr("Conquistas e perfis de jogos do Google.", "Game achievements and profiles from Google.", "Logros y perfiles de juegos de Google.")};
                case "com.google.android.tvrecommendations":
                case "com.google.android.leanbacklauncher.recommendations": return new String[] {Ui.tr("Recomendações do Android TV", "Android TV recommendations", "Recomendaciones de Android TV"),
                        Ui.tr("Sugestões para a tela inicial original da TV.", "Suggestions for the original home screen of the TV.", "Sugerencias para la pantalla de inicio original.")};
                case "com.google.android.feedback": return new String[] {Ui.tr("Envio de feedback", "Feedback", "Envío de comentarios"),
                        Ui.tr("Manda opiniões e travamentos ao Google.", "Sends opinions and crashes to Google.", "Envía opiniones y fallos a Google.")};
                case "com.google.android.tv.bugreportsender": return new String[] {Ui.tr("Relatórios de erro", "Bug reports", "Informes de errores"),
                        Ui.tr("Manda relatórios de erro ao Google.", "Sends bug reports to Google.", "Envía informes de errores a Google.")};
                case "com.android.printspooler": return new String[] {Ui.tr("Impressão", "Printing", "Impresión"),
                        Ui.tr("Imprimir pela TV.", "Printing from the TV.", "Imprimir desde la TV.")};
                case "com.google.android.syncadapters.calendar": return new String[] {Ui.tr("Sincronizar agenda", "Calendar sync", "Sincronizar calendario"),
                        Ui.tr("Copia a agenda do Google para a TV.", "Copies the Google calendar to the TV.", "Copia el calendario de Google a la TV.")};
                case "com.google.android.syncadapters.contacts": return new String[] {Ui.tr("Sincronizar contatos", "Contacts sync", "Sincronizar contactos"),
                        Ui.tr("Copia os contatos do Google para a TV.", "Copies the Google contacts to the TV.", "Copia los contactos de Google a la TV.")};
                default: return new String[] {pkg, ""};
            }
        }

        private boolean keeps(HomeMode.BackgroundApp app) {
            Boolean keep = backgroundKeep.get(app.pkg);
            return keep != null ? keep : app.enabled && app.keep;   // the ones already off stay off
        }

        private String backgroundValue(HomeMode.BackgroundApp app) {
            String mb = app.megabytes > 0 ? app.megabytes + " MB · " : "";
            if (!app.enabled) return mb + (keeps(app) ? Ui.tr("Ligar", "Turn on", "Encender") : Ui.tr("Desligado", "Off"));
            return mb + (keeps(app) ? Ui.tr("Manter", "Keep", "Mantener") : Ui.tr("Desligar", "Turn off", "Apagar"));
        }

        /** {to turn off, to turn on}: what the picks change. */
        private List<String>[] backgroundPlan() {
            return plan(this::keeps, true);
        }

        /** Automatic: off, every service the list doesn't keep; nothing turned on (one off already stays off). */
        private List<String>[] automaticPlan() {
            return plan(app -> app.keep, false);
        }

        private List<String>[] plan(java.util.function.Predicate<HomeMode.BackgroundApp> keep, boolean turnOn) {
            @SuppressWarnings("unchecked") List<String>[] plan = new List[] {new ArrayList<String>(), new ArrayList<String>()};
            if (backgroundApps == null) return plan;
            for (HomeMode.BackgroundApp app : backgroundApps) {
                if (app.enabled && !keep.test(app)) plan[0].add(app.pkg);
                if (turnOn && !app.enabled && keep.test(app)) plan[1].add(app.pkg);
            }
            return plan;
        }

        private String automaticText() {
            int n = automaticPlan()[0].size();
            return n == 0 ? Ui.tr("Já está leve", "Already light", "Ya está liviana")
                    : Ui.plural(Ui.tr("plural|one:Desliga {n}|other:Desliga {n}", "plural|one:Turns off {n}|other:Turns off {n}",
                    "plural|one:Apaga {n}|other:Apaga {n}"), n);
        }

        private void applyAutomatic() {
            backgroundKeep.clear();   // the picks go back to the list's
            confirmBackground(automaticPlan());
        }

        private String backgroundChanges() {
            List<String>[] plan = backgroundPlan();
            int n = plan[0].size() + plan[1].size();
            return n == 0 ? Ui.tr("Nada a mudar", "Nothing to change", "Nada que cambiar")
                    : Ui.plural(Ui.tr("plural|one:{n} mudança|other:{n} mudanças", "plural|one:{n} change|other:{n} changes",
                    "plural|one:{n} cambio|other:{n} cambios"), n);
        }

        private void askApplyBackground() {
            confirmBackground(backgroundPlan());
        }

        private void confirmBackground(List<String>[] plan) {
            if (plan[0].isEmpty() && plan[1].isEmpty()) return;
            String question = Ui.trf(Ui.tr("Desligar {off} e ligar {on}?", "Turn off {off} and turn on {on}?", "¿Apagar {off} y encender {on}?"),
                    "off", plan[0].size(), "on", plan[1].size());
            openPicker(question, list(Ui.tr("Aplicar", "Apply", "Aplicar"), Ui.tr("Cancelar", "Cancel")), 1, at -> {
                if (at != 0) return;
                HomeMode.requestBackgroundApps(getContext(), plan[0], plan[1]);
                backgroundApps = null;   // read again once the job is through (each app is checked first)
                backgroundKeep.clear();
                backgroundPage.rebuild.run();
                handler.postDelayed(this::readBackgroundApps, 6000 + 2500L * (plan[0].size() + plan[1].size()));
            });
        }

        private void readBackgroundApps() {
            if (readingBackground) return;
            readingBackground = true;
            new Thread(() -> {
                List<HomeMode.BackgroundApp> list = new ArrayList<>();
                try {
                    list = HomeMode.backgroundApps(getContext());
                } catch (java.io.IOException e) {
                    AppLog.w("background apps: " + e.getMessage());
                }
                List<HomeMode.BackgroundApp> read = list;
                handler.post(() -> {
                    readingBackground = false;
                    backgroundApps = read;
                    if (backgroundPage != null) backgroundPage.rebuild.run();
                    invalidate();
                });
            }, "free-my-tv-background-apps").start();
        }

        /** The picks: which list, how big the top is, where they open, the TMDB key, and Continue Watching's apps. */
        private Page picksPage() {
            Page p = new Page(Ui.tr("Recomendações", "Picks"));
            p.rows.add(choice(() -> Ui.tr("Lista", "List", "Lista"),
                    () -> list(Ui.tr("Original (5 por hora)", "Original (5 an hour)"), Ui.tr("Top do dia", "Today's top")),
                    () -> Prefs.topRecs() ? 1 : 0, at -> Prefs.setTopRecs(at == 1)));
            Row top = choice(() -> Ui.tr("Títulos no Top", "Titles in the top"), () -> {
                List<String> l = new ArrayList<>();
                for (int n = Prefs.TOP_MIN; n <= Prefs.TOP_MAX; n++) l.add(String.valueOf(n));
                return l;
            }, () -> Prefs.topCount() - Prefs.TOP_MIN, at -> Prefs.setTopCount(Prefs.TOP_MIN + at));
            p.rows.add(new Row(top.label, top.value, () -> { if (Prefs.topRecs()) top.ok.run(); },   // only the top has a size
                    dir -> { if (Prefs.topRecs()) top.adjust.accept(dir); }, () -> !Prefs.topRecs()));
            p.rows.add(choice(() -> Ui.tr("Abrir recomendações em", "Open picks in"), SettingsView::targetNames,
                    SettingsView::targetIndex, at -> Prefs.setRecsTarget(targetKeys()[at])));
            p.rows.add(new Row(() -> Ui.tr("Chave do TMDB", "TMDB key"), this::tmdbKeyState, activity::askTmdbKey, null, null));
            Row watch = new Row(() -> Ui.tr("Continuar assistindo", "Continue Watching", "Seguir viendo"),
                    SettingsView::watchAppsState, this::openWatchApps, null, null);
            p.rows.add(watch);
            p.hint = () -> Ui.tr("OK abre as opções; ← e → mudam direto.", "OK opens the choices; ← and → change it right away.");
            p.hint2 = () -> Ui.tr("Usa a API do TMDB, sem endosso ou certificação do TMDB.",
                    "This product uses the TMDB API but is not endorsed or certified by TMDB.");
            return p;
        }

        /** The weather (on/off, the city) and the prices (on/off, how often, which coins). */
        private Page weatherPage() {
            Page p = new Page(Ui.tr("Clima e cotações", "Weather and prices", "Clima y cotizaciones"));
            p.rows.add(onOff(() -> Ui.tr("Widget do clima", "Weather widget"), Prefs::weather, Prefs::setWeather, null));
            p.rows.add(new Row(() -> Ui.tr("Cidade do clima", "Weather city"), () -> cityStatus.isEmpty() ? Prefs.city() : cityStatus,
                    this::askCity, null, null));
            p.rows.add(onOff(() -> Ui.tr("Widget de cotações", "Quotes widget"), Prefs::quotes, Prefs::setQuotes, null));
            p.rows.add(choice(() -> Ui.tr("Atualizar cotações", "Refresh prices"), () -> list(Ui.tr("Tempo real", "Live"), "1 min", "5 min"),
                    Prefs::pricesEveryIndex, Prefs::setPricesEveryIndex));
            String[] coins = dollar() ? new String[] {"Bitcoin", "Ethereum", "Solana", Ui.tr("Dólar", "Dollar"), "Euro"}
                    : new String[] {"Bitcoin", "Ethereum", "Solana"};
            for (int i = 0; i < coins.length; i++) {
                int coin = i;
                String name = coins[i];
                // No quotes widget, nowhere to show prices: the coins read as off (and stay locked) until it is back on.
                p.rows.add(onOff(() -> name, () -> Prefs.quotes() && Prefs.coin(coin), on -> Prefs.setCoin(coin, on),
                        () -> !Prefs.quotes()));
            }
            p.hint = () -> Ui.tr("OK abre as opções; ← e → mudam direto.", "OK opens the choices; ← and → change it right away.");
            return p;
        }

        /** The clocks' time: automatic (the weather city's zone and the time from the internet) or a zone and adjustment by hand. */
        private Page clockPage() {
            Page p = new Page(Ui.tr("Relógio", "Clock", "Reloj"));
            p.rows.add(choice(() -> Ui.tr("Hora", "Time", "Hora"),
                    () -> list(Ui.tr("Automática (internet)", "Automatic (internet)", "Automática (internet)"),
                            Ui.tr("Manual", "Manual", "Manual")),
                    () -> Prefs.manualClock() ? 1 : 0, at -> Prefs.setManualClock(at == 1)));
            java.util.function.BooleanSupplier automatic = () -> !Prefs.manualClock();
            p.rows.add(dimmed(choice(() -> Ui.tr("Fuso horário", "Time zone", "Zona horaria"), SettingsView::zoneNames,
                    () -> automatic.getAsBoolean() ? nearest(Clock.ZONES, Clock.zoneMinutes()) : nearest(Clock.ZONES, Prefs.clockZone()),
                    at -> Prefs.setClockZone(Clock.ZONES[at])), automatic));
            p.rows.add(dimmed(choice(() -> Ui.tr("Ajuste da hora", "Time adjustment", "Ajuste de la hora"), SettingsView::adjustNames,
                    () -> automatic.getAsBoolean() ? nearest(Clock.ADJUSTS, 0) : nearest(Clock.ADJUSTS, Prefs.clockAdjust()),
                    at -> Prefs.setClockAdjust(Clock.ADJUSTS[at])), automatic));
            p.rows.add(new Row(() -> Ui.tr("Agora", "Now", "Ahora"), () -> Ui.clock(System.currentTimeMillis()), () -> {}, null, null));
            p.hint = () -> Prefs.manualClock()
                    ? Ui.tr("Escolha o fuso e, se a hora ainda estiver errada, ajuste com ← e →.",
                            "Pick the zone and, if the time is still off, adjust it with ← and →.",
                            "Elige la zona y, si la hora sigue mal, ajústala con ← y →.")
                    : Ui.tr("Usa o fuso da cidade do clima e a hora certa da internet.",
                            "Uses the weather city's time zone and the exact time from the internet.",
                            "Usa la zona de la ciudad del clima y la hora exacta de internet.");
            return p;
        }

        private static List<String> zoneNames() {
            List<String> l = new ArrayList<>();
            for (int minutes : Clock.ZONES) l.add(Clock.utcLabel(minutes));
            return l;
        }

        private static List<String> adjustNames() {
            List<String> l = new ArrayList<>();
            for (int minutes : Clock.ADJUSTS) l.add(Clock.adjustLabel(minutes));
            return l;
        }

        /** The index of the value in `values` closest to `value` (a zone the list lacks, like UTC+5:30, shows the nearest). */
        private static int nearest(int[] values, int value) {
            int best = 0;
            for (int i = 1; i < values.length; i++) if (Math.abs(values[i] - value) < Math.abs(values[best] - value)) best = i;
            return best;
        }

        /** Which screensaver Free My TV mode keeps, and a look at Free My TV's. */
        private Page screensaverPage() {
            Page p = new Page(Ui.tr("Tela de descanso", "Screensaver"));
            p.rows.add(choice(() -> Ui.tr("Tela de descanso", "Screensaver"),
                    () -> list(Ui.tr("Da TV (fotos do Google)", "The TV's own (Google photos)"), "Free My TV"),
                    () -> Prefs.ownScreensaver() ? 1 : 0, at -> {
                        boolean own = at == 1;
                        if (own == Prefs.ownScreensaver()) return;
                        Prefs.setOwnScreensaver(own);
                        HomeMode.requestScreensaver(getContext(), own);   // the TV's setting changes in the main process
                    }));
            p.rows.add(new Row(() -> Ui.tr("Ver a do Free My TV", "Preview Free My TV's", "Ver la de Free My TV"),
                    () -> Ui.tr("Ver agora", "Preview"), () -> {
                        pause();
                        activity.showPreview();
                    }, null, null));
            return p;
        }

        /**
         * Remote Buttons: "Identify a button" (press any button on the remote, then pick what it opens), then every button
         * that does something now and what. Remotes differ between TVs (the same button can send another code), so the
         * buttons are told apart by what this TV receives, not by a brand's list of names.
         */
        private Page keysPage() {
            Page p = new Page(Ui.tr("Botões do Controle", "Remote Buttons"));
            p.rebuild = () -> {
                p.rows.clear();
                p.rows.add(new Row(() -> learningKeys ? Ui.tr("Aperte o botão no controle…", "Press the button on the remote…",
                        "Pulsa el botón en el control…") : Ui.tr("Identificar um botão", "Identify a button", "Identificar un botón"),
                        () -> "", this::startLearningKeys, null, null));
                for (int key : Prefs.keysInUse()) {
                    // A brand default this remote never sent (another model's code) would only confuse: it shows once seen.
                    if (!Prefs.keyChosen(key) && !onRemote(key)) continue;
                    String label = keyLabel(key);
                    p.rows.add(new Row(() -> label, () -> targetName(key), () -> pickKeyTarget(key), null, null));
                }
                keepVisible(false);
            };
            p.hint = this::keysHint;
            p.hint2 = () -> learningKeys ? Ui.tr("Setas, OK, Voltar, Home, energia e volume não mudam. Voltar cancela.",
                    "Arrows, OK, Back, Home, power and volume don't change. Back cancels.",
                    "Flechas, OK, Atrás, Inicio, encendido y volumen no cambian. Atrás cancela.")
                    : Ui.tr("Em Nada, a TV volta a cuidar do botão.", "On Nothing, the TV handles the button again.",
                    "En Nada, la TV vuelve a encargarse del botón.");
            p.rebuild.run();
            return p;
        }

        private boolean keysServiceOn = true;   // read by the poll: the TV's list of enabled accessibility services
        private String keysError = "";
        private boolean learningKeys;
        private long learningSince;

        /** Waits for the next button (the key service takes it from inside any app; this screen, without the service). */
        private void startLearningKeys() {
            if (learningKeys) return;
            keysError = "";
            Prefs.startLearning();
            learningKeys = true;
            learningSince = System.currentTimeMillis();
            if (!keysServiceOn) HomeMode.ensureRemoteService(getContext(), 0);
            if (page != null && page.rebuild != null) page.rebuild.run();
            invalidate();
        }

        private void stopLearningKeys() {
            if (!learningKeys) return;
            learningKeys = false;
            Prefs.stopLearning();
            if (page != null && page.rebuild != null) page.rebuild.run();
            invalidate();
        }

        /** From the poll and from this screen's keys: a button came, its list of choices opens; a minute without one ends it. */
        private void checkLearned() {
            if (!learningKeys) return;
            int key = Prefs.learnedKey();
            if (key != 0) {
                stopLearningKeys();
                pickKeyTarget(key);
            } else if (System.currentTimeMillis() - learningSince > 60_000) {
                stopLearningKeys();
                keysError = Ui.tr("Nenhum botão foi apertado.", "No button was pressed.", "No se pulsó ningún botón.");
            }
        }

        /** The choices for one button: an app, the Control Center, Recent apps or Nothing. */
        private void pickKeyTarget(int key) {
            openPicker(keyLabel(key), appLabels, appPkgs.indexOf(Prefs.keyApp(key)), at -> setKeyApp(key, appPkgs.get(at)));
        }

        private String targetName(int key) {
            int at = appPkgs.indexOf(Prefs.keyApp(key));
            return at >= 0 ? appLabels.get(at) : Ui.tr("Não instalado", "Not installed");
        }

        /** The brand's name for one of its buttons (one remote per brand), else what this TV calls the key. */
        private String keyLabel(int key) {
            String[] labels = keyLabels();
            for (int i = 0; i < Prefs.KEYS.length && i < labels.length; i++) if (Prefs.KEYS[i] == key) return labels[i];
            return keyName(key);
        }

        /** "INFO button", "PROG RED button", or its number ("4097 button") for the brand's own codes. */
        private static String keyName(int key) {
            String name;
            if (key >= RemoteKeys.UNKNOWN_KEY) name = "scan " + (key - RemoteKeys.UNKNOWN_KEY);
            else if (key >= Prefs.SONY_KEY) name = "Sony " + (key - Prefs.SONY_KEY);
            else {
                String s = KeyEvent.keyCodeToString(key);
                name = s.startsWith("KEYCODE_") ? s.substring(8).replace('_', ' ') : String.valueOf(key);
            }
            return Ui.trf(Ui.tr("Botão {name}", "{name} button", "Botón {name}"), "name", name);
        }

        /** Saves a button's app; if it didn't save, says so; and makes sure the key service is on to use it. */
        private void setKeyApp(int key, String pkg) {
            keysError = Prefs.setKeyApp(key, pkg) ? ""
                    : Ui.tr("Não deu para salvar a escolha. Tente de novo.", "Couldn't save the choice. Try again.",
                    "No se pudo guardar la elección. Inténtalo de nuevo.");
            if (!keysServiceOn) HomeMode.ensureRemoteService(getContext(), 0);
        }

        // Asked once per button (a call to the input service): the rows are read on every frame.
        private final java.util.Map<Integer, Boolean> keyOnDevice = new java.util.HashMap<>();

        /** The remote has this button: the key service saw it, or a connected remote says it has it. */
        private boolean onRemote(int key) {
            if (Prefs.keySeenAt(key) > 0) return true;
            Boolean has = keyOnDevice.get(key);
            if (has == null) {
                // Sony's buttons share key codes and are told apart by scan code: only seeing them counts.
                has = key < Prefs.SONY_KEY && android.view.KeyCharacterMap.deviceHasKey(key);
                keyOnDevice.put(key, has);
            }
            return has;
        }

        /** Waiting for a button, else what's wrong, else the button just pressed and what it opens, else how to start. */
        private String keysHint() {
            if (learningKeys) {
                return Ui.tr("Aperte o botão do controle que você quer configurar.", "Press the button on the remote you want to set.",
                        "Pulsa el botón del control que quieres configurar.");
            }
            if (!keysError.isEmpty()) return keysError;
            // The service runs only in Free My TV mode (Original turns it off on purpose).
            if (!keysServiceOn && RemoteKeys.supported() && HomeMode.FREE.equals(state.optString("mode"))) {
                return Ui.tr("O serviço dos botões está desligado. Ligando…", "The buttons' service is off. Turning it on…",
                        "El servicio de los botones está apagado. Encendiéndolo…");
            }
            long now = System.currentTimeMillis();
            for (int key : Prefs.keysInUse()) {
                if (now - Prefs.keySeenAt(key) > 15_000) continue;
                return Ui.trf("{button} works: it opens {app}.", "button", keyLabel(key), "app", targetName(key));
            }
            return Ui.tr("Identificar um botão: aperte qualquer botão do controle e escolha o que ele abre.",
                    "Identify a button: press any button on the remote and pick what it opens.",
                    "Identificar un botón: pulsa cualquier botón del control y elige qué abre.");
        }

        /** A row that opens a page (a chevron instead of a value). */
        private static Row opens(java.util.function.Supplier<String> label, Runnable open) {
            Row r = new Row(label, () -> "", open, null, null);
            r.opens = true;
            return r;
        }

        /**
         * A setting with a list of choices: its value is the choice in use, OK opens the list, ← → step through it.
         * `choices` is read each time (the language's names, the apps), `current` is the index in use.
         */
        private Row choice(java.util.function.Supplier<String> label, java.util.function.Supplier<List<String>> choices,
                           java.util.function.IntSupplier current, java.util.function.IntConsumer set) {
            return new Row(label, () -> {
                List<String> l = choices.get();
                int at = current.getAsInt();
                return at >= 0 && at < l.size() ? l.get(at) : "";
            }, () -> openPicker(label.get(), choices.get(), current.getAsInt(), set), dir -> {
                int n = choices.get().size();
                set.accept((Math.max(0, current.getAsInt()) + dir + n) % n);
            }, null);
        }

        /** On / Off, with ← → or OK (a list of the two). `dim`: greyed out and locked. */
        private Row onOff(java.util.function.Supplier<String> label, java.util.function.BooleanSupplier on,
                          java.util.function.Consumer<Boolean> set, java.util.function.BooleanSupplier dim) {
            Row r = choice(label, () -> list(Ui.tr("Ligado", "On"), Ui.tr("Desligado", "Off")), () -> on.getAsBoolean() ? 0 : 1,
                    at -> set.accept(at == 0));
            return dim == null ? r : dimmed(r, dim);
        }

        /** The row greyed out and locked while `dim` says so. */
        private static Row dimmed(Row r, java.util.function.BooleanSupplier dim) {
            return new Row(r.label, r.value, () -> { if (!dim.getAsBoolean()) r.ok.run(); },
                    d -> { if (!dim.getAsBoolean()) r.adjust.accept(d); }, dim);
        }

        private static List<String> list(String... items) {
            List<String> l = new ArrayList<>();
            Collections.addAll(l, items);
            return l;
        }

        // MARK: - Moving between pages

        private void open(Page p) {
            back.push(new Place(screen, page, focus, scroll.target));
            showPage(p, 0);
        }

        private void showPage(Page p, int at) {
            screen = PAGE;
            page = p;
            focus = Math.max(0, Math.min(at, p.rows.size() - 1));
            scroll.snap(0);
            keepVisible(true);
            snapPops();
        }

        private void go(int s, Page p) {
            back.push(new Place(screen, page, focus, scroll.target));
            screen = s;
            page = p;
            focus = 0;
            snapPops();
        }

        /** Back: the page before, with its focus where it was; false on the main menu (Back leaves the settings). */
        private boolean goBack() {
            if (back.isEmpty()) return false;
            Place to = back.pop();
            screen = to.screen;
            page = to.page;
            focus = to.focus;
            scroll.snap(to.scroll);
            if (page != null && page.rebuild != null) page.rebuild.run();
            keepVisible(true);
            snapPops();
            return true;
        }

        private void openHomeChoice() {
            if (busy()) return;
            go(HOME_CHOICE, null);
            focus = freeActive() ? 0 : 1;
        }

        /**
         * The list of a setting's choices: opens on the one in use; OK picks and goes back to the setting, Back leaves
         * it as it was.
         */
        private void openPicker(String title, List<String> labels, int current, java.util.function.IntConsumer apply) {
            Page p = new Page(title);
            for (int i = 0; i < labels.size(); i++) {
                int at = i;
                String label = labels.get(i);
                Row r = new Row(() -> label, () -> "", () -> {
                    apply.accept(at);
                    changed();
                    goBack();
                }, null, null);
                r.check = i == current;
                p.rows.add(r);
            }
            p.hint = () -> (focus + 1) + " / " + p.rows.size() + "  ·  " + Ui.tr("OK escolhe, Voltar cancela.", "OK picks, Back cancels.");
            back.push(new Place(screen, page, focus, scroll.target));
            showPage(p, Math.max(0, current));
        }

        /** A setting changed: the theme may have too, and the data on screen reads the new settings. */
        private void changed() {
            retheme();
            HomeData.get(getContext()).settingsChanged();
            invalidate();
        }

        // MARK: - Scrolling

        private int rowCount() {
            return screen == PAGE && page != null ? page.rows.size() : screen == HOME_CHOICE ? 2 : 0;
        }

        private float listTop() {
            return page == main ? LIST_TOP : LIST_TOP + 20;
        }

        /** How many rows fit between the top of the list and the hints below it. */
        private int visibleRows() {
            if (screen != PAGE) return rowCount();
            float bottom = page == main ? LIST_BOTTOM - 40 : LIST_BOTTOM;   // the main menu keeps room for the switch's messages
            return Math.max(1, (int) ((bottom - listTop() + ROW_GAP) / STEP));
        }

        private int firstShown() {
            return screen == PAGE ? Math.max(0, (int) Math.floor(scroll.value)) : 0;
        }

        /** Scrolls so the focused row is on screen, with one row of the next ones in sight when there are more. */
        private void keepVisible(boolean snap) {
            int n = rowCount(), vis = visibleRows();
            float target = scroll.target;
            if (n <= vis) target = 0;
            else if (focus < target + 1 && focus > 0) target = Math.max(0, focus - 1);
            else if (focus == 0) target = 0;
            else if (focus > target + vis - 2) target = Math.min(n - vis, focus - vis + 2);
            target = Math.max(0, Math.min(Math.max(0, n - vis), target));
            scroll.target = target;
            if (snap) scroll.snap(target);
        }

        private Spring pop(int i) {
            while (pop.size() <= i) pop.add(Spring.focus(0));
            return pop.get(i);
        }

        private void snapPops() {
            for (Spring s : pop) s.snap(0);
            pop(focus).snap(1);
        }

        // MARK: - TalkBack

        /** A row as TalkBack reads it: its name and its value, as on screen. */
        private String rowText(int row) {
            if (screen == HOME_CHOICE) {
                String name = row == 0 ? "Free My TV, " + Ui.tr("sem anúncios, com a tela de descanso da TV", "no ads, with the TV's own screensaver")
                        : "Original, " + Ui.tr("o Início de fábrica da TV", "the TV's factory home screen");
                boolean inUse = (row == 0) == freeActive();
                return name + (inUse ? Ui.tr(". Em uso", ". In use") : "") + ", " + (row + 1) + Ui.tr(" de 2", " of 2");
            }
            if (page == null || row >= page.rows.size()) return "";
            Row r = page.rows.get(row);
            String value = page == main && row == 0 && busy() ? busyLine() : r.value.get();
            return r.label.get() + (value.isEmpty() ? "" : ", " + value) + (r.check ? Ui.tr(", escolhido", ", selected") : "")
                    + ", " + (row + 1) + Ui.tr(" de ", " of ") + page.rows.size();
        }

        private RectF rowBox(int row) {
            if (screen == HOME_CHOICE) {
                float top = LIST_TOP + 20 + row * (CHOICE_H + ROW_GAP);
                focusBox.set(LIST_X * k, top * k, LIST_R * k, (top + CHOICE_H) * k);
                return focusBox;
            }
            float top = listTop() + (row - scroll.value) * STEP;
            focusBox.set(LIST_X * k, top * k, LIST_R * k, (top + ROW_H) * k);
            float lo = listTop() * k, hi = (listTop() + visibleRows() * STEP) * k;
            if (focusBox.top < lo || focusBox.bottom > hi) focusBox.setEmpty();   // scrolled away
            return focusBox;
        }

        // MARK: - Life cycle

        void resume() {
            click.start();
            if (running) return;
            running = true;
            startedAt = SystemClock.uptimeMillis();
            lastNanos = 0;
            retheme();
            handler.post(poll);
            askFrame();
            spoken.readAgain();
        }

        void release() {
            click.release();
            pollIo.shutdown();
        }

        void pause() {
            stopLearningKeys();   // the key service must not keep taking buttons after this screen is gone
            click.stop();
            running = false;
            handler.removeCallbacks(poll);
            Choreographer.getInstance().removeFrameCallback(this);
            framePosted = false;
        }

        /**
         * Redraws while something moves, then stops asking for frames: onDraw asks again, so anything that redraws
         * (a key, the poll seeing a change) starts it again.
         */
        @Override public void doFrame(long frameTimeNanos) {
            framePosted = false;
            if (!running) return;
            boolean moving = busy() || !scroll.settled();
            for (Spring s : pop) moving |= !s.settled();
            if (moving) invalidate();
        }

        private void askFrame() {
            if (framePosted || !running) return;
            framePosted = true;
            Choreographer.getInstance().postFrameCallback(this);
        }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            w = width;
            h = height;
            k = w / 1920f;
            lightTheme = lightTheme();
            backdrop.setShader(new android.graphics.LinearGradient(0, 0, 0, h, lightTheme ? 0xFFF5F5F7 : 0xFF1C1C1E,
                    lightTheme ? 0xFFE2E2E7 : 0xFF0B0B0D, android.graphics.Shader.TileMode.CLAMP));
            shade.setShader(new android.graphics.RadialGradient(w * 0.5f, h * 0.42f, Math.max(w, h) * 0.75f, 0x00000000,
                    lightTheme ? 0x0F000000 : 0x33000000, android.graphics.Shader.TileMode.CLAMP));
            title = Ui.text(Ui.displaySemibold, 50 * k, fg(0xFFFFFFFF));
            title.setTextAlign(Paint.Align.CENTER);
            subtitle = Ui.text(Ui.regular, 26 * k, fg(0x99FFFFFF));
            subtitle.setTextAlign(Paint.Align.CENTER);
            version = Ui.text(Ui.regular, 22 * k, secondary(0x99FFFFFF));
            version.setTextAlign(Paint.Align.CENTER);
            rowLabel = Ui.text(Ui.medium, 30 * k, fg(0xF2FFFFFF));
            rowValue = Ui.text(Ui.regular, 28 * k, secondary(0x8CFFFFFF));
            rowValue.setTextAlign(Paint.Align.RIGHT);
            rowSub = Ui.text(Ui.regular, 23 * k, secondary(0x8CFFFFFF));
            header = Ui.text(Ui.semibold, 21 * k, secondary(0x99FFFFFF));
            header.setLetterSpacing(0.06f);
            cellLabel = Ui.text(Ui.regular, 25 * k, fg(0xD9FFFFFF));
            cellValue = Ui.numbers(Ui.regular, 25 * k, fg(0xD9FFFFFF));
            cellValue.setTextAlign(Paint.Align.RIGHT);
            warn = Ui.text(Ui.regular, 23 * k, lightTheme ? 0xFFB35C00 : AMBER);
            notice = new TextPaint(Ui.text(Ui.regular, 23 * k, fg(0xB3FFFFFF)));
            heading = Ui.text(Ui.displaySemibold, 34 * k, fg(0xFFFFFFFF));
            hint = Ui.text(Ui.regular, 23 * k, secondary(0x8CFFFFFF));
            hintLayouts.clear();   // made with the old paint (theme or size)
            spinner = new Paint(Paint.ANTI_ALIAS_FLAG);
            spinner.setStyle(Paint.Style.STROKE);
            spinner.setStrokeCap(Paint.Cap.ROUND);
            spinner.setStrokeWidth(3.5f * k);
            white = new Paint(Paint.ANTI_ALIAS_FLAG);
            white.setColor(lightTheme ? 0xFFFFFFFF : 0xFFF5F5F7);   // the focus pill: pure white over the light background
            shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
            shadow.setMaskFilter(new BlurMaskFilter(22 * k, BlurMaskFilter.Blur.NORMAL));
        }

        private TextPaint fitted(Paint p) {
            fit.set(p);
            return fit;
        }

        /** The background and the text follow the theme (it can change here, or turn to night while open). */
        private void retheme() {
            if (w > 0 && lightTheme() != lightTheme) onSizeChanged(w, h, w, h);
        }

        /** Light like the home screen by day (theme: auto, 6:00 to 18:00), or as Appearance > Theme says. */
        private boolean lightTheme() {
            String theme = Prefs.theme();
            if (!theme.equals("auto")) return theme.equals("light");
            return !HomeData.get(getContext()).isNight(System.currentTimeMillis());
        }

        /** Text and symbols: white on the dark background; on the light one, the same alpha in near-black. */
        private int fg(int white) {
            return lightTheme ? (white & 0xFF000000) | (INK & 0x00FFFFFF) : white;
        }

        /**
         * Small secondary text (values, hints, headers): `white` on the dark background; on the light one the home
         * screen's gray (Apple's #6E6E73, ~4.7:1), since near-black at a low alpha read under 4.5:1 there.
         */
        private int secondary(int white) {
            return lightTheme ? 0xFF6E6E73 : white;
        }

        /** The last switch's error: the TV's own debugging (ADB on port 5555) is off. */
        private boolean debuggingOff() {
            return !busy() && state.optString("error").contains("5555");
        }

        private boolean busy() {
            return !HomeMode.busyText(state).isEmpty() || (pendingAt != 0 && SystemClock.uptimeMillis() - pendingAt < 10_000);
        }

        private String busyLine() {
            String text = HomeMode.busyText(state);
            return text.isEmpty() ? Ui.tr("Começando…", "Starting…") : text;
        }

        private boolean freeActive() {
            return HomeMode.FREE.equals(state.optString("mode")) && !state.optBoolean("homeReverted");
        }

        // MARK: - Drawing

        @Override protected void onDraw(Canvas c) {
            if (w == 0) return;
            askFrame();
            long now = System.nanoTime();
            float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.05f, (now - lastNanos) / 1e9f);
            lastNanos = now;
            int n = rowCount();
            for (int i = 0; i < Math.max(n, pop.size()); i++) {
                Spring s = pop(i);
                s.target = i == focus && i < n ? 1 : 0;
                s.step(dt);
            }
            scroll.step(dt);
            float seconds = (SystemClock.uptimeMillis() - startedAt) / 1000f;
            c.drawRect(0, 0, w, h, backdrop);
            c.drawRect(0, 0, w, h, shade);

            // On the left: big symbol, title, description.
            float cx = 500 * k;
            Symbols.draw(getContext(), c, "sparkles", cx, 400 * k, 150 * k, fg(0xEBFFFFFF));
            c.drawText("Free My TV", cx, 612 * k, title);
            c.drawText(Ui.tr("Início e tela de descanso da TV", "Your TV's home screen and screensaver"), cx, 660 * k, subtitle);
            c.drawText(versionText(), cx, 704 * k, version);

            if (screen == HOME_CHOICE) drawChoice(c);
            else if (screen == STATUS) drawStatus(c);
            else drawPage(c, seconds);
        }

        /** The rows of the page (scrolled), arrows when there are more above or below, then its hint. */
        private void drawPage(Canvas c, float seconds) {
            float x = LIST_X * k + 30 * k;
            if (page.title != null) c.drawText(page.title, x, (LIST_TOP - 24) * k, heading);
            float top = listTop(), vis = visibleRows();
            float listBottom = top + vis * STEP;
            drawListPanel(c, top, Math.min(listBottom, top + page.rows.size() * STEP));
            c.save();
            // Rows fade out under the edges instead of being cut: drawn in a band a little taller than the list.
            c.clipRect(LIST_X * k - 40 * k, (top - 14) * k, LIST_R * k + 40 * k, (listBottom + 10) * k);
            int first = Math.max(0, (int) Math.floor(scroll.value) - 1);
            int last = Math.min(page.rows.size(), (int) Math.ceil(scroll.value + vis) + 1);
            for (int i = first; i < last; i++) {
                float y = top + (i - scroll.value) * STEP;
                // How much of the row is inside the list: rows passing an edge fade.
                float in = Math.min(Ui.clamp((y - top + ROW_H) / ROW_H), Ui.clamp((listBottom - y) / ROW_H));
                if (in <= 0.02f) continue;
                drawRow(c, i, y, in, seconds);
            }
            c.restore();
            // More above or below: a small chevron at that edge.
            int fg = fg(0x99FFFFFF);
            if (scroll.value > 0.05f) {
                c.save();
                c.rotate(180, (LIST_R - 40) * k, (top - 30) * k);
                Symbols.draw(getContext(), c, "chevron_down", (LIST_R - 40) * k, (top - 30) * k, 22 * k, fg);
                c.restore();
            }
            if (scroll.value + vis < page.rows.size() - 0.05f) {
                Symbols.draw(getContext(), c, "chevron_down", (LIST_R - 40) * k, (listBottom + 14) * k, 22 * k, fg);
            }
            float hy = (Math.min(listBottom, top + page.rows.size() * STEP) + 36) * k;
            if (page == main) {
                drawSwitchMessage(c, hy - 16 * k);
                return;
            }
            String hint1 = page.hint.get(), hint2 = page.hint2.get();
            if (!hint1.isEmpty()) hy = drawHint(c, hint1, x, hy) + 38 * k;
            else hy += 38 * k;
            if (!hint2.isEmpty()) drawHint(c, hint2, x, hy);
        }

        /**
         * The rows sit on one panel, like the home screen's Control Center (radius 30): a faint tint with a hairline
         * edge, so the white focus pill stands out on it in either theme. `top`/`bottom` in 1080p units.
         */
        private void drawListPanel(Canvas c, float top, float bottom) {
            panelRect.set((LIST_X - 22) * k, (top - 16) * k, (LIST_R + 22) * k, (bottom + 10) * k);
            float r = PANEL_RADIUS * k;
            panelPaint.setStyle(Paint.Style.FILL);
            panelPaint.setColor(lightTheme ? 0x0F000000 : 0x12FFFFFF);
            c.drawRoundRect(panelRect, r, r, panelPaint);
            panelPaint.setStyle(Paint.Style.STROKE);
            panelPaint.setStrokeWidth(Math.max(1, k));
            panelPaint.setColor(lightTheme ? 0x14000000 : 0x1FFFFFFF);
            c.drawRoundRect(panelRect, r, r, panelPaint);
        }

        private static final float PANEL_RADIUS = 30;   // ControlCenter.TILE_RADIUS
        private final Paint panelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF panelRect = new RectF();

        /** The hints, wrapped (2 lines at most) to the list's width: longer translations no longer run off the screen. */
        private final java.util.HashMap<String, StaticLayout> hintLayouts = new java.util.HashMap<>();

        /** Draws a hint with its first baseline at y; returns the last line's baseline. */
        private float drawHint(Canvas c, String text, float x, float y) {
            int width = Math.max(1, Math.round(LIST_R * k - x));
            StaticLayout layout = hintLayouts.get(text);
            if (layout == null || layout.getWidth() != width) {
                if (hintLayouts.size() > 24) hintLayouts.clear();
                layout = StaticLayout.Builder.obtain(text, 0, text.length(), new TextPaint(hint), width)
                        .setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).build();
                hintLayouts.put(text, layout);
            }
            float top = y - layout.getLineBaseline(0);
            c.save();
            c.translate(x, top);
            layout.draw(c);
            c.restore();
            return top + layout.getLineBaseline(layout.getLineCount() - 1);
        }

        /** One row: the focus pill, the name (cut if it would meet the value), the value, a chevron or a check. */
        private void drawRow(Canvas c, int i, float y, float alpha, float seconds) {
            Row r = page.rows.get(i);
            rowRect.set(LIST_X * k, y * k, LIST_R * k, (y + ROW_H) * k);
            float f = drawRowBackground(c, i);
            boolean dim = r.dim != null && r.dim.getAsBoolean();
            int ink = blend(dim ? fg(0x73FFFFFF) : fg(0xF2FFFFFF), dim ? 0x731C1C1E : INK, f);
            rowLabel.setColor(Ui.alpha(ink, alpha * ((ink >>> 24) / 255f)));
            float base = rowRect.centerY() + Ui.halfCap(rowLabel), right = rowRect.right - 30 * k, left = rowRect.left + 30 * k;
            float valueRoom = 0;
            if (page == main && i == 0 && busy()) {
                // Switching: the current step in place of the value, with the spinning arc.
                rowValue.setColor(Ui.alpha(blend(fg(0xB3FFFFFF), 0xB31C1C1E, f), alpha));
                String shown = TextUtils.ellipsize(busyLine(), fitted(rowValue), 470 * k, TextUtils.TruncateAt.END).toString();
                c.drawText(shown, right, base, rowValue);
                float sx = right - rowValue.measureText(shown) - 26 * k;
                spinner.setColor(Ui.alpha(blend(fg(0xFFFFFFFF), INK, f), alpha));
                Ui.spinner(c, sx, rowRect.centerY(), 12 * k, seconds * 1.3f, spinner);
                valueRoom = rowValue.measureText(shown) + 60 * k;
            } else if (r.opens) {
                float cw = Symbols.width(getContext(), "chevron_right", 22 * k);
                Symbols.draw(getContext(), c, "chevron_right", right - cw / 2, rowRect.centerY(), 22 * k,
                        Ui.alpha(blend(fg(0x66FFFFFF), 0x991C1C1E, f), alpha));
                valueRoom = cw + 20 * k;
            } else if (r.check) {
                float cw = Symbols.width(getContext(), "check", 26 * k);
                Symbols.draw(getContext(), c, "check", right - cw / 2, rowRect.centerY(), 26 * k,
                        Ui.alpha(blend(fg(0xFFFFFFFF), INK, f), alpha));
                valueRoom = cw + 20 * k;
            } else {
                String value = r.value.get();
                if (!value.isEmpty()) {
                    rowValue.setColor(Ui.alpha(blend(dim ? fg(0x59FFFFFF) : fg(0xB3FFFFFF), dim ? 0x591C1C1E : 0xB31C1C1E, f), alpha));
                    float maxValue = rowRect.width() * 0.45f;
                    String shown = rowValue.measureText(value) <= maxValue ? value
                            : TextUtils.ellipsize(value, fitted(rowValue), maxValue, TextUtils.TruncateAt.END).toString();
                    c.drawText(shown, right, base, rowValue);
                    valueRoom = rowValue.measureText(shown) + 30 * k;
                }
            }
            String label = r.label.get();
            float room = right - valueRoom - left;
            if (rowLabel.measureText(label) > room) label = TextUtils.ellipsize(label, fitted(rowLabel), room, TextUtils.TruncateAt.END).toString();
            c.drawText(label, left, base, rowLabel);
        }

        /** List row: no background; focused, white with a shadow (the Apple TV focus). Returns how focused it is. */
        private float drawRowBackground(Canvas c, int i) {
            float f = Ui.clamp(pop(i).value);
            if (f > 0.01f) {
                float s = 1 + 0.03f * f;
                float cx = rowRect.centerX(), cy = rowRect.centerY();
                tmp.set(cx - rowRect.width() * s / 2, cy - rowRect.height() * s / 2, cx + rowRect.width() * s / 2, cy + rowRect.height() * s / 2);
                float r = 22 * k * s;   // the home screen's menu rows (MENU_ROW_RADIUS)
                tmp.offset(0, 10 * k * f);
                shadow.setColor(Ui.alpha(0xFF000000, (lightTheme ? 0.16f : 0.4f) * f));
                c.drawRoundRect(tmp, r, r, shadow);
                tmp.offset(0, -10 * k * f);
                white.setAlpha(Math.round(255 * f));
                c.drawRoundRect(tmp, r, r, white);
                rowRect.set(tmp);
            }
            return f;
        }

        /** Under the main menu: what the TV asks for during a switch, or why the last one failed. */
        private void drawSwitchMessage(Canvas c, float y) {
            String error = state.optString("error");
            boolean working = busy();
            String busyText = HomeMode.busyText(state);
            if (working && (busyText.contains("Permitir") || busyText.contains("Allow"))) {
                c.drawText(Ui.tr("Na TV: marque “Sempre permitir” e aperte OK.", "On the TV: tick “Always allow” and press OK."),
                        LIST_X * k + 30 * k, y, warn);
            } else if (!working && !error.isEmpty()) {
                // The whole message, on up to four lines: some say what to do (the Wi-Fi one, #14).
                int width = Math.round(760 * k);
                if (errorLayout == null || !error.equals(errorShown) || errorLayout.getWidth() != width) {
                    errorShown = error;
                    errorLayout = StaticLayout.Builder.obtain(error, 0, error.length(), new TextPaint(warn), width)
                            .setMaxLines(4).setEllipsize(TextUtils.TruncateAt.END).build();
                }
                c.save();
                c.translate(LIST_X * k + 30 * k, y - errorLayout.getLineBaseline(0));
                errorLayout.draw(c);
                c.restore();
            }
        }

        /** Status: the comparison with the original home screen, the picks, and the latest notices. */
        private void drawStatus(Canvas c) {
            float x = LIST_X * k + 30 * k;
            c.drawText(Ui.tr("Estado", "Status", "Estado"), x, (LIST_TOP - 24) * k, heading);
            float y = drawComparison(c, (LIST_TOP + 40) * k);
            y = drawRecs(c, y + 40 * k);
            drawNotices(c, y + 56 * k);
        }

        /** Home screen picks: status, and the credit TMDB asks for. */
        private float drawRecs(Canvas c, float y) {
            float x = LIST_X * k + 30 * k;
            c.drawText(Ui.tr("RECOMENDAÇÕES", "RECOMMENDATIONS"), x, y, header);
            String line;
            String error = recs.optString("error");
            int count = recs.optInt("count");
            if (!hasKey) line = Ui.tr("Sem chave do TMDB: adicione em Recomendações → Chave do TMDB.", "No TMDB key: add one in Picks → TMDB key.");
            else if (!error.isEmpty()) line = Ui.tr("Erro: ", "Error: ") + error + ".";
            else if (count > 0 && Prefs.topRecs()) line = Ui.tr("Ligadas · Top do dia, " + count + " títulos.",
                    "On · Today's top, " + count + " titles.");
            else if (count > 0) line = Ui.tr("Ligadas · " + count + " títulos nos apps da TV, 5 novos a cada hora.",
                    "On · " + count + " titles from the TV's apps, 5 new ones every hour.");
            else line = Ui.tr("Buscando os títulos…", "Fetching titles…");
            c.drawText(TextUtils.ellipsize(line, notice, 790 * k, TextUtils.TruncateAt.END).toString(), x, y + 42 * k, notice);
            version.setTextAlign(Paint.Align.LEFT);
            c.drawText(Ui.tr("Usa a API do TMDB, sem endosso ou certificação do TMDB.",
                    "This product uses the TMDB API but is not endorsed or certified by TMDB."), x, y + 78 * k, version);
            version.setTextAlign(Paint.Align.CENTER);
            return y + 78 * k;
        }

        private float drawComparison(Canvas c, float y) {
            float x = LIST_X * k + 30 * k, colA = LIST_R * k - 220 * k, colB = LIST_R * k - 30 * k;
            c.drawText(Ui.tr("COMPARAÇÃO", "COMPARISON"), x, y, header);
            cellValue.setColor(fg(0x73FFFFFF));
            c.drawText("Original", colA, y, cellValue);
            c.drawText("Free My TV", colB, y, cellValue);
            cellValue.setColor(fg(0xD9FFFFFF));
            JSONObject metrics = state.optJSONObject("metrics");
            JSONObject a = metrics == null ? null : metrics.optJSONObject(HomeMode.ORIGINAL);
            JSONObject b = metrics == null ? null : metrics.optJSONObject(HomeMode.FREE);
            String[][] rows = {{Ui.tr("Botão Início", "Home button"), "homeMs"}, {Ui.tr("Memória livre", "Free memory"), "memAvailableMB"},
                    {Ui.tr("Memória do Início", "Home screen memory"), "homePssMB"}};
            for (int i = 0; i < rows.length; i++) {
                float base = y + (46 + 40 * i) * k;
                c.drawText(rows[i][0], x, base, cellLabel);
                c.drawText(metric(a, rows[i][1]), colA, base, cellValue);
                c.drawText(metric(b, rows[i][1]), colB, base, cellValue);
            }
            return y + (46 + 40 * rows.length) * k;
        }

        private void drawNotices(Canvas c, float y) {
            float x = LIST_X * k + 30 * k;
            c.drawText(Ui.tr("AVISOS", "NOTICES"), x, y, header);
            JSONArray notices = state.optJSONArray("notices");
            int shown = 0;
            for (int i = 0; notices != null && i < notices.length() && shown < 5; i++) {
                JSONObject o = notices.optJSONObject(i);
                if (o == null) continue;
                String line = ago(o.optLong("at")) + " · " + o.optString("text");
                c.drawText(TextUtils.ellipsize(line, notice, 790 * k, TextUtils.TruncateAt.END).toString(), x, y + (42 + 38 * shown) * k, notice);
                shown++;
            }
            if (shown == 0) c.drawText(Ui.tr("Nenhum ainda.", "None yet."), x, y + 42 * k, notice);
        }

        /** Home screen choice: Free My TV or the original, with a check on the one in use. */
        private void drawChoice(Canvas c) {
            c.drawText(Ui.tr("Início", "Home Screen"), LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading);
            String[][] options = {{"Free My TV", Ui.tr("Sem anúncios, com a tela de descanso da TV", "No ads, with the TV's own screensaver")},
                    {"Original", Ui.tr("O Início de fábrica da TV", "The TV's factory home screen")}};
            int current = freeActive() ? 0 : 1;
            for (int i = 0; i < 2; i++) {
                float y = (LIST_TOP + 20 + i * (CHOICE_H + ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + CHOICE_H * k);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(blend(fg(0xF2FFFFFF), INK, f));
                rowSub.setColor(blend(secondary(0x8CFFFFFF), 0x991C1C1E, f));
                c.drawText(options[i][0], rowRect.left + 30 * k, rowRect.top + 44 * k, rowLabel);
                c.drawText(options[i][1], rowRect.left + 30 * k, rowRect.top + 80 * k, rowSub);
                if (i == current) {
                    float cw = Symbols.width(getContext(), "check", 26 * k);
                    Symbols.draw(getContext(), c, "check", rowRect.right - 30 * k - cw / 2, rowRect.centerY(), 26 * k, blend(fg(0xFFFFFFFF), INK, f));
                }
            }
            c.drawText(Ui.tr("A troca leva uns segundos e a tela pisca nas medições.",
                    "Switching takes a few seconds; the screen blinks while it measures."), LIST_X * k + 30 * k,
                    (LIST_TOP + 20 + 2 * (CHOICE_H + ROW_GAP) + 40) * k, hint);
        }

        private static int blend(int a, int b, float f) {
            int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
            int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
            return (Math.round(aa + (ba - aa) * f) << 24) | (Math.round(ar + (br - ar) * f) << 16)
                    | (Math.round(ag + (bg - ag) * f) << 8) | Math.round(ab + (bb - ab) * f);
        }

        private static String metric(JSONObject m, String key) {
            if (m == null || !m.has(key)) return "—";
            long v = m.optLong(key);
            if (key.equals("homeMs")) return v + " ms";
            if (key.equals("memAvailableMB") && v >= 1024) return String.format(Ui.locale(), "%.2f GB", v / 1024.0);
            return v + " MB";
        }

        private static String ago(long at) {
            long minutes = Math.max(0, (System.currentTimeMillis() - at) / 60_000);
            if (minutes < 1) return Ui.tr("agora", "now");
            if (minutes < 60) return Ui.tr("há " + minutes + " min", minutes + " min ago");
            if (minutes < 48 * 60) return Ui.tr("há " + (minutes / 60) + " h", (minutes / 60) + " h ago");
            return Ui.tr("há " + (minutes / 1440) + " dias", (minutes / 1440) + " days ago");
        }

        private String versionText;   // read once: drawn on every frame of a focus animation

        private String versionText() {
            if (versionText == null) versionText = versionName(getContext());
            return versionText;
        }

        private static String versionName(Context c) {
            try {
                return "Free My TV " + c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
            } catch (android.content.pm.PackageManager.NameNotFoundException e) {
                return "Free My TV";
            }
        }

        // MARK: - Keys

        boolean onKey(KeyEvent e) {
            boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
            if (learningKeys && e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                if (!down) stopLearningKeys();
                return true;
            }
            // Without the key service (or a key it doesn't see), this screen takes the button itself.
            if (learningKeys && !RemoteKeys.reserved(e.getKeyCode())) {
                if (down && e.getRepeatCount() == 0) {
                    Prefs.learned(RemoteKeys.identity(e));
                    checkLearned();
                }
                return true;
            }
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (down) {
                        click.move();   // every press clicks
                        int dir = e.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1;
                        int next = focus + dir;
                        if (next >= 0 && next < rowCount()) {
                            focus = next;
                            keepVisible(false);
                            invalidate();
                            spoken.focusMoved();
                        }
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (down) {
                        click.move();
                        Row r = screen == PAGE && focus < rowCount() ? page.rows.get(focus) : null;
                        if (r != null && r.adjust != null) {
                            r.adjust.accept(e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                            if (page.rebuild != null) page.rebuild.run();
                            changed();
                            spoken.focusMoved();
                        }
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (down) {
                        if (e.getRepeatCount() == 0) okDown = true;
                    } else {
                        if (okDown && !e.isCanceled() && spoken.keyClick()) activate();
                        okDown = false;
                    }
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    if (screen == PAGE && page == main && back.isEmpty()) {
                        if (down) click.move();
                        return false;   // leaves the settings
                    }
                    if (down) {
                        if (e.getRepeatCount() == 0) backDown = true;
                    } else {
                        if (backDown && !e.isCanceled()) {
                            click.move();
                            goBack();
                        }
                        backDown = false;
                    }
                    invalidate();
                    spoken.focusMoved();
                    return true;
                default:
                    return false;
            }
        }

        private void activate() {
            click.open();
            if (screen == HOME_CHOICE) {
                boolean wantFree = focus == 0;
                if (wantFree != freeActive() && !busy()) {
                    HomeMode.request(getContext(), wantFree ? HomeMode.FREE : HomeMode.ORIGINAL);
                    pendingAt = SystemClock.uptimeMillis();
                }
                goBack();
            } else if (screen == PAGE && focus < rowCount()) {
                Page before = page;
                page.rows.get(focus).ok.run();
                if (page == before && page.rebuild != null) page.rebuild.run();
            }
            invalidate();
            spoken.focusMoved();
        }

        // MARK: - Choices behind the rows

        private static final String[] UNITS = {"auto", "C", "F"}, THEMES = {"auto", "light", "dark"};

        /** "auto", then English and every language with a catalogue (assets/i18n). */
        private static String[] languageChoices() {
            String[] all = Ui.languages();
            String[] v = new String[all.length + 1];
            v[0] = "auto";
            System.arraycopy(all, 0, v, 1, all.length);
            return v;
        }

        private List<String> languageNames() {
            List<String> l = new ArrayList<>();
            for (String code : languageChoices()) l.add(code.equals("auto") ? Ui.tr("Automático", "Automatic") : Ui.languageName(code));
            return l;
        }

        private static int languageIndex() {
            return Math.max(0, java.util.Arrays.asList(languageChoices()).indexOf(Prefs.language()));
        }

        private boolean dollar() {
            return "BRL".equalsIgnoreCase(Config.CURRENCY.trim());
        }

        /** Whether there is a key and where it comes from; never the key itself. */
        private String tmdbKeyState() {
            if (Prefs.typedTmdbKey()) return Ui.tr("Configurada na TV", "Set on the TV");
            return Prefs.tmdbKey().isEmpty() ? Ui.tr("Nenhuma: OK para inserir", "None: OK to add one")
                    : Ui.tr("Do config.properties", "From config.properties");
        }

        /** The click's volume, from off to high (Prefs.soundLevel). */
        private static List<String> soundLevels() {
            return list(Ui.tr("Desligado", "Off"), Ui.tr("Baixo", "Low", "Bajo"), Ui.tr("Médio", "Medium", "Medio"),
                    Ui.tr("Alto", "High", "Alto"));
        }

        /** Continue watching: every app, or how many are kept out. */
        private static String watchAppsState() {
            int off = Prefs.watchNextOffCount();
            return off == 0 ? Ui.tr("Todos os apps", "All apps", "Todas las apps")
                    : Ui.tr(off + (off == 1 ? " app desligado" : " apps desligados"), off + (off == 1 ? " app off" : " apps off"),
                            off + (off == 1 ? " app desactivada" : " apps desactivadas"));
        }

        /** "Open picks in": each title's own streaming app (the default), then the apps that open IMDb links. */
        private static String[] targetKeys() {
            String[] keys = new String[Recs.LINK_APPS.length + 1];
            keys[0] = "apps";
            for (int i = 0; i < Recs.LINK_APPS.length; i++) keys[i + 1] = Recs.LINK_APPS[i][0];
            return keys;
        }

        private static List<String> targetNames() {
            List<String> names = new ArrayList<>();
            names.add(Ui.tr("App de cada título", "Each title's app"));
            for (String[] a : Recs.LINK_APPS) names.add(a[1]);
            return names;
        }

        private static int targetIndex() {
            return Math.max(0, java.util.Arrays.asList(targetKeys()).indexOf(Prefs.recsTarget()));
        }

        private String[] keyLabels() {
            if (RemoteKeys.STEM_NETFLIX) return new String[] {Ui.tr("Botão Netflix", "Netflix button")};
            if (RemoteKeys.KTC) return new String[] {Ui.tr("Botão Tabii", "Tabii button")};
            if (RemoteKeys.SONY) return new String[] {Ui.tr("Botão BRAVIA CORE", "BRAVIA CORE button"),
                    Ui.tr("Botão Netflix", "Netflix button"), Ui.tr("Botão Disney+", "Disney+ button"),
                    Ui.tr("Botão Prime Video", "Prime Video button"), Ui.tr("Botão YouTube", "YouTube button"),
                    Ui.tr("Botão Crunchyroll", "Crunchyroll button"), Ui.tr("Botão Google Play", "Google Play button")};
            // TCL's remotes send other codes for the same button from one model to the next: no names, the code (keyName).
            return new String[0];
        }

        /** Installed TV apps, by name, after "Nothing": what a remote button can open. */
        private void loadApps() {
            PackageManager pm = getContext().getPackageManager();
            List<String[]> found = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
            for (ResolveInfo ri : pm.queryIntentActivities(query, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(getContext().getPackageName()) || !seen.add(pkg)) continue;
                found.add(new String[] {pkg, String.valueOf(ri.loadLabel(pm))});
            }
            Collections.sort(found, (a, b) -> a[1].toLowerCase(Locale.ROOT).compareTo(b[1].toLowerCase(Locale.ROOT)));
            appPkgs.clear();
            appLabels.clear();
            appPkgs.add("");
            // On Sony, "nothing" leaves the key to the TV, which opens its usual app.
            appLabels.add(RemoteKeys.SONY ? Ui.tr("Sony default") : Ui.tr("Nada", "Nothing"));
            // Free My TV's own, over any app.
            appPkgs.add(Prefs.OPEN_CONTROL);
            appLabels.add(Ui.tr("Central de controle", "Control Center", "Centro de control"));
            appPkgs.add(Prefs.OPEN_RECENTS);
            appLabels.add(Ui.tr("Apps recentes", "Recent apps", "Apps recientes"));
            appPkgs.add(Prefs.SLEEP_CYCLE);
            appLabels.add(Ui.tr("Timer de desligar", "Sleep timer", "Temporizador de apagado"));
            for (String[] f : found) {
                appPkgs.add(f[0]);
                appLabels.add(f[1]);
            }
        }

        /** Weather city: type the name (the Google TV app on a phone types too); the matches follow, as a list. */
        private void askCity() {
            if (Ui.tr("Buscando…", "Searching…").equals(cityStatus)) return;
            android.widget.EditText field = new android.widget.EditText(activity);
            field.setSingleLine(true);
            field.setHint(Ui.tr("Digite o nome da cidade", "Type the city's name"));
            field.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
            android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(Ui.tr("Cidade do clima", "Weather city"))
                    .setView(field)
                    .setPositiveButton(Ui.tr("Buscar", "Search"), (d, which) -> searchCity(field.getText().toString()))
                    .setNegativeButton(Ui.tr("Cancelar", "Cancel"), null)
                    .create();
            field.setOnEditorActionListener((v, action, event) -> {
                dialog.dismiss();
                searchCity(field.getText().toString());
                return true;
            });
            dialog.show();
        }

        private void searchCity(String name) {
            if (name.trim().isEmpty()) return;
            cityStatus = Ui.tr("Buscando…", "Searching…");
            Page from = page;
            invalidate();
            new Thread(() -> {
                List<String[]> found;
                String error;
                try {
                    found = HomeData.findCity(name);
                    error = found.isEmpty() ? Ui.tr("Nenhuma cidade com esse nome.", "No city with that name.") : "";
                } catch (Exception e) {
                    found = new ArrayList<>();
                    error = Ui.tr("Não deu para buscar agora.", "Couldn't search now.");
                    AppLog.w("city search: " + e.getMessage());
                }
                List<String[]> result = found;
                String message = error;
                handler.post(() -> {
                    cityStatus = message;
                    if (!result.isEmpty() && screen == PAGE && page == from) {
                        List<String> labels = new ArrayList<>();
                        for (String[] city : result) labels.add(city[0]);
                        // {label, name, latitude, longitude}
                        openPicker(Ui.tr("Cidade do clima", "Weather city"), labels, -1, at -> {
                            String[] city = result.get(at);
                            Prefs.setCity(city[1], city[2], city[3]);
                        });
                        spoken.focusMoved();
                    }
                    invalidate();
                    if (!message.isEmpty()) handler.postDelayed(() -> { cityStatus = ""; invalidate(); }, 4000);
                });
            }).start();
        }

        // MARK: - TV Apps: any app on or off, the system's too

        private static final int APPS_INSTALLED = 0, APPS_SYSTEM = 1, APPS_OFF = 2;
        private List<HomeMode.TvApp> tvApps;   // null until read
        private final java.util.Map<String, String> tvAppLabels = new java.util.HashMap<>();
        private final java.util.Map<String, Boolean> tvAppsPending = new java.util.HashMap<>();   // asked: on or off
        private String tvAppsStatus = "";
        private boolean readingTvApps;

        private void openTvApps() {
            Page p = new Page(Ui.tr("Apps da TV", "TV Apps", "Apps de la TV"));
            p.rows.add(tvAppsGroup(() -> Ui.tr("Apps instalados", "Installed apps", "Apps instaladas"), APPS_INSTALLED));
            p.rows.add(tvAppsGroup(() -> Ui.tr("Apps do sistema", "System apps", "Apps del sistema"), APPS_SYSTEM));
            p.rows.add(tvAppsGroup(() -> Ui.tr("Desligados", "Turned off", "Apagadas"), APPS_OFF));
            p.hint = this::tvAppsHint;
            open(p);
            readTvApps();
        }

        private Row tvAppsGroup(java.util.function.Supplier<String> label, int which) {
            Row r = new Row(label, () -> tvApps == null ? "…" : String.valueOf(tvAppsIn(which).size()), () -> open(tvAppsPage(label.get(), which)), null, null);
            r.opens = true;
            return r;
        }

        private List<HomeMode.TvApp> tvAppsIn(int which) {
            List<HomeMode.TvApp> out = new ArrayList<>();
            if (tvApps == null) return out;
            for (HomeMode.TvApp app : tvApps) {
                if (which == APPS_OFF ? !app.enabled : which == APPS_SYSTEM ? app.system : !app.system) out.add(app);
            }
            return out;
        }

        /** One app per row: OK turns it on or off; a locked one says why it stays. */
        private Page tvAppsPage(String title, int which) {
            Page p = new Page(title);
            p.rebuild = () -> {
                List<String> before = tvAppsShown(p);
                String focused = page == p && focus < before.size() ? before.get(focus) : null;   // the focus stays on its app
                p.rows.clear();
                List<String> shown = new ArrayList<>();
                for (HomeMode.TvApp app : tvAppsIn(which)) {
                    shown.add(app.pkg);
                    String name = tvAppLabels.containsKey(app.pkg) ? tvAppLabels.get(app.pkg) : app.pkg;
                    p.rows.add(new Row(() -> name, () -> tvAppState(app), () -> toggleTvApp(app, name), null,
                            () -> !app.locked.isEmpty()));
                }
                if (p.rows.isEmpty()) {
                    p.rows.add(new Row(() -> tvApps == null ? Ui.tr("Lendo os apps…", "Reading the apps…", "Leyendo las apps…") : Ui.tr("Nenhum.", "None.", "Ninguna."), () -> "", () -> {}, null, () -> true));
                }
                tvAppsShownByPage.put(p, shown);
                for (int i = 0; focused != null && i < shown.size(); i++) if (shown.get(i).equals(focused)) focus = i;
                keepVisible(false);
            };
            p.hint = this::tvAppsHint;
            p.rebuild.run();
            return p;
        }

        private final java.util.Map<Page, List<String>> tvAppsShownByPage = new java.util.WeakHashMap<>();

        private List<String> tvAppsShown(Page p) {
            List<String> shown = tvAppsShownByPage.get(p);
            return shown == null ? new ArrayList<>() : shown;
        }

        private String tvAppState(HomeMode.TvApp app) {
            Boolean asked = tvAppsPending.get(app.pkg);
            if (asked != null) return asked ? Ui.tr("Ligando…", "Turning on…", "Encendiendo…") : Ui.tr("Desligando…", "Turning off…", "Apagando…");
            if (!app.enabled) return Ui.tr("Desligado", "Off");
            return app.locked.isEmpty() ? Ui.tr("Ligado", "On") : Ui.tr("Protegido", "Protected", "Protegida");
        }

        private String tvAppsHint() {
            if (!tvAppsStatus.isEmpty()) return tvAppsStatus;
            return Ui.tr("Nada é desinstalado: o modo Original liga todos os apps de volta.", "Nothing is uninstalled: Original turns every app back on.", "Nada se desinstala: el modo Original vuelve a encender todas las apps.");
        }

        private void toggleTvApp(HomeMode.TvApp app, String name) {
            if (!app.locked.isEmpty()) {
                tvAppsStatus = Ui.trf(Ui.tr("{app} fica como está: {reason}.", "{app} stays as it is: {reason}.", "{app} queda como está: {reason}."), "app", name, "reason", app.locked);
                return;
            }
            if (tvAppsPending.containsKey(app.pkg)) return;
            if (app.enabled && app.system) {
                // A system app: asked first, it can be a piece the TV uses.
                openPicker(Ui.trf(Ui.tr("Desligar {app}?", "Turn off {app}?", "¿Apagar {app}?"), "app", name),
                        list(Ui.tr("Desligar", "Turn off", "Apagar"), Ui.tr("Cancelar", "Cancel")), 1, at -> { if (at == 0) askTvApp(app.pkg, false); });
                return;
            }
            askTvApp(app.pkg, !app.enabled);
        }

        private void askTvApp(String pkg, boolean on) {
            tvAppsStatus = "";
            tvAppsPending.put(pkg, on);
            HomeMode.requestAppEnabled(getContext(), pkg, on);
            // The job runs in the main process; the list is read again once it's done (the checks take a few seconds).
            handler.postDelayed(this::readTvApps, 3000);
            handler.postDelayed(() -> {
                tvAppsPending.remove(pkg);
                readTvApps();
            }, 8000);
            invalidate();
        }

        /** The apps, through the TV's shell, on a thread; the pages on screen are rebuilt with them. */
        private void readTvApps() {
            if (readingTvApps) return;
            readingTvApps = true;
            new Thread(() -> {
                List<HomeMode.TvApp> list = null;
                String error = "";
                java.util.Map<String, String> labels = new java.util.HashMap<>();
                try {
                    list = HomeMode.tvApps(getContext());
                    java.util.Map<String, Integer> seen = new java.util.HashMap<>();
                    for (HomeMode.TvApp app : list) {
                        String label = HomeApps.label(getContext(), app.pkg);
                        labels.put(app.pkg, label);
                        seen.put(label, seen.containsKey(label) ? seen.get(label) + 1 : 1);
                    }
                    // Several "Android System": the package tells them apart.
                    for (HomeMode.TvApp app : list) {
                        String label = labels.get(app.pkg);
                        if (seen.get(label) > 1 && !label.equals(app.pkg)) labels.put(app.pkg, label + " · " + app.pkg);
                    }
                    // By name; the ones with only a package name (no label) after them.
                    Collections.sort(list, (a, b) -> {
                        String la = labels.get(a.pkg), lb = labels.get(b.pkg);
                        boolean na = la.equals(a.pkg), nb = lb.equals(b.pkg);
                        if (na != nb) return na ? 1 : -1;
                        return la.toLowerCase(Locale.ROOT).compareTo(lb.toLowerCase(Locale.ROOT));
                    });
                } catch (java.io.IOException e) {
                    AppLog.w("tv apps: " + e.getMessage());
                    error = Ui.trf(Ui.tr("Não deu para ler os apps: {error}", "Couldn't read the apps: {error}", "No se pudieron leer las apps: {error}"), "error", e.getMessage());
                }
                List<HomeMode.TvApp> result = list;
                String message = error;
                handler.post(() -> {
                    readingTvApps = false;
                    if (result != null) {
                        tvApps = result;
                        tvAppLabels.putAll(labels);
                        for (HomeMode.TvApp app : result) {
                            Boolean asked = tvAppsPending.get(app.pkg);
                            if (asked != null && asked == app.enabled) tvAppsPending.remove(app.pkg);
                        }
                    }
                    if (!message.isEmpty()) tvAppsStatus = message;
                    if (page != null && page.rebuild != null) page.rebuild.run();
                    invalidate();
                });
            }, "tv-apps").start();
        }

        // MARK: - Shelf editor

        private void openShelfEditor() {
            shelfOrder.clear();
            shelfOrder.addAll(HomeApps.shelfPackages(getContext()));
            List<String[]> candidates = HomeApps.shelfCandidates(getContext());
            Page p = new Page(Ui.tr("Prateleira", "Shelf"));
            p.rebuild = () -> {
                // Selected apps first, in shelf order, then the rest in the order they came; the focus stays on its app.
                String focused = focus < shelfRows.size() ? shelfRows.get(focus)[0] : null;
                shelfRows.clear();
                for (String pkg : shelfOrder) {
                    for (String[] cand : candidates) if (cand[0].equals(pkg)) { shelfRows.add(cand); break; }
                }
                for (String[] cand : candidates) if (!shelfOrder.contains(cand[0])) shelfRows.add(cand);
                p.rows.clear();
                for (String[] app : shelfRows) {
                    String pkg = app[0];
                    p.rows.add(new Row(() -> app[1], () -> {
                        int place = shelfOrder.indexOf(pkg);
                        return place >= 0 ? "#" + (place + 1) : Ui.tr("Fora", "Off");
                    }, () -> toggleShelfApp(pkg), dir -> moveShelfApp(pkg, dir), () -> !shelfOrder.contains(pkg)));
                }
                for (int i = 0; focused != null && i < shelfRows.size(); i++) if (shelfRows.get(i)[0].equals(focused)) focus = i;
                keepVisible(false);
            };
            p.hint = () -> Ui.tr("OK põe ou tira da prateleira (até " + HomeApps.MAX_SHELF + "). ← → mudam a ordem.",
                    "OK adds or removes (up to " + HomeApps.MAX_SHELF + "). ← → change the order.",
                    "OK pone o quita del estante (hasta " + HomeApps.MAX_SHELF + "). ← → cambian el orden.");
            shelfRows.clear();
            open(p);
            p.rebuild.run();
            focus = 0;
            keepVisible(true);
        }

        /** OK: puts the app on the shelf (at the end) or takes it off. */
        private void toggleShelfApp(String pkg) {
            if (shelfOrder.contains(pkg)) shelfOrder.remove(pkg);
            else if (shelfOrder.size() < HomeApps.MAX_SHELF) shelfOrder.add(pkg);
            else return;
            Prefs.setShelf(shelfOrder);
        }

        /** ← → : moves a shelf app one place earlier or later. */
        private void moveShelfApp(String pkg, int dir) {
            int at = shelfOrder.indexOf(pkg), to = at + dir;
            if (at < 0 || to < 0 || to >= shelfOrder.size()) return;
            shelfOrder.remove(at);
            shelfOrder.add(to, pkg);
            Prefs.setShelf(shelfOrder);
        }

        // MARK: - Continue watching: which apps may show

        /** The TV's video apps and any with something to continue, on or off for the home screen. */
        private void openWatchApps() {
            Page p = new Page(Ui.tr("Continuar assistindo", "Continue Watching", "Seguir viendo"));
            for (String[] app : WatchNext.apps(getContext())) {
                String pkg = app[0];
                p.rows.add(new Row(() -> app[1], () -> onOffText(!Prefs.watchNextOff(pkg)),
                        () -> Prefs.setWatchNextOff(pkg, !Prefs.watchNextOff(pkg)),
                        dir -> Prefs.setWatchNextOff(pkg, !Prefs.watchNextOff(pkg)), () -> Prefs.watchNextOff(pkg)));
            }
            p.hint = () -> !WatchNext.allowed(getContext())
                    ? Ui.tr("Sem acesso à lista dos apps: troque o Início para Free My TV de novo.",
                            "No access to the apps' lists: switch the home screen to Free My TV again.",
                            "Sin acceso a la lista de las apps: cambia el inicio a Free My TV otra vez.")
                    : p.rows.isEmpty()
                    ? Ui.tr("Nenhum app de vídeo instalado.", "No video apps installed.", "Ninguna app de video instalada.")
                    : Ui.tr("OK liga ou desliga. Os apps de vídeo da TV e os que têm algo para continuar.",
                            "OK turns it on or off. The TV's video apps and any with something to continue.",
                            "OK activa o desactiva. Las apps de video del TV y las que tienen algo para seguir.");
            open(p);
        }

        private static String onOffText(boolean on) {
            return on ? Ui.tr("Ligado", "On") : Ui.tr("Desligado", "Off");
        }
    }
}
