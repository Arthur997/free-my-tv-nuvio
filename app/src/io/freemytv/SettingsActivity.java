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
        private boolean running, okDown, backDown, wasBusy;
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

        private final Runnable poll = new Runnable() {
            @Override public void run() {
                if (!running) return;
                state = HomeMode.state(getContext());
                hasKey = Recs.hasKey(getContext());
                recs = Recs.status(getContext());
                String services = android.provider.Settings.Secure.getString(getContext().getContentResolver(),
                        android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
                keysServiceOn = services != null && services.contains(HomeMode.PKG + "/");
                boolean nowBusy = !HomeMode.busyText(state).isEmpty();
                if (nowBusy) pendingAt = 0;
                // A switch just ended: TalkBack reads how it went (the newest notice).
                JSONArray notices = state.optJSONArray("notices");
                if (wasBusy && !nowBusy && notices != null && notices.length() > 0) {
                    JSONObject newest = notices.optJSONObject(0);
                    if (newest != null) announceForAccessibility(newest.optString("text"));
                }
                wasBusy = nowBusy;
                invalidate();
                handler.postDelayed(this, 500);
            }
        };

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
            p.rows.add(opens(() -> Ui.tr("Recomendações", "Picks"), () -> open(picksPage())));
            p.rows.add(opens(() -> Ui.tr("Clima e cotações", "Weather and prices", "Clima y cotizaciones"), () -> open(weatherPage())));
            p.rows.add(opens(() -> Ui.tr("Tela de descanso", "Screensaver"), () -> open(screensaverPage())));
            p.rows.add(opens(() -> Ui.tr("Prateleira", "Shelf"), this::openShelfEditor));
            p.rows.add(opens(() -> Ui.tr("Botões do Controle", "Remote Buttons"), () -> {
                HomeMode.ensureRemoteService(getContext(), 0);   // the buttons work from inside other apps through this service
                loadApps();
                open(keysPage());
            }));
            p.rows.add(opens(() -> Ui.tr("Estado", "Status", "Estado"), () -> go(STATUS, null)));
            p.rows.add(opens(() -> Ui.tr("Ajustes da TV", "TV Settings"), () ->
                    activity.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))));
            return p;
        }

        /** Language, theme, units, the top of the home screen, the shelf's art, performance mode and the sounds. */
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
            p.rows.add(choice(() -> Ui.tr("Modo desempenho", "Performance mode"),
                    () -> list(Ui.tr("Desligado", "Off"), Ui.tr("Ligado (sem vidro)", "On (no glass)")),
                    () -> Prefs.performance() ? 1 : 0, at -> Prefs.setPerformance(at == 1)));
            p.rows.add(choice(() -> Ui.tr("Volume dos sons", "Sound volume", "Volumen de los sonidos"), SettingsView::soundLevels,
                    Prefs::soundLevel, Prefs::setSoundLevel));
            p.hint = () -> Ui.tr("OK abre as opções; ← e → mudam direto.", "OK opens the choices; ← and → change it right away.");
            return p;
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

        /** Remote Buttons: the app each of the brand's buttons opens. */
        private Page keysPage() {
            Page p = new Page(Ui.tr("Botões do Controle", "Remote Buttons"));
            String[] labels = keyLabels();
            for (int i = 0; i < Prefs.KEYS.length && i < labels.length; i++) {
                int key = Prefs.KEYS[i];
                String label = labels[i];
                p.rows.add(new Row(() -> onRemote(key) ? label : label + " · " + Ui.tr("não visto", "not seen yet", "no visto"), () -> {
                    int at = appPkgs.indexOf(Prefs.keyApp(key));
                    return at >= 0 ? appLabels.get(at) : Ui.tr("Não instalado", "Not installed");
                }, () -> openPicker(label, appLabels, appPkgs.indexOf(Prefs.keyApp(key)), at -> setKeyApp(key, appPkgs.get(at))),
                        dir -> {
                            int n = appPkgs.size(), at = appPkgs.indexOf(Prefs.keyApp(key));
                            setKeyApp(key, appPkgs.get(at < 0 ? (dir > 0 ? 0 : n - 1) : (at + dir + n) % n));
                        }, null));
            }
            p.hint = () -> keysHint(labels);
            p.hint2 = () -> RemoteKeys.supported() ? Ui.tr("Disney e Globoplay: a própria TV controla esses botões.",
                    "Disney and Globoplay: the TV itself controls those buttons.")
                    : Ui.tr("Esses são os botões do controle da TCL: nesta TV, os botões funcionam como sempre.",
                    "These are the TCL remote's buttons: on this TV, the buttons work as they always did.");
            return p;
        }

        private boolean keysServiceOn = true;   // read by the poll: the TV's list of enabled accessibility services
        private String keysError = "";

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

        /** What's wrong, else the button just pressed and what it opens, else how to test one. */
        private String keysHint(String[] labels) {
            if (!keysError.isEmpty()) return keysError;
            // The service runs only in Free My TV mode (Original turns it off on purpose).
            if (!keysServiceOn && RemoteKeys.supported() && HomeMode.FREE.equals(state.optString("mode"))) {
                return Ui.tr("O serviço dos botões está desligado. Ligando…", "The buttons' service is off. Turning it on…",
                        "El servicio de los botones está apagado. Encendiéndolo…");
            }
            long now = System.currentTimeMillis();
            for (int i = 0; i < Prefs.KEYS.length && i < labels.length; i++) {
                if (now - Prefs.keySeenAt(Prefs.KEYS[i]) > 15_000) continue;
                int at = appPkgs.indexOf(Prefs.keyApp(Prefs.KEYS[i]));
                String app = at >= 0 ? appLabels.get(at) : Ui.tr("Não instalado", "Not installed");
                return Ui.trf("{button} works: it opens {app}.", "button", labels[i], "app", app);
            }
            return Ui.tr("OK abre a lista de apps. Aperte um botão do controle para testar.",
                    "OK opens the list of apps. Press a button on the remote to test it.",
                    "OK abre la lista de apps. Pulsa un botón del control para probarlo.");
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
            if (dim == null) return r;
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
            while (pop.size() <= i) pop.add(new Spring(0.28f, 0.85f, 0));
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
            Choreographer.getInstance().postFrameCallback(this);
            spoken.readAgain();
        }

        void release() {
            click.release();
        }

        void pause() {
            click.stop();
            running = false;
            handler.removeCallbacks(poll);
            Choreographer.getInstance().removeFrameCallback(this);
        }

        @Override public void doFrame(long frameTimeNanos) {
            if (!running) return;
            boolean moving = busy() || !scroll.settled();
            for (Spring s : pop) moving |= !s.settled();
            if (moving) invalidate();   // still: nothing to redraw
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
            version = Ui.text(Ui.regular, 22 * k, fg(0x66FFFFFF));
            version.setTextAlign(Paint.Align.CENTER);
            rowLabel = Ui.text(Ui.medium, 30 * k, fg(0xF2FFFFFF));
            rowValue = Ui.text(Ui.regular, 28 * k, fg(0x8CFFFFFF));
            rowValue.setTextAlign(Paint.Align.RIGHT);
            rowSub = Ui.text(Ui.regular, 23 * k, fg(0x8CFFFFFF));
            header = Ui.text(Ui.semibold, 21 * k, fg(0x73FFFFFF));
            header.setLetterSpacing(0.06f);
            cellLabel = Ui.text(Ui.regular, 25 * k, fg(0xD9FFFFFF));
            cellValue = Ui.numbers(Ui.regular, 25 * k, fg(0xD9FFFFFF));
            cellValue.setTextAlign(Paint.Align.RIGHT);
            warn = Ui.text(Ui.regular, 23 * k, lightTheme ? 0xFFB35C00 : AMBER);
            notice = new TextPaint(Ui.text(Ui.regular, 23 * k, fg(0xB3FFFFFF)));
            heading = Ui.text(Ui.displaySemibold, 34 * k, fg(0xFFFFFFFF));
            hint = Ui.text(Ui.regular, 23 * k, fg(0x8CFFFFFF));
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
            c.drawText(versionName(getContext()), cx, 704 * k, version);

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
            int fg = fg(0x59FFFFFF);
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
            if (!hint1.isEmpty()) c.drawText(hint1, x, hy, hint);
            if (!hint2.isEmpty()) c.drawText(hint2, x, hy + 38 * k, hint);
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
                float s = 1 + 0.02f * f;
                float cx = rowRect.centerX(), cy = rowRect.centerY();
                tmp.set(cx - rowRect.width() * s / 2, cy - rowRect.height() * s / 2, cx + rowRect.width() * s / 2, cy + rowRect.height() * s / 2);
                float r = 18 * k * s;
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
                rowSub.setColor(blend(fg(0x8CFFFFFF), 0x991C1C1E, f));
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
            return new String[] {Ui.tr("Botão Netflix", "Netflix button"), Ui.tr("Botão YouTube", "YouTube button"),
                    Ui.tr("Botão Prime Video", "Prime Video button"), Ui.tr("Botão TCL Channel", "TCL Channel button")};
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
