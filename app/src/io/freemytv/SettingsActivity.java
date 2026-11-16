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
    private boolean askedPrepare;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Watchdog.install(this);
        Ui.init(this);
        Ui.themeWindow(this);
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
        // The pairing assistant (wireless debugging paired), or the first time on a TV not switched yet.
        if (getIntent().getBooleanExtra("prepare", false)) {
            getIntent().removeExtra("prepare");
            view.openPrepare();
        } else if (!Prefs.prepared() && !askedPrepare && !HomeMode.state(this).has("since")) {
            askedPrepare = true;
            view.openPrepare();
        }
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
        input.setHint(Ui.tr("API Key ou Read Access Token"));
        android.widget.FrameLayout box = new android.widget.FrameLayout(this);
        int pad = Math.round(24 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, 0, pad, 0);
        box.addView(input);
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(Ui.tr("Chave do TMDB"))
                .setMessage(Ui.tr("Crie uma de graça em themoviedb.org → Settings → API e cole aqui. Dica: o app Google TV no "
                        + "celular serve de teclado (e cola)."))
                .setView(box)
                .setPositiveButton(Ui.tr("Salvar"), (d, w) -> saveTmdbKey(input.getText().toString()))
                .setNegativeButton(Ui.tr("Cancelar"), null);
        if (Prefs.typedTmdbKey()) builder.setNeutralButton(Ui.tr("Apagar"), (d, w) -> saveTmdbKey(""));
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
        /** The main menu's Home Screen row: its value shows the switch while it runs. */
        private static final int HOME_ROW = 1;

        /** One row: its name and value (read when drawn), OK, ← → (null: none), and whether it's greyed out. */
        private static final class Row {
            final java.util.function.Supplier<String> label, value;
            final Runnable ok;
            final java.util.function.IntConsumer adjust;
            final java.util.function.BooleanSupplier dim;
            boolean opens;   // OK opens a page or a list: a chevron instead of a value
            boolean check;   // the choice in use, in a list of choices
            /** What it does, on the left while it has the focus (null: the page's). */
            java.util.function.Supplier<String> help;

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
            /** The big symbol on the left, and the page's text there when the focused row has none. */
            String symbol = "sparkles";
            java.util.function.Supplier<String> about;
            /** Prepare the TV's pages: the poll also reads the TV's debugging settings. */
            boolean live;

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
        // The list's panel: the home screen's shelf glass (DockGlass; flat in performance mode).
        private final Scene scene = new Scene();
        private final Paint glassShadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final DockGlass glass = new DockGlass(scene, new Shadows(), glassShadow);
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
        private TextPaint notice, aboutPaint;
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
                boolean live = screen == PAGE && page != null && page.live;
                pollIo.execute(() -> {
                    boolean[] checks = live ? prepareChecks(c) : null;
                    JSONObject newState = HomeMode.state(c);
                    boolean newHasKey = Recs.hasKey(c);
                    JSONObject newRecs = Recs.status(c);
                    String services = android.provider.Settings.Secure.getString(c.getContentResolver(),
                            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
                    boolean serviceOn = services != null && services.contains(HomeMode.PKG + "/");
                    handler.post(() -> {
                        if (checks != null) applyChecks(checks);
                        applyPoll(newState, newHasKey, newRecs, serviceOn);
                    });
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
            if (wasBusy && !nowBusy && page == cleanupPage && cleanupPage != null) analyzeCleanup();
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
                    .append('|').append(hasKey).append(keysServiceOn);
            if (page == null) return b.toString();   // Status and the home screen choice: no page, no rows
            b.append('|').append(page.hint.get()).append(page.hint2.get());
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

        /**
         * The main menu, in six groups: Prepare the TV, the home screen switch, and one page per topic. Every row says
         * on the left what it does.
         */
        private Page mainPage() {
            Page p = new Page(null);
            // The last switch failed because the TV's debugging is off (a TV update can do that): the first row then
            // goes straight to where it's turned on.
            p.rows.add(helped(opens(() -> debuggingOff() ? Ui.tr("Ligar a depuração")
                    : Ui.tr("Preparar a TV"), () -> {
                        if (debuggingOff()) open(debuggingPage());
                        else openPrepare();
                    }), () -> debuggingOff()
                    ? Ui.tr("A depuração da TV desligou (uma atualização da TV faz isso). Sem ela, o Free My TV não troca o Início nem desliga apps.")
                    : Ui.tr("Guia passo a passo: depuração, autorização, Início, limpeza e chave do TMDB.")));
            p.rows.add(helped(new Row(() -> Ui.tr("Início"),
                    () -> busy() ? "" : freeActive() ? "Free My TV" : Ui.tr("Original"), this::openHomeChoice, null, null),
                    () -> Ui.tr("Escolhe entre o Início do Free My TV e o original da TV. A troca leva alguns segundos e dá para voltar quando quiser.")));
            p.rows.add(helped(opens(() -> Ui.tr("Aparência"), () -> open(appearancePage())),
                    () -> Ui.tr("Idioma, tema claro ou escuro, temperatura, relógio, capas dos apps e sons.")));
            p.rows.add(helped(opens(() -> Ui.tr("Conteúdo do Início"), () -> open(contentPage())),
                    () -> Ui.tr("O que aparece no Início: a prateleira de apps, as recomendações, clima, cotações e futebol, e a tela de descanso.")));
            p.rows.add(helped(opens(() -> Ui.tr("Apps e controle"), () -> open(appsPage())),
                    () -> Ui.tr("Liga e desliga apps da TV, limpa a TV e escolhe o que cada botão do controle abre.")));
            p.rows.add(helped(opens(() -> Ui.tr("Sistema"), () -> open(systemPage())),
                    () -> Ui.tr("Desempenho, atualizações do app, o estado da troca, o que abre ao ligar a TV e os ajustes dela.")));
            return p;
        }

        /** The home screen's parts: the shelf, the picks, the drawer's cards and the screensaver. */
        private Page contentPage() {
            Page p = new Page(Ui.tr("Conteúdo do Início"));
            p.symbol = "home";
            p.rows.add(helped(opens(() -> Ui.tr("Prateleira"), this::openShelfEditor),
                    () -> Ui.tr("Os apps da fileira do Início: quais aparecem e em que ordem.")));
            p.rows.add(helped(opens(() -> Ui.tr("Recomendações"), () -> open(picksPage())),
                    () -> Ui.tr("Filmes e séries sugeridos no topo do Início, a chave do TMDB e o Continuar assistindo.")));
            p.rows.add(helped(opens(() -> Ui.tr("Clima, cotações e futebol"),
                    () -> open(weatherPage())),
                    () -> Ui.tr("Os cartões da gaveta: o clima da sua cidade e, ao lado, cotações ou o seu time.")));
            p.rows.add(helped(opens(() -> Ui.tr("Tela de descanso"), () -> open(screensaverPage())),
                    () -> Ui.tr("O que a TV mostra parada: as fotos do Google ou a tela de descanso do Free My TV.")));
            return p;
        }

        /** The TV's apps by hand, the cleanup and the remote's buttons. */
        private Page appsPage() {
            Page p = new Page(Ui.tr("Apps e controle"));
            p.symbol = "apps";
            p.rows.add(helped(opens(() -> Ui.tr("Apps da TV"), this::openTvApps),
                    () -> Ui.tr("Todos os apps da TV, um a um: liga ou desliga (nunca desinstala). O que a TV precisa fica protegido.")));
            p.rows.add(helped(opens(() -> Ui.tr("Limpar a TV"), this::openCleanup),
                    () -> Ui.tr("Desliga de uma vez demonstrações, telemetria e propaganda do fabricante, em três níveis. Dá para desfazer.")));
            p.rows.add(helped(opens(() -> Ui.tr("Botões do Controle"), () -> {
                HomeMode.ensureRemoteService(getContext(), 0);   // the buttons work from inside other apps through this service
                loadApps();
                open(keysPage());
            }), () -> Ui.tr("Escolhe o que cada botão de app do controle abre (Netflix, YouTube, Prime…), de qualquer app.")));
            return p;
        }

        /** How fast it runs, the updates, the switch's status and the TV's own settings. */
        private Page systemPage() {
            Page p = new Page(Ui.tr("Sistema"));
            p.symbol = "settings";
            p.rows.add(helped(opens(() -> Ui.tr("Desempenho"), () -> open(performancePage())),
                    () -> Ui.tr("Para TVs lentas: sem o efeito de vidro, animações rápidas, otimizar apps e serviços em segundo plano.")));
            p.rows.add(helped(opens(() -> Ui.tr("Atualizações"), () -> open(updatesPage())),
                    () -> Ui.tr("A versão instalada, procurar uma nova e atualizar sozinho.")));
            p.rows.add(helped(opens(() -> Ui.tr("Estado"), () -> go(STATUS, null)),
                    () -> Ui.tr("O que a última troca fez, os avisos e a memória e o tempo do Início em cada modo.")));
            p.rows.add(helped(opens(() -> Ui.tr("Ajustes da TV"), () -> HomeApps.openSettings(activity, Settings.ACTION_SETTINGS)),
                    () -> Ui.tr("Abre as configurações da própria TV (rede, imagem, som, contas).")));
            p.rows.add(helped(choice(() -> Ui.tr("Ao ligar a TV"), () -> list(Ui.tr("Início"), Ui.tr("TV ao vivo")),
                    () -> PowerOn.LIVE.equals(Prefs.powerOn()) ? 1 : 0,
                    at -> Prefs.setPowerOn(at == 1 ? PowerOn.LIVE : PowerOn.HOME)),
                    () -> Ui.tr("O que aparece quando a TV termina de ligar. TV ao vivo abre 10 segundos depois, se nenhum botão for apertado antes. Sair do modo de espera não muda nada.")));
            return p;
        }

        /** Each row's description on the left, in order (a null leaves that row with the page's). */
        @SafeVarargs
        private static void describe(Page p, java.util.function.Supplier<String>... texts) {
            for (int i = 0; i < texts.length && i < p.rows.size(); i++) {
                if (texts[i] != null) p.rows.get(i).help = texts[i];
            }
        }

        /** Language, theme, units, the top of the home screen, the shelf's art and the sounds. */
        private Page appearancePage() {
            Page p = new Page(Ui.tr("Aparência"));
            p.rows.add(choice(() -> Ui.tr("Idioma"), this::languageNames, () -> languageIndex(),
                    at -> Prefs.setLanguage(languageChoices()[at])));
            p.rows.add(choice(() -> Ui.tr("Tema"),
                    () -> list(Ui.tr("Automático"), Ui.tr("Claro"), Ui.tr("Escuro")),
                    () -> Math.max(0, java.util.Arrays.asList(THEMES).indexOf(Prefs.theme())), at -> Prefs.setTheme(THEMES[at])));
            p.rows.add(choice(() -> Ui.tr("Temperatura"), () -> list(Ui.tr("Automática"), "°C", "°F"),
                    () -> Math.max(0, java.util.Arrays.asList(UNITS).indexOf(Prefs.temperature())), at -> Prefs.setTemperature(UNITS[at])));
            p.rows.add(choice(() -> Ui.tr("Topo do Início"),
                    () -> list(Ui.tr("Relógio e clima"), Ui.tr("Cotações")),
                    () -> Prefs.topClock() ? 0 : 1, at -> Prefs.setTopClock(at == 0)));
            p.rows.add(choice(() -> Ui.tr("Capa dos apps na prateleira"),
                    () -> list(Ui.tr("Original do app"), Ui.tr("Logo")),
                    () -> Prefs.originalArt() ? 0 : 1, at -> Prefs.setOriginalArt(at == 0)));
            p.rows.add(choice(() -> Ui.tr("Volume dos sons"), SettingsView::soundLevels,
                    Prefs::soundLevel, Prefs::setSoundLevel));
            p.rows.add(opens(() -> Ui.tr("Relógio"), () -> open(clockPage())));
            p.symbol = "visibility";
            describe(p,
                    () -> Ui.tr("O idioma dos textos do Free My TV. Automático segue o da TV."),
                    () -> Ui.tr("Claro, escuro, ou automático: claro de dia e escuro à noite."),
                    () -> Ui.tr("Graus Celsius ou Fahrenheit no clima. Automático segue o país."),
                    () -> Ui.tr("O que fica no alto do Início: o relógio com o clima, ou as cotações."),
                    () -> Ui.tr("Na prateleira, a imagem que o próprio app traz ou só o logo dele."),
                    () -> Ui.tr("O volume do clique ao mover o foco e abrir itens."),
                    () -> Ui.tr("A hora do relógio: automática pela internet, ou fuso e ajuste à mão."));
            p.hint = () -> Ui.tr("OK abre as opções; ← e → mudam direto.");
            return p;
        }

        // MARK: - Prepare the TV: step by step, each step checked on the TV as it changes

        private boolean devOn, usbOn, wirelessOn, port5555, assistOn;
        private boolean adbOk, testingAdb;
        private String adbText = "";
        private Page preparePage;

        /** Read in the background by the poll, while a Prepare page is on screen. */
        private static boolean[] prepareChecks(Context c) {
            android.content.ContentResolver r = c.getContentResolver();
            String services = Settings.Secure.getString(r, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return new boolean[] {
                Settings.Global.getInt(r, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1,
                Settings.Global.getInt(r, Settings.Global.ADB_ENABLED, 0) == 1,
                Adb.wirelessOn(c),
                portOpen(5555),
                services != null && services.contains(HomeMode.PKG + "/" + HomeMode.PKG + ".PairingAssist"),
            };
        }

        private void applyChecks(boolean[] v) {
            devOn = v[0];
            usbOn = v[1];
            wirelessOn = v[2];
            port5555 = v[3];
            assistOn = v[4];
        }

        /** Only a TCP connection (no ADB message): it doesn't make the TV ask anything. */
        private static boolean portOpen(int port) {
            try (java.net.Socket s = new java.net.Socket()) {
                s.connect(new java.net.InetSocketAddress("127.0.0.1", port), 300);
                return true;
            } catch (java.io.IOException e) {
                return false;
            }
        }

        private static String done(boolean ok) {
            return ok ? Ui.tr("Pronto") : Ui.tr("Falta fazer");
        }

        void openPrepare() {
            if (preparePage == null) preparePage = preparePage();
            adbOk = adbOk || state.optBoolean("adbAuthorized");
            if (page != preparePage) open(preparePage);
            invalidate();
        }

        private Page preparePage() {
            Page p = new Page(Ui.tr("Preparar a TV"));
            p.symbol = "tv_settings";
            p.live = true;
            p.about = () -> Ui.tr("Seis passos, uma vez só. Cada um mostra à esquerda o que fazer e se confere sozinho.");
            Row dev = opens(() -> Ui.tr("1. Opções do desenvolvedor"),
                    () -> open(developerPage()));
            dev.help = () -> Ui.tr("Libera os ajustes que o Free My TV usa. Fica escondido até você apertar 7 vezes o número da versão da TV.");
            p.rows.add(withValue(dev, () -> done(devOn)));
            Row debug = opens(() -> Ui.tr("2. Depuração"), () -> open(debuggingPage()));
            debug.help = () -> Ui.tr("Deixa o Free My TV dar comandos à própria TV (trocar o Início, desligar apps), sem computador.");
            p.rows.add(withValue(debug, () -> done(port5555 || wirelessOn)));
            Row allow = new Row(() -> Ui.tr("3. Autorizar o Free My TV"),
                    () -> testingAdb || !adbText.isEmpty() && !adbOk ? adbText : done(adbOk), this::testAdb, null, null);
            allow.help = () -> Ui.tr("OK testa a conexão. Na primeira vez a TV pergunta “Permitir depuração?”: marque “Sempre permitir” e aceite.");
            p.rows.add(allow);
            Row home = new Row(() -> Ui.tr("4. Início do Free My TV"),
                    () -> busy() ? "" : freeActive() ? "Free My TV" : Ui.tr("Original"), this::openHomeChoice, null, null);
            home.help = () -> Ui.tr("Troca o Início e desliga uma lista curta de apps de propaganda (nunca desinstala). “Original” traz tudo de volta.");
            p.rows.add(home);
            Row clean = opens(() -> Ui.tr("5. Limpar a TV"), this::openCleanup);
            clean.help = () -> Ui.tr("Desliga demonstrações, telemetria e propaganda do fabricante, para sobrar memória. Dá para desfazer.");
            p.rows.add(withValue(clean, () -> {
                int n = HomeMode.stringList(state, "cleaned").size();
                return n == 0 ? Ui.tr("Opcional") : Ui.trf("{n} desligados", "n", n);
            }));
            Row key = opens(() -> Ui.tr("6. Recomendações (chave do TMDB)"),
                    () -> open(tmdbPage()));
            key.help = () -> Ui.tr("Os filmes e séries do Início e as fotos do protetor de tela vêm do TMDB, com uma chave grátis sua.");
            p.rows.add(withValue(key, () -> Prefs.tmdbKey().isEmpty() ? Ui.tr("Opcional") : done(true)));
            Row finish = new Row(() -> Ui.tr("Concluir"), () -> "", () -> {
                Prefs.setPrepared(true);
                goBack();
            }, null, null);
            finish.help = () -> Ui.tr("Volta às configurações. Este assistente fica em “Preparar a TV”.");
            p.rows.add(finish);
            return p;
        }

        /** The row with this value instead of a chevron (its OK still opens). */
        private static Row withValue(Row r, java.util.function.Supplier<String> value) {
            Row out = new Row(r.label, value, r.ok, r.adjust, r.dim);
            out.help = r.help;
            return out;
        }

        private static Row helped(Row r, java.util.function.Supplier<String> help) {
            r.help = help;
            return r;
        }

        private Page developerPage() {
            Page p = new Page(Ui.tr("Opções do desenvolvedor"));
            p.symbol = "settings";
            p.live = true;
            p.about = () -> Ui.tr("1. OK em “Abrir Sobre”.\n2. Desça até “Build” (ou “Versão do Android TV OS”) e aperte OK 7 vezes, até a TV dizer que você é desenvolvedor.\n3. Volte aqui com Voltar.");
            p.rows.add(new Row(() -> Ui.tr("Abrir Sobre"), () -> "",
                    () -> HomeApps.openSettings(activity, Settings.ACTION_DEVICE_INFO_SETTINGS), null, null));
            p.rows.add(new Row(() -> Ui.tr("Situação"),
                    () -> devOn ? Ui.tr("Ligadas") : Ui.tr("Ainda escondidas"), () -> {}, null, null));
            return p;
        }

        private Page debuggingPage() {
            Page p = new Page(Ui.tr("Depuração"));
            p.symbol = "control";
            p.live = true;
            p.about = () -> Ui.tr("Um dos dois basta. Comece pela depuração USB; se ela não abrir a conexão, use a depuração por Wi-Fi.");
            p.rows.add(helped(new Row(() -> Ui.tr("Depuração USB"),
                    () -> port5555 ? Ui.tr("Funcionando") : usbOn ? Ui.tr("Ligada, sem conexão")
                            : Ui.tr("Desligada"),
                    () -> HomeApps.openSettings(activity, Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS), null, null),
                    () -> Ui.tr("OK abre as Opções do desenvolvedor: ligue “Depuração USB” e volte. Na maioria das TVs isso já basta.")));
            if (AdbTls.supported()) {
                Row wireless = opens(() -> Ui.tr("Depuração por Wi-Fi"), () -> open(wirelessPage()));
                wireless.help = () -> Ui.tr("Para TVs em que a depuração USB não abre a conexão (Google TV novas): pareia com um código de 6 números.");
                p.rows.add(withValue(wireless, () -> wirelessOn ? Ui.tr("Ligada") : Ui.tr("Desligada")));
            }
            return p;
        }

        private Page wirelessPage() {
            Page p = new Page(Ui.tr("Depuração por Wi-Fi"));
            p.symbol = "wifi";
            p.live = true;
            p.rows.add(helped(new Row(() -> Ui.tr("1. Ligar o assistente de pareamento"),
                    () -> assistOn ? Ui.tr("Ligado") : Ui.tr("Desligado"),
                    () -> HomeApps.openSettings(activity, Settings.ACTION_ACCESSIBILITY_SETTINGS), null, null),
                    () -> Ui.tr("OK abre Acessibilidade: escolha “Free My TV: pareamento” e ligue. Ele só lê o código da tela de pareamento e se desliga depois.")));
            p.rows.add(helped(new Row(() -> Ui.tr("2. Ligar a depuração por Wi-Fi"),
                    () -> wirelessOn ? Ui.tr("Ligada") : Ui.tr("Desligada"),
                    () -> HomeApps.openSettings(activity, Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS), null, null),
                    () -> Ui.tr("OK abre as Opções do desenvolvedor: abra “Depuração por Wi-Fi”, ligue e aceite a pergunta da TV.")));
            p.rows.add(helped(new Row(() -> Ui.tr("3. Parear com código"),
                    () -> adbOk ? Ui.tr("Pareado") : "",
                    () -> HomeApps.openSettings(activity, Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS), null, null),
                    () -> Ui.tr("Em “Depuração por Wi-Fi”, escolha “Parear o dispositivo com um código de pareamento”. Com o assistente ligado, o Free My TV lê o código, pareia sozinho e volta para cá.")));
            return p;
        }

        /** Connects to the TV's ADB once (the TV may ask to allow it); on TCL also lets the switch run in the background. */
        private void testAdb() {
            if (testingAdb) return;
            testingAdb = true;
            adbText = Ui.tr("Conectando…");
            Context app = getContext().getApplicationContext();
            new Thread(() -> {
                boolean ok = false;
                String text;
                try (Adb adb = Adb.connect(app, 120_000, () -> handler.post(() -> {
                    adbText = Ui.tr("Aceite na TV");
                    invalidate();
                }))) {
                    adb.shell("echo ok");
                    // TCL blocks background jobs by default: the switch (ModeJob) would never run.
                    if (HomeMode.tcl()) HomeMode.allowAutoStart(adb, HomeMode.PKG);
                    JSONObject s = HomeMode.state(app);
                    s.put("adbAuthorized", true);
                    HomeMode.save(app, s);
                    ok = true;
                    text = "";
                } catch (Exception e) {
                    AppLog.w("prepare: " + e.getMessage());
                    text = Ui.tr("Não conectou");
                }
                boolean result = ok;
                String shown = text;
                handler.post(() -> {
                    testingAdb = false;
                    adbOk = result;
                    adbText = shown;
                    invalidate();
                });
            }, "prepare-adb").start();
        }

        private Page tmdbPage() {
            Page p = new Page(Ui.tr("Chave do TMDB"));
            p.symbol = "play";
            p.about = () -> Ui.tr("1. No celular ou computador, crie uma conta grátis em themoviedb.org.\n2. Em Settings → API, peça uma chave (uso pessoal) e copie a “API Key”.\n3. Aqui, OK em “Digitar a chave”. O app Google TV no celular serve de teclado e cola.");
            p.rows.add(new Row(() -> Ui.tr("Digitar a chave"), this::tmdbKeyState, activity::askTmdbKey, null, null));
            return p;
        }

        // MARK: - Cleanup (Debloat): the TV's list, a level, and app by app

        private Page cleanupPage;
        private List<Debloat.App> cleanupApps;
        private boolean analyzing;
        private String cleanupError = "";
        private int cleanLevel = Debloat.RECOMMENDED;
        private final Set<String> cleanAdd = new HashSet<>(), cleanSkip = new HashSet<>();

        private void openCleanup() {
            if (cleanupPage == null) {
                cleanupPage = new Page(Ui.tr("Limpar a TV"));
                cleanupPage.symbol = "clean";
                cleanupPage.rebuild = this::fillCleanup;
            }
            fillCleanup();
            open(cleanupPage);
            if (cleanupApps == null && !analyzing) analyzeCleanup();
        }

        private void analyzeCleanup() {
            if (analyzing) return;
            analyzing = true;
            cleanupError = "";
            fillCleanup();
            Context app = getContext().getApplicationContext();
            new Thread(() -> {
                List<Debloat.App> found = null;
                String error = "";
                try {
                    found = Debloat.analyze(app);
                } catch (Exception e) {
                    AppLog.w("cleanup analysis: " + e.getMessage());
                    error = String.valueOf(e.getMessage());
                }
                List<Debloat.App> result = found;
                String why = error;
                handler.post(() -> {
                    analyzing = false;
                    if (result != null) cleanupApps = result;
                    cleanupError = why;
                    fillCleanup();
                    invalidate();
                });
            }, "cleanup").start();
        }

        private boolean picked(Debloat.App a) {
            if (!a.on || !a.locked.isEmpty() || cleanSkip.contains(a.pkg)) return false;
            return cleanAdd.contains(a.pkg) || a.pickedBy(cleanLevel);
        }

        private List<String> pickedApps() {
            List<String> out = new ArrayList<>();
            if (cleanupApps != null) for (Debloat.App a : cleanupApps) if (picked(a)) out.add(a.pkg);
            return out;
        }

        private long pickedMb() {
            long mb = 0;
            if (cleanupApps != null) for (Debloat.App a : cleanupApps) if (picked(a)) mb += a.mb;
            return mb;
        }

        private static String levelName(int level) {
            switch (level) {
                case Debloat.LIGHT: return Ui.tr("Leve");
                case Debloat.COMPLETE: return Ui.tr("Completa");
                default: return Ui.tr("Recomendada");
            }
        }

        /** The page's rows from the last analysis (kept in place: the focus stays where it was). */
        private void fillCleanup() {
            Page p = cleanupPage;
            if (p == null) return;
            p.rows.clear();
            p.about = () -> Ui.tr("Desliga apps do fabricante que você não usa (nunca desinstala). Cada app é conferido antes: o que a TV precisa fica ligado.");
            if (analyzing || cleanupApps == null) {
                p.rows.add(new Row(() -> cleanupError.isEmpty() ? Ui.tr("Analisando os apps da TV…")
                        : Ui.tr("Não deu para analisar"),
                        () -> cleanupError.isEmpty() ? "" : Ui.tr("Tentar de novo"), this::analyzeCleanup, null, null));
                if (!cleanupError.isEmpty()) p.rows.get(0).help = () -> cleanupError
                        + Ui.tr("\n\nConfira os passos 2 e 3 de “Preparar a TV”.");
                return;
            }
            Row level = choice(() -> Ui.tr("Nível"),
                    () -> list(levelName(Debloat.LIGHT), levelName(Debloat.RECOMMENDED), levelName(Debloat.COMPLETE)),
                    () -> cleanLevel - 1, at -> cleanLevel = at + 1);
            level.help = () -> Ui.tr("Leve: demonstrações de loja, manuais, telemetria e anúncios.\nRecomendada: também canais de propaganda, lojas e contas do fabricante.\nCompleta: também extras que algumas pessoas usam (espelhar a tela, assistente de voz).");
            p.rows.add(level);
            Row apply = dimmed(new Row(() -> pickedApps().isEmpty() ? Ui.tr("Nada a desligar neste nível") : Ui.plural("plural|one:Desligar {n} app|other:Desligar {n} apps", pickedApps().size()),
                    () -> busy() ? HomeMode.busyText(state) : pickedMb() > 0 ? Ui.trf("~{mb} MB livres", "mb", pickedMb()) : "",
                    () -> {
                        List<String> off = pickedApps();
                        if (off.isEmpty()) return;
                        Debloat.request(getContext(), off, new ArrayList<>());
                        cleanAdd.clear();
                        cleanSkip.clear();
                    }, null, null), () -> busy() || pickedApps().isEmpty());
            apply.help = () -> Ui.tr("Desliga os apps marcados com “Vai desligar” abaixo. A memória é o que eles usam agora.");
            p.rows.add(apply);
            int cleaned = HomeMode.stringList(state, "cleaned").size();
            if (cleaned > 0) {
                Row undo = dimmed(new Row(() -> Ui.tr("Desfazer a limpeza"),
                        () -> String.valueOf(HomeMode.stringList(state, "cleaned").size()), () -> Debloat.requestUndo(getContext()), null, null), this::busy);
                undo.help = () -> Ui.tr("Liga de novo tudo o que a limpeza desligou.");
                p.rows.add(undo);
            }
            for (Debloat.App a : cleanupApps) p.rows.add(cleanupRow(a));
            if (page == p) focus = Math.min(focus, p.rows.size() - 1);
        }

        private Row cleanupRow(Debloat.App a) {
            Row r = new Row(() -> a.label, () -> {
                if (!a.on) return a.cleaned ? Ui.tr("Desligado pela limpeza") : Ui.tr("Desligado");
                if (!a.locked.isEmpty()) return Ui.tr("Protegido");
                return picked(a) ? Ui.tr("Vai desligar") : Ui.tr("Fica ligado");
            }, () -> {
                if (!a.on && a.cleaned && !busy()) Debloat.request(getContext(), new ArrayList<>(), Collections.singletonList(a.pkg));
                if (!a.on || !a.locked.isEmpty()) return;
                boolean now = picked(a);
                cleanAdd.remove(a.pkg);
                cleanSkip.remove(a.pkg);
                if (now) cleanSkip.add(a.pkg);
                else if (!a.pickedBy(cleanLevel)) cleanAdd.add(a.pkg);
            }, null, null);
            r.help = () -> {
                StringBuilder b = new StringBuilder(a.why);
                if (a.level == Debloat.SUGGESTED) b.append(Ui.tr("\n\nNão está na lista: só desliga se você marcar."));
                else b.append("\n\n").append(Ui.trf("Nível: {level}", "level", levelName(a.level)));
                if (!a.locked.isEmpty()) b.append("\n").append(Ui.trf("Fica ligado: {why}", "why", a.locked));
                if (a.mb > 0) b.append("\n").append(Ui.trf("Usando {mb} MB agora", "mb", a.mb));
                if (!a.on && a.cleaned) b.append("\n").append(Ui.tr("OK liga de novo."));
                b.append("\n").append(a.pkg);
                return b.toString();
            };
            return r;
        }

        // MARK: - Updates: the newest GitHub release (Updater), now or on its own

        private Updater.Release release;   // the newest, once read
        private boolean releaseRead, releaseFailed, readingRelease, updating;

        private Page updatesPage() {
            Page p = new Page(Ui.tr("Atualizações"));
            p.rows.add(new Row(() -> Ui.tr("Versão instalada"),
                    () -> Updater.installedName(getContext()), () -> {}, null, null));
            p.rows.add(new Row(() -> Ui.tr("Procurar atualização"), this::releaseState,
                    this::askUpdate, null, () -> updating));
            p.rows.add(onOff(() -> Ui.tr("Atualizar sozinho"), Prefs::autoUpdate,
                    Prefs::setAutoUpdate, null));
            p.hint = () -> focus == 2
                    ? Ui.tr("Procura a cada 6 horas e instala quando você sai do app.")
                    : Ui.tr("As versões novas vêm do GitHub do projeto.");
            p.hint2 = () -> Ui.tr("Ao atualizar, o app reinicia sozinho.");
            p.symbol = "sync";
            describe(p,
                    () -> Ui.tr("A versão do Free My TV nesta TV."),
                    () -> Ui.tr("Procura uma versão nova no GitHub do projeto; se houver, OK instala."),
                    () -> Ui.tr("Procura a cada 6 horas e instala quando você sai do app."));
            readRelease();
            return p;
        }

        private String releaseState() {
            if (updating) return Ui.tr("Atualizando…");
            if (!releaseRead) return "…";
            if (releaseFailed) return Ui.tr("Sem conexão: OK tenta de novo");
            if (release == null || release.code <= Updater.installedCode(getContext())) return Ui.tr("Em dia");
            return Ui.trf("{version} disponível", "version", release.version());
        }

        /** A newer release: asks, then installs it now. Otherwise OK reads GitHub again. */
        private void askUpdate() {
            if (updating) return;
            if (release == null || release.code <= Updater.installedCode(getContext())) {
                releaseRead = false;
                readRelease();
                return;
            }
            openPicker(Ui.trf("Atualizar para {version}? O app reinicia.", "version", release.version()),
                    list(Ui.tr("Atualizar"), Ui.tr("Cancelar")), 1, at -> {
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
            Page p = new Page(Ui.tr("Desempenho"));
            p.rows.add(choice(() -> Ui.tr("Modo desempenho"),
                    () -> list(Ui.tr("Desligado"), Ui.tr("Ligado (sem vidro)")),
                    () -> Prefs.performance() ? 1 : 0, at -> Prefs.setPerformance(at == 1)));
            p.rows.add(onOff(() -> Ui.tr("Animações rápidas"),
                    () -> HomeMode.fastAnimations(state), on -> HomeMode.requestFastAnimations(getContext(), on), null));
            p.rows.add(new Row(() -> Ui.tr("Otimizar apps"), this::optimizeState,
                    this::askOptimize, null, null));
            Row background = new Row(() -> Ui.tr("Serviços em segundo plano"),
                    () -> "", this::openBackgroundApps, null, null);
            background.opens = true;
            p.rows.add(background);
            p.hint = () -> Ui.tr("OK abre as opções; ← e → mudam direto.");
            p.symbol = "speed";
            describe(p,
                    () -> Ui.tr("Tira o efeito de vidro e as sombras: mais leve para TVs lentas."),
                    () -> Ui.tr("Janelas e transições da TV na metade do tempo. O modo Original volta como era."),
                    () -> Ui.tr("Compila o código mais usado dos apps para abrirem mais rápido. A TV fica lenta enquanto roda."),
                    () -> Ui.tr("Apps da TV que rodam escondidos e ocupam memória: você escolhe o que fica ligado."));
            readOptimize();
            return p;
        }

        private String optimizeState() {
            if (!optimizeRead) return "…";
            if (optimizeRunning) return Ui.tr("Otimizando…");
            if (optimizedAt == 0) return Ui.tr("Nunca");
            long days = (System.currentTimeMillis() - optimizedAt) / 86_400_000L;
            return days == 0 ? Ui.tr("Hoje") : Ui.plural("plural|one:Há {n} dia|other:Há {n} dias", (int) days);
        }

        private void askOptimize() {
            if (optimizeRunning) return;
            openPicker(Ui.tr("Otimizar os apps agora? A TV fica lenta por alguns minutos."),
                    list(Ui.tr("Otimizar"), Ui.tr("Cancelar")), 1, at -> {
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
            Page p = new Page(Ui.tr("Serviços em segundo plano"));
            p.rebuild = () -> {
                p.rows.clear();
                if (backgroundApps == null || backgroundApps.isEmpty()) {
                    p.rows.add(new Row(() -> backgroundApps == null ? Ui.tr("Lendo os serviços…")
                            : Ui.tr("Nenhum serviço para desligar."),
                            () -> "", () -> {}, null, () -> true));
                } else {
                    Row auto = new Row(() -> Ui.tr("Automático"), this::automaticText, this::applyAutomatic, null,
                            () -> automaticPlan()[0].isEmpty() && automaticPlan()[1].isEmpty());
                    p.rows.add(auto);
                    for (HomeMode.BackgroundApp app : backgroundApps) {
                        p.rows.add(new Row(() -> serviceText(app.pkg)[0], () -> backgroundValue(app),
                                () -> backgroundKeep.put(app.pkg, !keeps(app)), dir -> backgroundKeep.put(app.pkg, !keeps(app)), null));
                    }
                    p.rows.add(new Row(() -> Ui.tr("Aplicar"), this::backgroundChanges, this::askApplyBackground, null,
                            () -> backgroundPlan()[0].isEmpty() && backgroundPlan()[1].isEmpty()));
                }
                if (page == p && focus >= p.rows.size()) focus = p.rows.size() - 1;
            };
            // Row 0 is Automatic, then one per service, then Apply.
            p.hint = () -> backgroundApps != null && focus == 0 && !backgroundApps.isEmpty()
                    ? Ui.tr("Desliga o que não faz falta; mantém o essencial.")
                    : backgroundApps == null || focus < 1 || focus > backgroundApps.size()
                    ? Ui.tr("Nada é desinstalado: o modo Original liga todos de volta.")
                    : serviceText(backgroundApps.get(focus - 1).pkg)[1];
            p.hint2 = () -> Ui.tr("OK escolhe manter ou desligar; nada muda antes de Aplicar.");
            p.rebuild.run();
            backgroundPage = p;
            open(p);
            readBackgroundApps();
        }

        /** {name, what it does and what turning it off costs}, short enough for the hint's line. */
        private static String[] serviceText(String pkg) {
            switch (pkg) {
                case "com.google.android.katniss": return new String[] {Ui.tr("Google (busca e Assistente)"),
                        Ui.tr("Desligado, o microfone do controle e a busca param.")};
                case "com.google.android.speech.pumpkin": return new String[] {Ui.tr("Voz sem internet"),
                        Ui.tr("Comandos de voz sem internet, para o Assistente.")};
                case "com.google.android.backdrop": return new String[] {Ui.tr("Modo ambiente"),
                        Ui.tr("A tela de descanso de fotos do Google.")};
                case "com.google.android.tts": return new String[] {Ui.tr("Leitura em voz alta"),
                        Ui.tr("Lê textos em voz alta; o TalkBack usa.")};
                case "com.google.android.marvin.talkback": return new String[] {"TalkBack",
                        Ui.tr("O leitor de tela, para quem não enxerga bem.")};
                case "com.google.android.videos": return new String[] {Ui.tr("Google TV (filmes)"),
                        Ui.tr("Comprar e alugar filmes e séries do Google.")};
                case "com.android.gallery3d": return new String[] {Ui.tr("Galeria"),
                        Ui.tr("Fotos de um pendrive. A TV tem seu próprio leitor.")};
                case "com.google.android.music": return new String[] {"Google Play Music",
                        Ui.tr("Serviço encerrado pelo Google.")};
                case "com.google.android.youtube.tvmusic": return new String[] {"YouTube Music",
                        Ui.tr("Música do YouTube, num app à parte.")};
                case "com.google.android.play.games": return new String[] {"Play Games",
                        Ui.tr("Conquistas e perfis de jogos do Google.")};
                case "com.google.android.tvrecommendations":
                case "com.google.android.leanbacklauncher.recommendations": return new String[] {Ui.tr("Recomendações do Android TV"),
                        Ui.tr("Sugestões para a tela inicial original da TV.")};
                case "com.google.android.feedback": return new String[] {Ui.tr("Envio de feedback"),
                        Ui.tr("Manda opiniões e travamentos ao Google.")};
                case "com.google.android.tv.bugreportsender": return new String[] {Ui.tr("Relatórios de erro"),
                        Ui.tr("Manda relatórios de erro ao Google.")};
                case "com.android.printspooler": return new String[] {Ui.tr("Impressão"),
                        Ui.tr("Imprimir pela TV.")};
                case "com.google.android.syncadapters.calendar": return new String[] {Ui.tr("Sincronizar agenda"),
                        Ui.tr("Copia a agenda do Google para a TV.")};
                case "com.google.android.syncadapters.contacts": return new String[] {Ui.tr("Sincronizar contatos"),
                        Ui.tr("Copia os contatos do Google para a TV.")};
                default: return new String[] {pkg, ""};
            }
        }

        private boolean keeps(HomeMode.BackgroundApp app) {
            Boolean keep = backgroundKeep.get(app.pkg);
            return keep != null ? keep : app.enabled && app.keep;   // the ones already off stay off
        }

        private String backgroundValue(HomeMode.BackgroundApp app) {
            String mb = app.megabytes > 0 ? app.megabytes + " MB · " : "";
            if (!app.enabled) return mb + (keeps(app) ? Ui.tr("Ligar") : Ui.tr("Desligado"));
            return mb + (keeps(app) ? Ui.tr("Manter") : Ui.tr("Desligar"));
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
            return n == 0 ? Ui.tr("Já está leve")
                    : Ui.plural("plural|one:Desliga {n}|other:Desliga {n}", n);
        }

        private void applyAutomatic() {
            backgroundKeep.clear();   // the picks go back to the list's
            confirmBackground(automaticPlan());
        }

        private String backgroundChanges() {
            List<String>[] plan = backgroundPlan();
            int n = plan[0].size() + plan[1].size();
            return n == 0 ? Ui.tr("Nada a mudar")
                    : Ui.plural("plural|one:{n} mudança|other:{n} mudanças", n);
        }

        private void askApplyBackground() {
            confirmBackground(backgroundPlan());
        }

        private void confirmBackground(List<String>[] plan) {
            if (plan[0].isEmpty() && plan[1].isEmpty()) return;
            String question = Ui.trf("Desligar {off} e ligar {on}?",
                    "off", plan[0].size(), "on", plan[1].size());
            openPicker(question, list(Ui.tr("Aplicar"), Ui.tr("Cancelar")), 1, at -> {
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

        /** The picks' lists, in Prefs.LIST_* order. */
        private static List<String> recsListNames() {
            return list(Ui.tr("Original (5 por hora)"), Ui.tr("Top do dia"),
                    Ui.tr("Top da semana"), Ui.tr("Lançamentos"),
                    Ui.tr("Aclamados"));
        }

        /** The picks: which list, how big the top is, where they open, the TMDB key, and Continue Watching's apps. */
        private Page picksPage() {
            Page p = new Page(Ui.tr("Recomendações"));
            p.rows.add(choice(() -> Ui.tr("Lista"),
                    SettingsView::recsListNames, Prefs::recsList, Prefs::setRecsList));
            Row top = choice(() -> Ui.tr("Títulos na lista"), () -> {
                List<String> l = new ArrayList<>();
                for (int n = Prefs.TOP_MIN; n <= Prefs.TOP_MAX; n++) l.add(String.valueOf(n));
                return l;
            }, () -> Prefs.topCount() - Prefs.TOP_MIN, at -> Prefs.setTopCount(Prefs.TOP_MIN + at));
            p.rows.add(new Row(top.label, top.value, () -> { if (Prefs.topRecs()) top.ok.run(); },   // only the top has a size
                    dir -> { if (Prefs.topRecs()) top.adjust.accept(dir); }, () -> !Prefs.topRecs()));
            p.rows.add(choice(() -> Ui.tr("Buscar a lista de novo"),
                    () -> list(Ui.tr("A cada hora"), Ui.tr("A cada 3 horas"),
                            Ui.tr("A cada 6 horas"), Ui.tr("A cada 12 horas"),
                            Ui.tr("Uma vez por dia")),
                    Prefs::recsEveryIndex, Prefs::setRecsEveryIndex));
            p.rows.add(choice(() -> Ui.tr("Abrir recomendações em"), SettingsView::targetNames,
                    SettingsView::targetIndex, at -> Prefs.setRecsTarget(targetKeys()[at])));
            p.rows.add(new Row(() -> Ui.tr("Chave do TMDB"), this::tmdbKeyState, activity::askTmdbKey, null, null));
            Row watch = new Row(() -> Ui.tr("Continuar assistindo"),
                    SettingsView::watchAppsState, this::openWatchApps, null, null);
            p.rows.add(watch);
            p.hint = () -> Ui.tr("OK abre as opções; ← e → mudam direto.");
            p.hint2 = () -> Ui.tr("Usa a API do TMDB, sem endosso ou certificação do TMDB.");
            p.symbol = "play";
            describe(p,
                    () -> Ui.tr("Original: cinco títulos novos por hora. Top do dia e da semana: os mais vistos. Lançamentos: o que saiu nos últimos 2 meses. Aclamados: os mais bem avaliados dos últimos 3 anos."),
                    () -> Ui.tr("Quantos títulos a lista mostra (menos no Original, que mostra cinco)."),
                    () -> Ui.tr("De quanto em quanto tempo a lista é buscada de novo no TMDB. Mais vezes trazem títulos novos mais cedo."),
                    () -> Ui.tr("Onde um título abre quando você aperta OK nele."),
                    () -> Ui.tr("A sua chave grátis do TMDB: sem ela não há recomendações nem fotos na tela de descanso."),
                    () -> Ui.tr("Os apps cujos episódios e filmes começados aparecem no Início."));
            return p;
        }

        /** The drawer's widgets, each on or off (the row shares its width among the ones on), and their options. */
        private Page weatherPage() {
            Page p = new Page(Ui.tr("Clima, cotações e futebol"));
            p.rows.add(onOff(() -> Ui.tr("Widget do clima"), Prefs::weather, Prefs::setWeather, null));
            p.rows.add(new Row(() -> Ui.tr("Cidade do clima"), () -> cityStatus.isEmpty() ? Prefs.city() : cityStatus,
                    this::askCity, null, null));
            p.rows.add(onOff(() -> Ui.tr("Widget de cotações"), Prefs::quotes, Prefs::setQuotes, null));
            p.rows.add(onOff(() -> Ui.tr("Widget de futebol"),
                    Prefs::footballWidget, Prefs::setFootballWidget, null));
            // Football: the team (a list, or a search by name) and the extras.
            java.util.function.BooleanSupplier noFootball = () -> !Prefs.footballWidget();
            Row team = new Row(() -> Ui.tr("Time"),
                    () -> Prefs.footballTeamName().isEmpty() ? Ui.tr("Escolher") : Prefs.footballTeamName(),
                    this::openTeams, null, null);
            p.rows.add(dimmed(team, noFootball));
            p.rows.add(onOff(() -> Ui.tr("Avisos de gol e de início"),
                    Prefs::footballAlerts, Prefs::setFootballAlerts, noFootball));
            p.rows.add(onOff(() -> Ui.tr("Placar na tela de descanso"),
                    Prefs::footballInDream, Prefs::setFootballInDream, noFootball));
            Row every = choice(() -> Ui.tr("Atualizar cotações"), () -> list(Ui.tr("Tempo real"), "1 min", "5 min"),
                    Prefs::pricesEveryIndex, Prefs::setPricesEveryIndex);
            p.rows.add(dimmed(every, () -> !Prefs.quotes()));
            String[] coins = dollar() ? new String[] {"Bitcoin", "Ethereum", "Solana", Ui.tr("Dólar"), "Euro"}
                    : new String[] {"Bitcoin", "Ethereum", "Solana"};
            for (int i = 0; i < coins.length; i++) {
                int coin = i;
                String name = coins[i];
                // No quotes widget, nowhere to show prices: the coins read as off (and stay locked) until it is back on.
                p.rows.add(helped(onOff(() -> name, () -> Prefs.quotes() && Prefs.coin(coin), on -> Prefs.setCoin(coin, on),
                        () -> !Prefs.quotes()), () -> Ui.trf("Mostra a cotação de {coin} no cartão de cotações.", "coin", name)));
            }
            p.symbol = "partly_cloudy_day";
            describe(p,
                    () -> Ui.tr("O cartão do clima na gaveta do Início. Os widgets ligados dividem a linha entre si."),
                    () -> Ui.tr("A cidade da previsão (e do fuso do relógio automático)."),
                    () -> Ui.tr("O cartão das cotações: as moedas escolhidas abaixo, com a variação em 24 horas."),
                    () -> Ui.tr("O cartão do seu time: o jogo ao vivo ou o próximo; sem jogo perto, o último resultado e a posição."),
                    () -> Ui.tr("O time do cartão de futebol: da lista ou buscando pelo nome."),
                    () -> Ui.tr("Um aviso na tela quando o jogo do seu time começa e a cada gol."),
                    () -> Ui.tr("O placar ao vivo também na tela de descanso."),
                    () -> Ui.tr("De quanto em quanto tempo as cotações se atualizam."));
            p.hint = () -> Ui.tr("OK abre as opções; ← e → mudam direto.");
            return p;
        }

        /** The clocks' time: automatic (the weather city's zone and the time from the internet) or a zone and adjustment by hand. */
        private Page clockPage() {
            Page p = new Page(Ui.tr("Relógio"));
            p.rows.add(choice(() -> Ui.tr("Hora"),
                    () -> list(Ui.tr("Automática (internet)"),
                            Ui.tr("Manual")),
                    () -> Prefs.manualClock() ? 1 : 0, at -> Prefs.setManualClock(at == 1)));
            java.util.function.BooleanSupplier automatic = () -> !Prefs.manualClock();
            p.rows.add(dimmed(choice(() -> Ui.tr("Fuso horário"), SettingsView::zoneNames,
                    () -> automatic.getAsBoolean() ? nearest(Clock.ZONES, Clock.zoneMinutes()) : nearest(Clock.ZONES, Prefs.clockZone()),
                    at -> Prefs.setClockZone(Clock.ZONES[at])), automatic));
            p.rows.add(dimmed(choice(() -> Ui.tr("Ajuste da hora"), SettingsView::adjustNames,
                    () -> automatic.getAsBoolean() ? nearest(Clock.ADJUSTS, 0) : nearest(Clock.ADJUSTS, Prefs.clockAdjust()),
                    at -> Prefs.setClockAdjust(Clock.ADJUSTS[at])), automatic));
            p.rows.add(new Row(() -> Ui.tr("Agora"), () -> Ui.clock(System.currentTimeMillis()), () -> {}, null, null));
            p.symbol = "recents";
            describe(p,
                    () -> Ui.tr("Automática: a hora certa da internet no fuso da cidade do clima. Manual: você escolhe."),
                    () -> Ui.tr("O fuso do relógio, na hora manual."),
                    () -> Ui.tr("Minutos a mais ou a menos, se a hora ainda estiver errada."),
                    () -> Ui.tr("A hora que o Free My TV mostra agora."));
            p.hint = () -> Prefs.manualClock()
                    ? Ui.tr("Escolha o fuso e, se a hora ainda estiver errada, ajuste com ← e →.")
                    : Ui.tr("Usa o fuso da cidade do clima e a hora certa da internet.");
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
            Page p = new Page(Ui.tr("Tela de descanso"));
            p.rows.add(choice(() -> Ui.tr("Tela de descanso"),
                    () -> list(Ui.tr("Da TV (fotos do Google)"), "Free My TV"),
                    () -> Prefs.ownScreensaver() ? 1 : 0, at -> {
                        boolean own = at == 1;
                        if (own == Prefs.ownScreensaver()) return;
                        Prefs.setOwnScreensaver(own);
                        HomeMode.requestScreensaver(getContext(), own);   // the TV's setting changes in the main process
                    }));
            p.rows.add(new Row(() -> Ui.tr("Ver a do Free My TV"),
                    () -> Ui.tr("Ver agora"), () -> {
                        pause();
                        activity.showPreview();
                    }, null, null));
            p.symbol = "bedtime";
            describe(p,
                    () -> Ui.tr("No modo Free My TV: a da TV (fotos do Google) ou a do Free My TV, com relógio, clima e cartazes."),
                    () -> Ui.tr("Mostra a tela de descanso do Free My TV agora. Qualquer botão fecha."));
            return p;
        }

        /**
         * Remote Buttons: "Identify a button" (press any button on the remote, then pick what it opens), then every button
         * that does something now and what. Remotes differ between TVs (the same button can send another code), so the
         * buttons are told apart by what this TV receives, not by a brand's list of names.
         */
        private Page keysPage() {
            Page p = new Page(Ui.tr("Botões do Controle"));
            p.rebuild = () -> {
                p.rows.clear();
                p.rows.add(new Row(() -> learningKeys ? Ui.tr("Aperte o botão no controle…") : Ui.tr("Identificar um botão"),
                        () -> "", this::startLearningKeys, null, null));
                for (int key : Prefs.keysInUse()) {
                    // A brand default this remote never sent (another model's code) would only confuse: it shows once seen.
                    if (!Prefs.keyChosen(key) && !onRemote(key)) continue;
                    String label = keyLabel(key);
                    p.rows.add(new Row(() -> label, () -> targetName(key), () -> pickKeyTarget(key), null, null));
                }
                keepVisible(false);
            };
            p.symbol = "control";
            p.about = () -> Ui.tr("“Identificar um botão”: aperte o botão no controle e escolha o que ele abre. Os botões já escolhidos vêm abaixo.");
            p.hint = this::keysHint;
            p.hint2 = () -> learningKeys ? Ui.tr("Setas, OK, Voltar, Home, energia e volume não mudam. Voltar cancela.")
                    : Ui.tr("Em Nada, a TV volta a cuidar do botão.");
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
                keysError = Ui.tr("Nenhum botão foi apertado.");
            }
        }

        /** The choices for one button: an app, the Control Center, Recent apps or Nothing. */
        private void pickKeyTarget(int key) {
            openPicker(keyLabel(key), appLabels, appPkgs.indexOf(Prefs.keyApp(key)), at -> setKeyApp(key, appPkgs.get(at)));
        }

        private String targetName(int key) {
            int at = appPkgs.indexOf(Prefs.keyApp(key));
            return at >= 0 ? appLabels.get(at) : Ui.tr("Não instalado");
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
            return Ui.trf("Botão {name}", "name", name);
        }

        /** Saves a button's app; if it didn't save, says so; and makes sure the key service is on to use it. */
        private void setKeyApp(int key, String pkg) {
            keysError = Prefs.setKeyApp(key, pkg) ? ""
                    : Ui.tr("Não deu para salvar a escolha. Tente de novo.");
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
                return Ui.tr("Aperte o botão do controle que você quer configurar.");
            }
            if (!keysError.isEmpty()) return keysError;
            // The service runs only in Free My TV mode (Original turns it off on purpose).
            if (!keysServiceOn && RemoteKeys.supported() && HomeMode.FREE.equals(state.optString("mode"))) {
                return Ui.tr("O serviço dos botões está desligado. Ligando…");
            }
            long now = System.currentTimeMillis();
            for (int key : Prefs.keysInUse()) {
                if (now - Prefs.keySeenAt(key) > 15_000) continue;
                return Ui.trf("{button} funciona: abre o {app}.", "button", keyLabel(key), "app", targetName(key));
            }
            return Ui.tr("Identificar um botão: aperte qualquer botão do controle e escolha o que ele abre.");
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
            Row r = choice(label, () -> list(Ui.tr("Ligado"), Ui.tr("Desligado")), () -> on.getAsBoolean() ? 0 : 1,
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
            p.hint = () -> (focus + 1) + " / " + p.rows.size() + "  ·  " + Ui.tr("OK escolhe, Voltar cancela.");
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
                String name = row == 0 ? "Free My TV, " + Ui.tr("sem anúncios, com a tela de descanso da TV")
                        : "Original, " + Ui.tr("o Início de fábrica da TV");
                boolean inUse = (row == 0) == freeActive();
                return name + (inUse ? Ui.tr(". Em uso") : "") + ", " + Ui.trf("{n} de {total}", "n", row + 1, "total", 2);
            }
            if (page == null || row >= page.rows.size()) return "";
            Row r = page.rows.get(row);
            String value = page == main && row == HOME_ROW && busy() ? busyLine() : r.value.get();
            return r.label.get() + (value.isEmpty() ? "" : ", " + value) + (r.check ? Ui.tr(", escolhido") : "")
                    + ", " + Ui.trf("{n} de {total}", "n", row + 1, "total", page.rows.size());
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
            scene.setSize(w, h);
            glass.refract(lightTheme ? 0xFFF5F5F7 : 0xFF1C1C1E, lightTheme ? 0xFFE2E2E7 : 0xFF0B0B0D);
            glassShadow.setMaskFilter(new BlurMaskFilter(DockGlass.SHADOW_BLUR * k, BlurMaskFilter.Blur.NORMAL));
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
            aboutPaint = new TextPaint(Ui.text(Ui.regular, 26 * k, fg(0xB3FFFFFF)));
            aboutLayouts.clear();
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
            return text.isEmpty() ? Ui.tr("Começando…") : text;
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

            // On the left: the page's symbol, then what the focused row does (or the page's steps).
            float cx = 500 * k;
            boolean onPage = screen == PAGE && page != null;
            Symbols.draw(getContext(), c, onPage ? page.symbol : "sparkles", cx, 330 * k, 150 * k, fg(0xEBFFFFFF));
            float ty = 548 * k;
            if (!onPage || page == main) {
                c.drawText("Free My TV", cx, ty, title);
                ty += 58 * k;
                c.drawText(versionText(), cx, 1000 * k, version);
            }
            drawAbout(c, leftText(), cx, ty);

            if (screen == HOME_CHOICE) drawChoice(c);
            else if (screen == STATUS) drawStatus(c);
            else drawPage(c, seconds);
        }

        /** The focused row's description, else the page's, else what Free My TV is (main menu and Home Screen). */
        private String leftText() {
            if (screen == PAGE && page != null) {
                if (focus < page.rows.size() && page.rows.get(focus).help != null) return page.rows.get(focus).help.get();
                if (page.about != null) return page.about.get();
                if (page != main) return "";
            }
            return Ui.tr("Início e tela de descanso da TV");
        }

        private final java.util.HashMap<String, StaticLayout> aboutLayouts = new java.util.HashMap<>();

        /** A description on the left, centered under the symbol, wrapped (each text laid out once). */
        private void drawAbout(Canvas c, String text, float cx, float top) {
            if (text.isEmpty()) return;
            int width = Math.round(780 * k);
            StaticLayout layout = aboutLayouts.get(text);
            if (layout == null) {
                if (aboutLayouts.size() > 40) aboutLayouts.clear();
                layout = StaticLayout.Builder.obtain(text, 0, text.length(), aboutPaint, width)
                        .setAlignment(android.text.Layout.Alignment.ALIGN_CENTER).setLineSpacing(0, 1.18f)
                        .setMaxLines(11).setEllipsize(TextUtils.TruncateAt.END).build();
                aboutLayouts.put(text, layout);
            }
            c.save();
            c.translate(cx - width / 2f, top - layout.getLineBaseline(0) + Ui.cap(aboutPaint));
            layout.draw(c);
            c.restore();
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
         * The rows sit on one panel of the home screen's shelf glass (radius 30, as the Control Center's tiles), the
         * white focus pill on it. `top`/`bottom` in 1080p units.
         */
        private void drawListPanel(Canvas c, float top, float bottom) {
            panelRect.set((LIST_X - 22) * k, (top - 16) * k, (LIST_R + 22) * k, (bottom + 10) * k);
            glass.draw(c, panelRect, PANEL_RADIUS * k, lightTheme ? 0 : 1, k, 1);
        }

        private static final float PANEL_RADIUS = 30;   // ControlCenter.TILE_RADIUS
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
            if (page == main && i == HOME_ROW && busy()) {
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
                c.drawText(Ui.tr("Na TV: marque “Sempre permitir” e aperte OK."),
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
            c.drawText(Ui.tr("Estado"), x, (LIST_TOP - 24) * k, heading);
            float y = drawComparison(c, (LIST_TOP + 40) * k);
            y = drawRecs(c, y + 40 * k);
            drawNotices(c, y + 56 * k);
        }

        /** Home screen picks: status, and the credit TMDB asks for. */
        private float drawRecs(Canvas c, float y) {
            float x = LIST_X * k + 30 * k;
            c.drawText(Ui.tr("RECOMENDAÇÕES"), x, y, header);
            String line;
            String error = recs.optString("error");
            int count = recs.optInt("count");
            if (!hasKey) line = Ui.tr("Sem chave do TMDB: adicione em Recomendações → Chave do TMDB.");
            else if (!error.isEmpty()) line = Ui.trf("Erro: {error}.", "error", error);
            else if (count > 0 && Prefs.topRecs()) line = Ui.trf("Ligadas · {list}, {n} títulos.", "list", recsListNames().get(Prefs.recsList()), "n", count);
            else if (count > 0) line = Ui.trf("Ligadas · {n} títulos nos apps da TV, 5 novos a cada hora.", "n", count);
            else line = Ui.tr("Buscando os títulos…");
            c.drawText(TextUtils.ellipsize(line, notice, 790 * k, TextUtils.TruncateAt.END).toString(), x, y + 42 * k, notice);
            version.setTextAlign(Paint.Align.LEFT);
            c.drawText(Ui.tr("Usa a API do TMDB, sem endosso ou certificação do TMDB."), x, y + 78 * k, version);
            version.setTextAlign(Paint.Align.CENTER);
            return y + 78 * k;
        }

        private float drawComparison(Canvas c, float y) {
            float x = LIST_X * k + 30 * k, colA = LIST_R * k - 220 * k, colB = LIST_R * k - 30 * k;
            c.drawText(Ui.tr("COMPARAÇÃO"), x, y, header);
            cellValue.setColor(fg(0x73FFFFFF));
            c.drawText("Original", colA, y, cellValue);
            c.drawText("Free My TV", colB, y, cellValue);
            cellValue.setColor(fg(0xD9FFFFFF));
            JSONObject metrics = state.optJSONObject("metrics");
            JSONObject a = metrics == null ? null : metrics.optJSONObject(HomeMode.ORIGINAL);
            JSONObject b = metrics == null ? null : metrics.optJSONObject(HomeMode.FREE);
            String[][] rows = {{Ui.tr("Botão Início"), "homeMs"}, {Ui.tr("Memória livre"), "memAvailableMB"},
                    {Ui.tr("Memória do Início"), "homePssMB"}};
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
            c.drawText(Ui.tr("AVISOS"), x, y, header);
            JSONArray notices = state.optJSONArray("notices");
            int shown = 0;
            for (int i = 0; notices != null && i < notices.length() && shown < 5; i++) {
                JSONObject o = notices.optJSONObject(i);
                if (o == null) continue;
                String line = ago(o.optLong("at")) + " · " + o.optString("text");
                c.drawText(TextUtils.ellipsize(line, notice, 790 * k, TextUtils.TruncateAt.END).toString(), x, y + (42 + 38 * shown) * k, notice);
                shown++;
            }
            if (shown == 0) c.drawText(Ui.tr("Nenhum ainda."), x, y + 42 * k, notice);
        }

        /** Home screen choice: Free My TV or the original, with a check on the one in use. */
        private void drawChoice(Canvas c) {
            c.drawText(Ui.tr("Início"), LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading);
            String[][] options = {{"Free My TV", Ui.tr("Sem anúncios, com a tela de descanso da TV")},
                    {"Original", Ui.tr("O Início de fábrica da TV")}};
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
            c.drawText(Ui.tr("A troca leva uns segundos e a tela pisca nas medições."), LIST_X * k + 30 * k,
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
            if (minutes < 1) return Ui.tr("agora");
            if (minutes < 60) return Ui.trf("há {n} min", "n", minutes);
            if (minutes < 48 * 60) return Ui.trf("há {n} h", "n", minutes / 60);
            return Ui.trf("há {n} dias", "n", minutes / 1440);
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

        /** "auto", then the app's languages (Ui.LANGUAGES). */
        private static String[] languageChoices() {
            String[] all = Ui.LANGUAGES;
            String[] v = new String[all.length + 1];
            v[0] = "auto";
            System.arraycopy(all, 0, v, 1, all.length);
            return v;
        }

        private List<String> languageNames() {
            List<String> l = new ArrayList<>();
            for (String code : languageChoices()) l.add(code.equals("auto") ? Ui.tr("Automático") : Ui.languageName(code));
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
            if (Prefs.typedTmdbKey()) return Ui.tr("Configurada na TV");
            return Prefs.tmdbKey().isEmpty() ? Ui.tr("Nenhuma: OK para inserir")
                    : Ui.tr("Do config.properties");
        }

        /** The click's volume, from off to high (Prefs.soundLevel). */
        private static List<String> soundLevels() {
            return list(Ui.tr("Desligado"), Ui.tr("Baixo"), Ui.tr("Médio"),
                    Ui.tr("Alto"));
        }

        /** Continue watching: every app, or how many are kept out. */
        private static String watchAppsState() {
            int off = Prefs.watchNextOffCount();
            return off == 0 ? Ui.tr("Todos os apps")
                    : Ui.plural("plural|one:{n} app desligado|other:{n} apps desligados", off);
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
            names.add(Ui.tr("App de cada título"));
            for (String[] a : Recs.LINK_APPS) names.add(a[1]);
            return names;
        }

        private static int targetIndex() {
            return Math.max(0, java.util.Arrays.asList(targetKeys()).indexOf(Prefs.recsTarget()));
        }

        private String[] keyLabels() {
            if (RemoteKeys.STEM_NETFLIX) return new String[] {Ui.tr("Botão Netflix")};
            if (RemoteKeys.KTC) return new String[] {Ui.tr("Botão Tabii")};
            if (RemoteKeys.SONY) return new String[] {Ui.tr("Botão BRAVIA CORE"),
                    Ui.tr("Botão Netflix"), Ui.tr("Botão Disney+"),
                    Ui.tr("Botão Prime Video"), Ui.tr("Botão YouTube"),
                    Ui.tr("Botão Crunchyroll"), Ui.tr("Botão Google Play")};
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
            appLabels.add(RemoteKeys.SONY ? Ui.tr("Padrão da Sony") : Ui.tr("Nada"));
            // Free My TV's own, over any app.
            appPkgs.add(Prefs.OPEN_CONTROL);
            appLabels.add(Ui.tr("Central de controle"));
            appPkgs.add(Prefs.OPEN_RECENTS);
            appLabels.add(Ui.tr("Apps recentes"));
            appPkgs.add(Prefs.SLEEP_CYCLE);
            appLabels.add(Ui.tr("Timer de desligar"));
            for (String[] f : found) {
                appPkgs.add(f[0]);
                appLabels.add(f[1]);
            }
        }

        /** Weather city: type the name (the Google TV app on a phone types too); the matches follow, as a list. */
        private void askCity() {
            if (Ui.tr("Buscando…").equals(cityStatus)) return;
            android.widget.EditText field = new android.widget.EditText(activity);
            field.setSingleLine(true);
            field.setHint(Ui.tr("Digite o nome da cidade"));
            field.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
            android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(Ui.tr("Cidade do clima"))
                    .setView(field)
                    .setPositiveButton(Ui.tr("Buscar"), (d, which) -> searchCity(field.getText().toString()))
                    .setNegativeButton(Ui.tr("Cancelar"), null)
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
            cityStatus = Ui.tr("Buscando…");
            Page from = page;
            invalidate();
            new Thread(() -> {
                List<String[]> found;
                String error;
                try {
                    found = HomeData.findCity(name);
                    error = found.isEmpty() ? Ui.tr("Nenhuma cidade com esse nome.") : "";
                } catch (Exception e) {
                    found = new ArrayList<>();
                    error = Ui.tr("Não deu para buscar agora.");
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
                        openPicker(Ui.tr("Cidade do clima"), labels, -1, at -> {
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

        // MARK: - Football team: the main leagues' teams, or a search by name

        /** The leagues listed for a quick pick: Brazil's Série A and B and Europe's main leagues. */
        private static final String[] TEAM_LEAGUES = {"bra.1", "bra.2", "eng.1", "esp.1", "ita.1", "ger.1", "fra.1", "por.1"};
        private static final long TEAMS_FRESH_FOR = 7 * 24 * 3600_000L;
        private List<Football.Team> teamList;   // null until read
        private String teamsStatus = "";

        private void openTeams() {
            Page p = new Page(Ui.tr("Time"));
            p.rebuild = () -> fillTeams(p);
            fillTeams(p);
            p.hint = () -> Ui.tr("O time aparece no widget ao lado do clima, com o jogo ao vivo ou o próximo.");
            open(p);
            if (teamList == null) readTeams(p);
        }

        /** The search first, then every team of the listed leagues, by league; the team followed has a check. */
        private void fillTeams(Page p) {
            p.rows.clear();
            Row search = new Row(() -> teamsStatus.isEmpty() ? Ui.tr("Buscar pelo nome…") : teamsStatus,
                    () -> "", this::askTeam, null, null);
            p.rows.add(search);
            if (teamList == null) {
                p.rows.add(new Row(() -> Ui.tr("Lendo os times…"), () -> "", () -> {}, null, () -> true));
                return;
            }
            for (Football.Team t : teamList) {
                Row r = new Row(() -> t.name, () -> t.leagueName, () -> pickTeam(t), null, null);
                r.check = t.id.equals(Prefs.footballTeam());
                p.rows.add(r);
            }
        }

        /** The listed leagues' teams: from the cache (a week), else from the source, in the background. */
        private void readTeams(Page p) {
            new Thread(() -> {
                List<Football.Team> all = cachedTeams();
                if (all == null) {
                    all = new ArrayList<>();
                    for (String league : TEAM_LEAGUES) {
                        try {
                            all.addAll(FootballSource.get().teams(league));
                        } catch (Exception e) {
                            AppLog.w("football teams " + league + ": " + e.getMessage());
                        }
                    }
                    if (!all.isEmpty()) saveTeams(all);
                }
                List<Football.Team> result = all;
                handler.post(() -> {
                    teamList = result;
                    if (result.isEmpty()) teamsStatus = Ui.tr("Sem conexão: tente buscar pelo nome.");
                    if (page == p) {
                        fillTeams(p);
                        focus = Math.min(focus, p.rows.size() - 1);
                        snapPops();
                    }
                    invalidate();
                });
            }).start();
        }

        private List<Football.Team> cachedTeams() {
            android.content.SharedPreferences c = getContext().getSharedPreferences("football-teams", Context.MODE_PRIVATE);
            long at = c.getLong("at", 0), now = System.currentTimeMillis();
            if (now - at > TEAMS_FRESH_FOR || now < at || !Ui.language.equals(c.getString("language", ""))) return null;
            try {
                JSONArray a = new JSONArray(c.getString("teams", "[]"));
                List<Football.Team> out = new ArrayList<>();
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.getJSONObject(i);
                    Football.Team t = new Football.Team();
                    t.id = o.optString("id");
                    t.name = o.optString("name");
                    t.league = o.optString("league");
                    t.leagueName = o.optString("leagueName");
                    out.add(t);
                }
                return out.isEmpty() ? null : out;
            } catch (Exception e) {
                return null;
            }
        }

        private void saveTeams(List<Football.Team> teams) {
            try {
                JSONArray a = new JSONArray();
                for (Football.Team t : teams) {
                    a.put(new JSONObject().put("id", t.id).put("name", t.name).put("league", t.league).put("leagueName", t.leagueName));
                }
                getContext().getSharedPreferences("football-teams", Context.MODE_PRIVATE).edit().putString("teams", a.toString())
                        .putLong("at", System.currentTimeMillis()).putString("language", Ui.language).apply();
            } catch (Exception ignored) {
            }
        }

        private void pickTeam(Football.Team t) {
            Prefs.setFootballTeam(t.id, t.name, t.league);
            // Choosing a team means wanting it on the home screen.
            if (!Prefs.footballWidget()) Prefs.setFootballWidget(true);
            changed();
            goBack();
        }

        /** A team by name (any club the source knows: lower divisions, other countries). */
        private void askTeam() {
            if (Ui.tr("Buscando…").equals(teamsStatus)) return;
            android.widget.EditText field = new android.widget.EditText(activity);
            field.setSingleLine(true);
            field.setHint(Ui.tr("Digite o nome do time"));
            field.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
            android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(Ui.tr("Time"))
                    .setView(field)
                    .setPositiveButton(Ui.tr("Buscar"), (d, which) -> searchTeam(field.getText().toString()))
                    .setNegativeButton(Ui.tr("Cancelar"), null)
                    .create();
            field.setOnEditorActionListener((v, action, event) -> {
                dialog.dismiss();
                searchTeam(field.getText().toString());
                return true;
            });
            dialog.show();
        }

        private void searchTeam(String name) {
            if (name.trim().isEmpty()) return;
            teamsStatus = Ui.tr("Buscando…");
            Page from = page;
            invalidate();
            new Thread(() -> {
                List<Football.Team> found;
                String error;
                try {
                    found = FootballSource.get().search(name);
                    error = found.isEmpty() ? Ui.tr("Nenhum time com esse nome.") : "";
                } catch (Exception e) {
                    found = new ArrayList<>();
                    error = Ui.tr("Não deu para buscar agora.");
                    AppLog.w("team search: " + e.getMessage());
                }
                List<Football.Team> result = found;
                String message = error;
                handler.post(() -> {
                    teamsStatus = message;
                    if (!result.isEmpty() && screen == PAGE && page == from) {
                        List<String> labels = new ArrayList<>();
                        for (Football.Team t : result) labels.add(t.leagueName.isEmpty() ? t.name : t.name + "  ·  " + t.leagueName);
                        openPicker(Ui.tr("Time"), labels, -1, at -> pickTeam(result.get(at)));
                        spoken.focusMoved();
                    }
                    invalidate();
                    if (!message.isEmpty()) handler.postDelayed(() -> { teamsStatus = ""; invalidate(); }, 4000);
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
            Page p = new Page(Ui.tr("Apps da TV"));
            p.rows.add(tvAppsGroup(() -> Ui.tr("Apps instalados"), APPS_INSTALLED));
            p.rows.add(tvAppsGroup(() -> Ui.tr("Apps do sistema"), APPS_SYSTEM));
            p.rows.add(tvAppsGroup(() -> Ui.tr("Desligados"), APPS_OFF));
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
                    p.rows.add(new Row(() -> tvApps == null ? Ui.tr("Lendo os apps…") : Ui.tr("Nenhum."), () -> "", () -> {}, null, () -> true));
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
            if (asked != null) return asked ? Ui.tr("Ligando…") : Ui.tr("Desligando…");
            if (!app.enabled) return Ui.tr("Desligado");
            return app.locked.isEmpty() ? Ui.tr("Ligado") : Ui.tr("Protegido");
        }

        private String tvAppsHint() {
            if (!tvAppsStatus.isEmpty()) return tvAppsStatus;
            return Ui.tr("Nada é desinstalado: o modo Original liga todos os apps de volta.");
        }

        private void toggleTvApp(HomeMode.TvApp app, String name) {
            if (!app.locked.isEmpty()) {
                tvAppsStatus = Ui.trf("{app} fica como está: {reason}.", "app", name, "reason", app.locked);
                return;
            }
            if (tvAppsPending.containsKey(app.pkg)) return;
            if (app.enabled && app.system) {
                // A system app: asked first, it can be a piece the TV uses.
                openPicker(Ui.trf("Desligar {app}?", "app", name),
                        list(Ui.tr("Desligar"), Ui.tr("Cancelar")), 1, at -> { if (at == 0) askTvApp(app.pkg, false); });
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
                    error = Ui.trf("Não deu para ler os apps: {error}", "error", e.getMessage());
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
            Page p = new Page(Ui.tr("Prateleira"));
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
                        return place >= 0 ? "#" + (place + 1) : Ui.tr("Fora");
                    }, () -> toggleShelfApp(pkg), dir -> moveShelfApp(pkg, dir), () -> !shelfOrder.contains(pkg)));
                }
                for (int i = 0; focused != null && i < shelfRows.size(); i++) if (shelfRows.get(i)[0].equals(focused)) focus = i;
                keepVisible(false);
            };
            p.hint = () -> Ui.trf("OK põe ou tira da prateleira (até {n}). ← → mudam a ordem.", "n", HomeApps.MAX_SHELF);
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
            Page p = new Page(Ui.tr("Continuar assistindo"));
            for (String[] app : WatchNext.apps(getContext())) {
                String pkg = app[0];
                p.rows.add(new Row(() -> app[1], () -> onOffText(!Prefs.watchNextOff(pkg)),
                        () -> Prefs.setWatchNextOff(pkg, !Prefs.watchNextOff(pkg)),
                        dir -> Prefs.setWatchNextOff(pkg, !Prefs.watchNextOff(pkg)), () -> Prefs.watchNextOff(pkg)));
            }
            p.hint = () -> !WatchNext.allowed(getContext())
                    ? Ui.tr("Sem acesso à lista dos apps: troque o Início para Free My TV de novo.")
                    : p.rows.isEmpty()
                    ? Ui.tr("Nenhum app de vídeo instalado.")
                    : Ui.tr("OK liga ou desliga. Os apps de vídeo da TV e os que têm algo para continuar.");
            open(p);
        }

        private static String onOffText(boolean on) {
            return on ? Ui.tr("Ligado") : Ui.tr("Desligado");
        }
    }
}
