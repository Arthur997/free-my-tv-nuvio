package io.freemytv;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * The football screen, in the home screen's look (the shelf's glass, light by day, dark at night). Three tabs at the
 * top (TabBar), side by side:
 *  - Time: the team's match live or next (OK opens it), its last five results, where it stands in each competition;
 *  - Jogos: the season in every competition, live, next and played; any match opens;
 *  - Tabela: the competitions as chips, the table below with the team marked.
 * Any match opens its own screen over this one, with its own tabs: the summary (goals and cards on a timeline), the
 * numbers, the line-ups, the record (the last meetings and each team's last five) and the betting odds.
 * One way to move everywhere: ← → change the tab, ↓ goes into the tab, ↑ ↓ move, OK opens, Back goes back up (then
 * closes). A line at the bottom says what the keys do where the focus is. Each page is a layer redrawn only when its
 * data or its focus changes; changing tabs only moves them. Measurements in a 1920×1080 space.
 */
final class FootballView extends ViewGroup implements Choreographer.FrameCallback {
    private static final float M = 80, R = 1840, TITLE_BASE = 116, RADIUS = 30, ROW = 84;
    private static final int TEAM = 0, GAMES = 1, TABLE = 2, PAGES = 3, CHROME = 3, MATCH = 4;
    private static final int D_SUMMARY = 0, D_NUMBERS = 1, D_LINEUPS = 2, D_RECORD = 3, D_ODDS = 4;
    /** Where the focus is inside a tab: on the tabs, the Tabela's chips, or the tab's items. */
    private static final int ON_TABS = 0, ON_CHIPS = 1, ON_ITEMS = 2;
    private static final int FORM = 5, GAME_ROWS = 10, TABLE_ROWS = 19;
    private static final int INK = 0xFF1D1D1F, DIM = 0xFF6E6E73, WIN = 0xFF30D158, LOSS = 0xFFFF453A;
    private static final long LIVE_EVERY = 60_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Ui.background("football-screen");
    private final FootballSource source = FootballSource.get();
    private final String teamId = Prefs.footballTeam();
    private final Spring page = new Spring(0.34f, 0.92f, 0);
    private final TabBar tabs = new TabBar(Ui.tr("Time", "Team", "Equipo"), Ui.tr("Jogos", "Matches", "Partidos"),
            Ui.tr("Tabela", "Table", "Tabla"));
    private final TabBar matchTabs = new TabBar(Ui.tr("Resumo", "Summary", "Resumen"), Ui.tr("Números", "Numbers", "Números"),
            Ui.tr("Escalações", "Line-ups", "Alineaciones"), Ui.tr("Retrospecto", "Record", "Historial"), "Odds");
    private final Layer[] layers = new Layer[5];
    private final Scene scene = new Scene();
    private final Shadows shadows = new Shadows();
    private final Paint glassShadow = new Paint(Paint.ANTI_ALIAS_FLAG), pillShadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final DockGlass glass = new DockGlass(scene, shadows, glassShadow);
    private final Paint background = new Paint(), shade = new Paint(), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint image = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF rect = new RectF(), pill = new RectF();
    private boolean running, frameQueued, light = true, okDown, backDown;
    private long lastNanos;
    private int w, h;
    private float k = 1;

    // Where the focus is
    private int tab, zone = ON_TABS;
    private int teamItem;                  // Time: 0 = the match, 1… = the last results
    private int gameItem, gameFirst;       // Jogos: the focused row (in gameRows) and the first shown
    private int tableRow, tableFirst;      // Tabela: the focused row and the first shown
    private int league;                    // Tabela: the competition shown

    // The data, as it arrives (UI thread)
    private Football.Overview overview;
    private List<Football.Match> schedule;
    private final List<Football.Match> last = new ArrayList<>();
    /** Jogos: section names (String) and matches, in order. */
    private final List<Object> gameRows = new ArrayList<>();
    private final List<String> leagues = new ArrayList<>(), leagueNames = new ArrayList<>();
    private final Map<String, Football.Table> tables = new HashMap<>();
    private Football.Match next;
    private boolean failed, loadingTables;
    private String status = "";

    // The match screen (over the tabs)
    private Football.Match open;
    private Football.Details details;
    private Football.Odds odds;
    private int matchTab;
    private boolean loadingMatch;

    // Paints
    private Paint title, headerRight, panelTitle, big, name, text, dim, small, smallDim, pillText, numbers, numbersBold, tabText,
            tabOn, hint, chip, chipOn, oddsBig;

    private final Runnable minute = new Runnable() {
        @Override public void run() {
            if (!running) return;
            refreshLive();
            redraw();
            handler.postDelayed(this, LIVE_EVERY);
        }
    };

    FootballView(Activity a) {
        super(a);
        setFocusable(true);
        setWillNotDraw(false);
        for (int i = 0; i < layers.length; i++) {
            layers[i] = new Layer(a, i);
            if (i < PAGES) layers[i].setLayerType(Ui.LEGACY ? LAYER_TYPE_NONE : LAYER_TYPE_HARDWARE, null);
            layers[i].setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(layers[i]);
        }
        layers[MATCH].setVisibility(INVISIBLE);
        bringChildToFront(layers[CHROME]);   // the tabs over the match screen too
        // What the home card already knows, at once (its cache); the full data follows.
        FootballData cached = FootballData.get(a);
        if (cached.overview != null && cached.overview.team.id.equals(teamId)) overview = cached.overview;
    }

    // MARK: - Lifecycle and data

    void resume() {
        running = true;
        if (w > 0) {
            boolean was = light;
            light = !HomeData.get(getContext()).isNight(System.currentTimeMillis());
            if (was != light) makeTheme();
        }
        if (schedule == null) load();
        redraw();
        handler.postDelayed(minute, LIVE_EVERY);
    }

    void pause() {
        running = false;
        handler.removeCallbacks(minute);
        Choreographer.getInstance().removeFrameCallback(this);
        frameQueued = false;
    }

    /** The team first (light), then its season (heavier), then the tables of its competitions, one by one. */
    private void load() {
        status = Ui.tr("Carregando…", "Loading…", "Cargando…");
        io.execute(() -> {
            try {
                Football.Overview o = source.overview(teamId);
                handler.post(() -> {
                    overview = o;
                    redraw();
                });
                List<Football.Match> s = source.schedule(teamId);
                handler.post(() -> {
                    seasonArrived(s);
                    loadTables();
                });
            } catch (Exception e) {
                AppLog.w("football screen: " + e.getMessage());
                handler.post(() -> {
                    failed = true;
                    status = Ui.tr("Sem conexão agora.", "No connection right now.", "Sin conexión ahora.");
                    redraw();
                });
            }
        });
    }

    /** The season: the last results, the Jogos list in sections, and the competitions (the main one first). */
    private void seasonArrived(List<Football.Match> s) {
        schedule = s;
        status = "";
        last.clear();
        for (int i = s.size() - 1; i >= 0 && last.size() < FORM; i--) if (s.get(i).over()) last.add(s.get(i));
        List<Football.Match> live = new ArrayList<>(), coming = new ArrayList<>(), played = new ArrayList<>();
        for (Football.Match m : s) {
            if (m.live()) live.add(m);
            else if (m.over()) played.add(0, m);   // newest first
            else coming.add(m);
        }
        next = coming.isEmpty() ? null : coming.get(0);
        gameRows.clear();
        section(Ui.tr("Ao vivo", "Live", "En vivo"), live);
        section(Ui.tr("Próximos", "Next", "Próximos"), coming);
        section(Ui.tr("Anteriores", "Played", "Anteriores"), played);
        gameItem = firstMatchRow();
        leagues.clear();
        leagueNames.clear();
        String main = overview != null && !overview.team.league.isEmpty() ? overview.team.league : Prefs.footballLeague();
        if (!main.isEmpty()) addLeague(main, overview != null ? overview.team.leagueName : "");
        for (int i = s.size() - 1; i >= 0; i--) {   // the other competitions, the most recent first; friendlies have no table
            Football.Match m = s.get(i);
            if (!m.league.isEmpty() && !m.league.contains("friendly")) addLeague(m.league, m.leagueName);
        }
        redraw();
    }

    private void section(String label, List<Football.Match> matches) {
        if (matches.isEmpty()) return;
        gameRows.add(label);
        gameRows.addAll(matches);
    }

    private int firstMatchRow() {
        for (int i = 0; i < gameRows.size(); i++) if (gameRows.get(i) instanceof Football.Match) return i;
        return 0;
    }

    private void addLeague(String slug, String label) {
        if (leagues.contains(slug)) return;
        leagues.add(slug);
        leagueNames.add(label);
    }

    /** Every competition's table, one after the other (they also give the Time tab its positions). */
    private void loadTables() {
        if (loadingTables || leagues.isEmpty()) return;
        loadingTables = true;
        List<String> all = new ArrayList<>(leagues);
        io.execute(() -> {
            for (String slug : all) {
                Football.Table t = null;
                try {
                    t = source.table(slug);
                } catch (Exception e) {
                    AppLog.w("football table " + slug + ": " + e.getMessage());
                }
                Football.Table result = t;
                handler.post(() -> {
                    tables.put(slug, result);   // null: none (a cup's knockout stage)
                    layers[TEAM].invalidate();
                    layers[TABLE].invalidate();
                });
            }
            handler.post(() -> loadingTables = false);
        });
    }

    /** The match screen's data: the details (with the record) and the odds, side by side. */
    private void loadMatch(Football.Match m) {
        loadingMatch = true;
        io.execute(() -> {
            Football.Details d = null;
            Football.Odds o = null;
            try {
                d = source.details(m.league, m.id);
            } catch (Exception e) {
                AppLog.w("football match: " + e.getMessage());
            }
            try {
                o = source.odds(m.league, m.id);
            } catch (Exception e) {
                AppLog.w("football odds: " + e.getMessage());
            }
            Football.Details details = d;
            Football.Odds odds = o;
            handler.post(() -> {
                if (open != m) return;   // closed or another one opened meanwhile
                this.details = details;
                if (odds != null) this.odds = odds;
                loadingMatch = false;
                layers[MATCH].invalidate();
            });
        });
    }

    /** While a match is live: the team's card, and the open match's details, every minute (not the season). */
    private void refreshLive() {
        Football.Match o = open;
        if (o != null && (o.live() || details != null && details.match != null && details.match.live())) loadMatch(o);
        Football.Overview ov = overview;
        if (ov == null || ov.match == null || !ov.match.live()) return;
        io.execute(() -> {
            try {
                Football.Overview fresh = source.overview(teamId);
                handler.post(() -> {
                    overview = fresh;
                    redraw();
                });
            } catch (Exception e) {
                AppLog.w("football live: " + e.getMessage());
            }
        });
    }

    // MARK: - Keys

    boolean onKey(KeyEvent e) {
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) sideways(e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) vertical(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down) vertical(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (down) {
                    if (e.getRepeatCount() == 0) okDown = true;
                } else {
                    if (okDown && !e.isCanceled()) ok();
                    okDown = false;
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (open == null && zone == ON_TABS && tab == TEAM) return false;   // the activity closes
                if (down) {
                    if (e.getRepeatCount() == 0) backDown = true;
                } else {
                    if (backDown && !e.isCanceled()) back();
                    backDown = false;
                }
                return true;
            default:
                return false;
        }
    }

    /** ← →: the match screen's tabs, the main tabs, or the Tabela's competitions. */
    private void sideways(int dx) {
        if (open != null) {
            int to = matchTab + dx;
            if (to < 0 || to >= matchTabs.count()) return;
            matchTab = to;
            focusMoved();
            layers[MATCH].invalidate();
            return;
        }
        if (zone == ON_TABS) {
            int to = tab + dx;
            if (to < 0 || to >= PAGES) return;
            tab = to;
            page.target = to;
            wake();
            focusMoved();
            announceForAccessibility(tabs.label(to));
        } else if (zone == ON_CHIPS && leagues.size() > 1) {
            int to = league + dx;
            if (to < 0 || to >= leagues.size()) return;
            league = to;
            tableRow = teamRow();
            tableFirst = firstShown(tableRow, rows().size(), TABLE_ROWS);
            focusMoved();
        }
    }

    /** ↓ ↑: into the tab, between its items, and back up to the tabs from the first one. */
    private void vertical(int dy) {
        if (open != null) return;   // the match screen has only its tabs
        if (zone == ON_TABS) {
            if (dy < 0) return;
            if (tab == TEAM) {
                teamItem = teamMatch() != null ? 0 : 1;
                if (teamItem > last.size()) return;
                zone = ON_ITEMS;
            } else if (tab == GAMES) {
                if (gameRows.isEmpty()) return;
                gameItem = firstMatchRow();
                gameFirst = 0;
                zone = ON_ITEMS;
            } else {
                if (leagues.isEmpty()) return;
                zone = ON_CHIPS;
            }
            focusMoved();
            return;
        }
        if (tab == TEAM) {
            int to = teamItem + dy;
            if (to < (teamMatch() != null ? 0 : 1)) zone = ON_TABS;
            else if (to <= last.size()) teamItem = to;
            else return;
        } else if (tab == GAMES) {
            int to = gameItem + dy;
            while (to >= 0 && to < gameRows.size() && !(gameRows.get(to) instanceof Football.Match)) to += dy;   // over section names
            if (to < 0 || to >= gameRows.size()) {
                if (dy < 0) zone = ON_TABS;
                else return;
            } else {
                gameItem = to;
                gameFirst = firstShown(gameItem, gameRows.size(), GAME_ROWS, gameFirst);
            }
        } else if (zone == ON_CHIPS) {
            if (dy < 0) zone = ON_TABS;
            else if (!rows().isEmpty()) {
                zone = ON_ITEMS;
                tableRow = teamRow();
                tableFirst = firstShown(tableRow, rows().size(), TABLE_ROWS);
            } else return;
        } else {
            int to = tableRow + dy;
            if (to < 0) zone = ON_CHIPS;
            else if (to < rows().size()) {
                tableRow = to;
                tableFirst = firstShown(tableRow, rows().size(), TABLE_ROWS, tableFirst);
            } else return;
        }
        focusMoved();
    }

    private void ok() {
        if (open != null) return;
        if (zone == ON_TABS) {
            vertical(1);   // OK on a tab: into it
            return;
        }
        Football.Match m = null;
        if (tab == TEAM && zone == ON_ITEMS) m = teamItem == 0 ? teamMatch() : teamItem - 1 < last.size() ? last.get(teamItem - 1) : null;
        else if (tab == GAMES && zone == ON_ITEMS && gameItem < gameRows.size() && gameRows.get(gameItem) instanceof Football.Match) {
            m = (Football.Match) gameRows.get(gameItem);
        }
        if (m != null) openMatch(m);
    }

    private void back() {
        if (open != null) {
            open = null;
            details = null;
            odds = null;
            layers[MATCH].setVisibility(INVISIBLE);
        } else if (zone == ON_ITEMS && tab == TABLE) {
            zone = ON_CHIPS;
        } else if (zone != ON_TABS) {
            zone = ON_TABS;
        } else {
            tab = TEAM;
            page.target = TEAM;
            wake();
        }
        focusMoved();
    }

    private void openMatch(Football.Match m) {
        open = m;
        details = null;
        odds = null;
        // A match not played yet has no summary or numbers: its record and odds first.
        matchTab = Football.PRE.equals(m.state) ? D_RECORD : D_SUMMARY;
        layers[MATCH].setVisibility(VISIBLE);
        layers[MATCH].invalidate();
        focusMoved();
        loadMatch(m);
        announceForAccessibility(m.home.label() + " × " + m.away.label());
    }

    /** The first row to show so `focus` is in a window of `shown` rows, centered when it can be. */
    private static int firstShown(int focus, int total, int shown) {
        return Math.max(0, Math.min(total - shown, focus - shown / 2));
    }

    /** Scrolled only as much as needed to keep `focus` in the window starting at `first`. */
    private static int firstShown(int focus, int total, int shown, int first) {
        if (focus < first) first = focus;
        else if (focus >= first + shown) first = focus - shown + 1;
        return Math.max(0, Math.min(Math.max(0, total - shown), first));
    }

    private void focusMoved() {
        for (Layer l : layers) l.invalidate();
    }

    /** What the keys do where the focus is. */
    private String hint() {
        if (open != null) return Ui.tr("← → troca de aba  ·  Voltar fecha o jogo", "← → change tabs  ·  Back closes the match",
                "← → cambia de pestaña  ·  Volver cierra el partido");
        if (zone == ON_TABS) return tab == TEAM ? Ui.tr("← → troca de aba  ·  ↓ entra  ·  Voltar sai", "← → change tabs  ·  ↓ go in  ·  Back exits",
                "← → cambia de pestaña  ·  ↓ entra  ·  Volver sale")
                : Ui.tr("← → troca de aba  ·  ↓ entra", "← → change tabs  ·  ↓ go in", "← → cambia de pestaña  ·  ↓ entra");
        if (zone == ON_CHIPS) return Ui.tr("← → troca o campeonato  ·  ↓ desce na tabela", "← → change competition  ·  ↓ into the table",
                "← → cambia de torneo  ·  ↓ baja a la tabla");
        if (tab == TABLE) return Ui.tr("↑ ↓ percorre a tabela  ·  Voltar volta", "↑ ↓ move through the table  ·  Back returns",
                "↑ ↓ recorre la tabla  ·  Volver regresa");
        return Ui.tr("↑ ↓ escolhe o jogo  ·  OK abre o jogo  ·  Voltar volta às abas", "↑ ↓ pick a match  ·  OK opens it  ·  Back returns",
                "↑ ↓ elige el partido  ·  OK lo abre  ·  Volver regresa");
    }

    // MARK: - Frames: only while the tabs slide

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
        if (page.step(dt)) {
            frameQueued = true;
            Choreographer.getInstance().postFrameCallback(this);
        }
        place();
    }

    // MARK: - Size, layers and theme

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec);
        for (Layer l : layers) {
            l.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
        }
        setMeasuredDimension(width, height);
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        for (Layer layer : layers) layer.layout(0, 0, r - l, b - t);
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        w = width;
        h = height;
        k = w / 1920f;
        light = !HomeData.get(getContext()).isNight(System.currentTimeMillis());
        scene.setSize(w, h);
        makeTheme();
        place();
    }

    /** The tabs' pages side by side, where the tab has them (moved, not redrawn). */
    private void place() {
        for (int i = 0; i < PAGES; i++) layers[i].setTranslationX((i - page.value) * w);
    }

    private void redraw() {
        for (Layer l : layers) l.invalidate();
    }

    private int ink(float a) {
        return Ui.alpha(light ? INK : 0xFFFFFFFF, a);
    }

    private int dimInk() {
        return light ? DIM : 0xADFFFFFF;
    }

    private void makeTheme() {
        int top = light ? 0xFFF5F5F7 : 0xFF1C1C1E, bottom = light ? 0xFFE2E2E7 : 0xFF0B0B0D;
        background.setShader(new LinearGradient(0, 0, 0, h, top, bottom, Shader.TileMode.CLAMP));
        shade.setShader(new RadialGradient(w * 0.5f, h * 0.42f, Math.max(w, h) * 0.75f, 0x00000000,
                light ? 0x0F000000 : 0x33000000, Shader.TileMode.CLAMP));
        glass.refract(top, bottom);
        glassShadow.setMaskFilter(new BlurMaskFilter(DockGlass.SHADOW_BLUR * k, BlurMaskFilter.Blur.NORMAL));
        pillShadow.setMaskFilter(new BlurMaskFilter(20 * k, BlurMaskFilter.Blur.NORMAL));
        title = Ui.text(Ui.displaySemibold, 50 * k, ink(1));
        headerRight = Ui.text(Ui.medium, 24 * k, dimInk());
        headerRight.setTextAlign(Paint.Align.RIGHT);
        panelTitle = Ui.text(Ui.semibold, 23 * k, dimInk());
        big = Ui.numbers(Ui.displayMedium, 92 * k, ink(1));
        big.setTextAlign(Paint.Align.CENTER);
        oddsBig = Ui.numbers(Ui.displayMedium, 72 * k, ink(1));
        oddsBig.setTextAlign(Paint.Align.CENTER);
        name = Ui.text(Ui.medium, 30 * k, ink(0.95f));
        name.setTextAlign(Paint.Align.CENTER);
        text = Ui.text(Ui.medium, 27 * k, ink(0.95f));
        dim = Ui.text(Ui.medium, 24 * k, dimInk());
        small = Ui.numbers(Ui.medium, 24 * k, ink(0.95f));
        smallDim = Ui.numbers(Ui.medium, 22 * k, dimInk());
        pillText = Ui.text(Ui.semibold, 20 * k, 0xFFFFFFFF);
        pillText.setTextAlign(Paint.Align.CENTER);
        numbers = Ui.numbers(Ui.medium, 24 * k, ink(0.9f));
        numbers.setTextAlign(Paint.Align.RIGHT);
        numbersBold = Ui.numbers(Ui.semibold, 24 * k, ink(1));
        numbersBold.setTextAlign(Paint.Align.RIGHT);
        tabText = Ui.text(Ui.medium, 28 * k, dimInk());
        tabOn = Ui.text(Ui.semibold, 28 * k, ink(1));
        hint = Ui.text(Ui.medium, 21 * k, dimInk());
        hint.setTextAlign(Paint.Align.CENTER);
        chip = Ui.text(Ui.medium, 24 * k, dimInk());
        chipOn = Ui.text(Ui.semibold, 24 * k, ink(1));
        float x = M * k + title.measureText(headerTitle()) + 40 * k;
        tabs.layout(x, 98 * k, k, tabOn);
        matchTabs.layout(M * k, 98 * k, k, tabOn);
    }

    private String headerTitle() {
        return overview != null ? overview.team.name : Prefs.footballTeamName();
    }

    /** One layer: a tab's page, the title and tabs (still), or the match screen. */
    private final class Layer extends View {
        private final int kind;

        Layer(Context c, int kind) {
            super(c);
            this.kind = kind;
        }

        @Override protected void onDraw(Canvas c) {
            if (w == 0 || title == null) return;
            switch (kind) {
                case TEAM: drawTeam(c); break;
                case GAMES: drawGames(c); break;
                case TABLE: drawTable(c); break;
                case CHROME: drawChrome(c); break;
                default: drawMatch(c); break;
            }
        }
    }

    @Override protected void onDraw(Canvas c) {
        if (w == 0) return;
        c.drawRect(0, 0, w, h, background);
        c.drawRect(0, 0, w, h, shade);
    }

    // MARK: - Drawing helpers

    private void panel(Canvas c, float left, float top, float right, float bottom) {
        rect.set(left * k, top * k, right * k, bottom * k);
        glass.draw(c, rect, RADIUS * k, light ? 0 : 1, k, 1);
    }

    /** The focus: a white pill with a soft shadow (the text on it turns dark). */
    private void focusPill(Canvas c, float left, float top, float right, float bottom) {
        pill.set(left, top, right, bottom);
        float r = Math.min(24 * k, pill.height() / 2);
        rect.set(pill);
        rect.offset(0, 6 * k);
        pillShadow.setColor(light ? 0x29000000 : 0x66000000);
        shadows.draw(c, rect, r, 20 * k, pillShadow);
        fill.setShader(null);
        fill.setColor(light ? 0xFFFFFFFF : 0xFFF5F5F7);
        c.drawRoundRect(pill, r, r, fill);
    }

    private void logo(Canvas c, Football.Team t, float cx, float cy, float size) {
        Bitmap b = FootballLogos.get(getContext(), t.id, this::redraw);
        rect.set(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2);
        if (b != null) {
            c.drawBitmap(b, null, rect, image);
            return;
        }
        fill.setShader(null);
        fill.setColor(ink(0.1f));
        c.drawCircle(cx, cy, size / 2, fill);
        if (size < 40 * k || t.abbreviation.isEmpty()) return;   // no logo (yet): the team's initials in its place
        smallDim.setTextAlign(Paint.Align.CENTER);
        c.drawText(t.abbreviation, cx, cy + Ui.halfCap(smallDim), smallDim);
        smallDim.setTextAlign(Paint.Align.LEFT);
    }

    /** "AO VIVO 67'" in a red pill, "Encerrado" or the time in gray, right-aligned at `right`. */
    private void stateBadge(Canvas c, Football.Match m, float right, float mid) {
        String state = FootballText.state(m);
        if (m.live()) {
            String s = state.toUpperCase(Ui.locale());
            float tw = pillText.measureText(s);
            rect.set(right - tw - 30 * k, mid - 18 * k, right, mid + 18 * k);
            fill.setShader(null);
            fill.setColor(light ? 0xFFD70015 : LOSS);
            c.drawRoundRect(rect, 18 * k, 18 * k, fill);
            c.drawText(s, rect.centerX(), mid + Ui.halfCap(pillText), pillText);
        } else {
            dim.setTextAlign(Paint.Align.RIGHT);
            c.drawText(state, right, mid + Ui.halfCap(dim), dim);
            dim.setTextAlign(Paint.Align.LEFT);
        }
    }

    /** V (green), E (gray) or D (red) in a small square; the team's view of a match that's over. */
    private void outcome(Canvas c, String code, float x, float mid) {
        int color = "W".equals(code) ? (light ? 0xFF248A3D : WIN) : "L".equals(code) ? (light ? 0xFFD70015 : LOSS) : ink(0.35f);
        rect.set(x, mid - 20 * k, x + 40 * k, mid + 20 * k);
        fill.setShader(null);
        fill.setColor(color);
        c.drawRoundRect(rect, 10 * k, 10 * k, fill);
        c.drawText(FootballText.outcome(code), rect.centerX(), mid + Ui.halfCap(pillText), pillText);
    }

    /** The match from `teamId`'s side: "2 × 1" with its goals first, or the time before it starts. */
    private String scoreFor(Football.Match m, String team) {
        if (m.homeScore < 0 || m.awayScore < 0) return Ui.time(m.at);
        boolean home = m.home.id.equals(team);
        return (home ? m.homeScore : m.awayScore) + " × " + (home ? m.awayScore : m.homeScore);
    }

    private static String side(Football.Match m, String team) {
        return m.home.id.equals(team) ? Ui.tr("em casa", "home", "en casa") : Ui.tr("fora", "away", "fuera");
    }

    private void loading(Canvas c, float x, float y) {
        c.drawText(failed ? status : Ui.tr("Carregando…", "Loading…", "Cargando…"), x, y, dim);
    }

    // MARK: - The title, the tabs and the keys' line (still)

    private void drawChrome(Canvas c) {
        if (open != null) {
            matchTabs.draw(c, glass, shadows, light, k, matchTab, true, tabText, tabOn, INK);
            c.drawText(open.leagueName, R * k, TITLE_BASE * k, headerRight);
        } else {
            String t = headerTitle();
            c.drawText(t, M * k - Ui.leftBearing(title, t), TITLE_BASE * k, title);
            tabs.draw(c, glass, shadows, light, k, tab, zone == ON_TABS, tabText, tabOn, INK);
            String side = overview != null && !overview.standing.isEmpty() ? overview.standing : status;
            c.drawText(side, R * k, TITLE_BASE * k, headerRight);
        }
        c.drawText(hint(), w / 2f, 1064 * k, hint);
    }

    // MARK: - Time: the match, the last results, the positions

    private Football.Match teamMatch() {
        Football.Overview o = overview;
        Football.Match m = o == null ? null : o.match;
        return m != null ? m : next;
    }

    private void drawTeam(Canvas c) {
        Football.Match m = teamMatch();
        boolean focused = zone == ON_ITEMS && tab == TEAM && teamItem == 0;
        panel(c, M, 160, R, 500);
        if (focused) focusPill(c, (M + 12) * k, 172 * k, (R - 12) * k, 488 * k);
        float cx = w / 2f;
        if (m == null) {
            dim.setTextAlign(Paint.Align.CENTER);
            c.drawText(overview == null || schedule == null ? Ui.tr("Carregando…", "Loading…", "Cargando…")
                    : Ui.tr("Nenhum jogo marcado.", "No match scheduled.", "Ningún partido programado."), cx, 340 * k, dim);
            dim.setTextAlign(Paint.Align.LEFT);
        } else {
            matchStrip(c, m, 212, focused);
            dim.setTextAlign(Paint.Align.CENTER);
            int keep = dim.getColor();
            if (focused) dim.setColor(DIM);
            c.drawText(FootballText.when(m.at) + (m.broadcast.isEmpty() ? "" : "  ·  " + m.broadcast), cx, 462 * k, dim);
            dim.setColor(keep);
            dim.setTextAlign(Paint.Align.LEFT);
        }
        drawForm(c);
        drawPositions(c);
    }

    /** Home and away with their logos, the score (or the time) between them, the competition and the state on top. */
    private void matchStrip(Canvas c, Football.Match m, float top, boolean onPill) {
        float cx = w / 2f, side = 420 * k, cy = (top + 108) * k;
        int keepName = name.getColor(), keepBig = big.getColor(), keepTitle = panelTitle.getColor();
        if (onPill) {
            name.setColor(INK);
            big.setColor(INK);
            panelTitle.setColor(DIM);
        }
        c.drawText(m.leagueName, (M + 40) * k, top * k, panelTitle);
        stateBadge(c, m, (R - 40) * k, (top - 8) * k);
        logo(c, m.home, cx - side, cy - 16 * k, 104 * k);
        logo(c, m.away, cx + side, cy - 16 * k, 104 * k);
        c.drawText(m.home.label(), cx - side, cy + 78 * k, name);
        c.drawText(m.away.label(), cx + side, cy + 78 * k, name);
        c.drawText(m.homeScore >= 0 ? FootballText.score(m) : Ui.time(m.at), cx, cy - 16 * k + Ui.halfCap(big), big);
        name.setColor(keepName);
        big.setColor(keepBig);
        panelTitle.setColor(keepTitle);
    }

    private void drawForm(Canvas c) {
        panel(c, M, 530, 940, 1028);
        float x = (M + 36) * k, r = (940 - 36) * k;
        c.drawText(Ui.tr("Últimos jogos", "Last matches", "Últimos partidos"), x, 580 * k, panelTitle);
        if (last.isEmpty()) {
            if (schedule == null) loading(c, x, 640 * k);
            else c.drawText(Ui.tr("Nenhum ainda.", "None yet.", "Ninguno aún."), x, 640 * k, dim);
            return;
        }
        for (int i = 0; i < last.size(); i++) {
            Football.Match m = last.get(i);
            float top = (600 + i * 84) * k, mid = top + 40 * k;
            boolean focused = zone == ON_ITEMS && tab == TEAM && teamItem == i + 1;
            if (focused) focusPill(c, x - 16 * k, top, r + 16 * k, top + 80 * k);
            matchRow(c, m, x, r, mid, focused, true);
        }
    }

    /**
     * A match in a list, from the team's side: V/E/D (played), the opponent's logo and name, when, home or away and
     * the competition, and the score (or where it's on).
     */
    private void matchRow(Canvas c, Football.Match m, float x, float r, float mid, boolean onPill, boolean showOutcome) {
        int keepText = text.getColor(), keepDim = smallDim.getColor(), keepBold = numbersBold.getColor();
        if (onPill) {
            text.setColor(INK);
            smallDim.setColor(DIM);
            numbersBold.setColor(INK);
        }
        float tx = x;
        if (showOutcome && m.over()) {
            outcome(c, m.outcome(teamId), tx, mid);
            tx += 56 * k;
        }
        Football.Team them = m.opponent(teamId);
        logo(c, them, tx + 22 * k, mid, 44 * k);
        tx += 60 * k;
        c.drawText(them.label(), tx, mid - 4 * k, text);
        String when = m.over() ? Ui.datePattern("format|d/M").format(m.at) : FootballText.when(m.at);
        c.drawText(when + "  ·  " + side(m, teamId) + (m.leagueName.isEmpty() ? "" : "  ·  " + m.leagueName), tx, mid + 28 * k, smallDim);
        String right = m.live() ? FootballText.state(m) + "  " + scoreFor(m, teamId) : m.over() ? scoreFor(m, teamId) : m.broadcast;
        if (!right.isEmpty()) c.drawText(right, r, mid + Ui.halfCap(numbersBold), numbersBold);
        text.setColor(keepText);
        smallDim.setColor(keepDim);
        numbersBold.setColor(keepBold);
    }

    /** Where the team stands in each competition with a table (from the tables as they arrive). */
    private void drawPositions(Canvas c) {
        panel(c, 980, 530, R, 1028);
        float x = (980 + 36) * k, r = (R - 36) * k;
        c.drawText(Ui.tr("Nos campeonatos", "In the competitions", "En los torneos"), x, 580 * k, panelTitle);
        int shown = 0;
        for (int i = 0; i < leagues.size() && shown < 5; i++) {
            Football.Table t = tables.get(leagues.get(i));
            Football.TableRow row = t == null ? null : find(t);
            if (row == null) continue;
            float mid = (640 + shown * 84) * k;
            numbersBold.setTextAlign(Paint.Align.LEFT);
            c.drawText(Ui.trf(Ui.tr("{n}º", "#{n}", "{n}º"), "n", row.rank), x, mid + Ui.halfCap(numbersBold), numbersBold);
            numbersBold.setTextAlign(Paint.Align.RIGHT);
            c.drawText(leagueNames.get(i), x + 80 * k, mid - 4 * k, text);
            c.drawText(Ui.trf(Ui.tr("{p} pts  ·  {j} jogos", "{p} pts  ·  {j} played", "{p} pts  ·  {j} partidos"), "p", row.points,
                    "j", row.played), x + 80 * k, mid + 28 * k, smallDim);
            shown++;
        }
        if (shown == 0) {
            if (loadingTables || schedule == null) loading(c, x, 640 * k);
            else c.drawText(Ui.tr("Sem tabela agora.", "No table right now.", "Sin tabla ahora."), x, 640 * k, dim);
        }
    }

    private Football.TableRow find(Football.Table t) {
        for (List<Football.TableRow> g : t.rows) for (Football.TableRow r : g) if (r.team.id.equals(teamId)) return r;
        return null;
    }

    // MARK: - Jogos: the season, live, next and played

    private void drawGames(Canvas c) {
        panel(c, M, 160, R, 1028);
        float x = (M + 40) * k, r = (R - 40) * k;
        if (schedule == null) {
            loading(c, x, 230 * k);
            return;
        }
        if (gameRows.isEmpty()) {
            c.drawText(Ui.tr("Nenhum jogo nesta temporada.", "No matches this season.", "Ningún partido esta temporada."), x, 230 * k, dim);
            return;
        }
        for (int i = gameFirst; i < gameRows.size() && i < gameFirst + GAME_ROWS; i++) {
            Object row = gameRows.get(i);
            float top = (178 + (i - gameFirst) * ROW) * k, mid = top + 40 * k;
            if (row instanceof String) {
                c.drawText((String) row, x, mid + Ui.halfCap(panelTitle) + 12 * k, panelTitle);
                continue;
            }
            boolean focused = zone == ON_ITEMS && tab == GAMES && i == gameItem;
            if (focused) focusPill(c, x - 20 * k, top, r + 20 * k, top + 80 * k);
            matchRow(c, (Football.Match) row, x, r, mid, focused, true);
        }
        // More below: a chevron at the panel's bottom edge.
        if (gameFirst + GAME_ROWS < gameRows.size()) Symbols.draw(getContext(), c, "chevron_down", w / 2f, 1012 * k, 22 * k, dimInk());
    }

    // MARK: - Tabela: the competitions as chips, the table below

    private List<Football.TableRow> rows() {
        Football.Table t = leagues.isEmpty() ? null : tables.get(leagues.get(league));
        if (t == null || t.rows.isEmpty()) return new ArrayList<>();
        return t.rows.get(group(t));
    }

    /** The group the team is in (a cup's group stage), else the first. */
    private int group(Football.Table t) {
        for (int i = 0; i < t.rows.size(); i++) for (Football.TableRow row : t.rows.get(i)) if (row.team.id.equals(teamId)) return i;
        return 0;
    }

    private int teamRow() {
        List<Football.TableRow> rows = rows();
        for (int i = 0; i < rows.size(); i++) if (rows.get(i).team.id.equals(teamId)) return i;
        return 0;
    }

    private void drawTable(Canvas c) {
        // The competitions: one chip each, the shown one filled (white while the focus is on them).
        float cx = M * k;
        for (int i = 0; i < leagues.size(); i++) {
            String label = leagueNames.get(i).isEmpty() ? leagues.get(i) : leagueNames.get(i);
            Paint p = i == league ? chipOn : chip;
            float wd = p.measureText(label) + 48 * k;
            rect.set(cx, 162 * k, cx + wd, 214 * k);
            if (i == league) {
                if (zone == ON_CHIPS && tab == TABLE) focusPill(c, rect.left, rect.top, rect.right, rect.bottom);
                else {
                    fill.setShader(null);
                    fill.setColor(ink(0.1f));
                    c.drawRoundRect(rect, 26 * k, 26 * k, fill);
                }
            } else {
                glass.draw(c, rect, 26 * k, light ? 0 : 1, k, 1);
            }
            int keep = p.getColor();
            if (i == league && zone == ON_CHIPS && tab == TABLE) p.setColor(INK);
            c.drawText(label, rect.left + 24 * k, rect.centerY() + Ui.halfCap(p), p);
            p.setColor(keep);
            cx += wd + 16 * k;
        }
        panel(c, M, 236, R, 1028);
        float x = (M + 40) * k;
        if (leagues.isEmpty()) {
            loading(c, x, 300 * k);
            return;
        }
        Football.Table t = tables.get(leagues.get(league));
        if (t == null) {
            c.drawText(tables.containsKey(leagues.get(league)) ? Ui.tr("Sem tabela nesta fase.", "No table at this stage.", "Sin tabla en esta fase.")
                    : Ui.tr("Carregando…", "Loading…", "Cargando…"), x, 300 * k, dim);
            return;
        }
        int g = group(t);
        String group = t.groups.isEmpty() ? "" : t.groups.get(g);
        c.drawText(group.isEmpty() ? t.name : group, x, 282 * k, panelTitle);
        float[] cols = {1300, 1410, 1510, 1610, 1710, 1800};
        String[] heads = {Ui.tr("P", "Pts", "Pts"), Ui.tr("J", "P", "J"), Ui.tr("V", "W", "G"), Ui.tr("E", "D", "E"), Ui.tr("D", "L", "P"),
                Ui.tr("SG", "GD", "DG")};
        smallDim.setTextAlign(Paint.Align.RIGHT);
        for (int i = 0; i < cols.length; i++) c.drawText(heads[i], cols[i] * k, 282 * k, smallDim);
        smallDim.setTextAlign(Paint.Align.LEFT);
        List<Football.TableRow> rows = t.rows.get(g);
        int first = zone == ON_ITEMS && tab == TABLE ? tableFirst : firstShown(teamRow(), rows.size(), TABLE_ROWS);
        for (int i = first; i < rows.size() && i < first + TABLE_ROWS; i++) {
            Football.TableRow row = rows.get(i);
            float top = (298 + (i - first) * 38) * k, mid = top + 19 * k;
            boolean mine = row.team.id.equals(teamId), focused = zone == ON_ITEMS && tab == TABLE && i == tableRow;
            if (focused) focusPill(c, (M + 16) * k, top, (R - 16) * k, top + 36 * k);
            else if (mine) {
                rect.set((M + 16) * k, top, (R - 16) * k, top + 36 * k);
                fill.setShader(null);
                fill.setColor(light ? 0x1A0071E3 : 0x2964D2FF);   // the team's row: a faint blue band
                c.drawRoundRect(rect, 12 * k, 12 * k, fill);
            }
            int on = focused ? INK : ink(0.95f);
            numbers.setColor(on);
            numbersBold.setColor(on);
            text.setColor(on);
            c.drawText(String.valueOf(row.rank == 0 ? i + 1 : row.rank), x + 30 * k, mid + Ui.halfCap(numbers), numbers);
            logo(c, row.team, x + 70 * k, mid, 28 * k);
            c.drawText(row.team.label(), x + 100 * k, mid + Ui.halfCap(text), text);
            int[] values = {row.points, row.played, row.wins, row.draws, row.losses, row.goalDiff};
            for (int j = 0; j < values.length; j++) {
                String v = j == 5 && values[j] > 0 ? "+" + values[j] : String.valueOf(values[j]);
                c.drawText(v, cols[j] * k, mid + Ui.halfCap(numbers), j == 0 ? numbersBold : numbers);
            }
        }
        numbers.setColor(ink(0.9f));
        numbersBold.setColor(ink(1));
        text.setColor(ink(0.95f));
    }

    // MARK: - The match screen

    private void drawMatch(Canvas c) {
        Football.Match m = open;
        if (m == null) return;
        c.drawRect(0, 0, w, h, background);   // over the tabs' pages
        c.drawRect(0, 0, w, h, shade);
        Football.Match shown = details != null && details.match != null ? details.match : m;
        panel(c, M, 160, R, 400);
        matchStrip(c, shown, 212, false);
        String venue = details == null || details.venue.isEmpty() ? "" : "  ·  " + details.venue;
        dim.setTextAlign(Paint.Align.CENTER);
        c.drawText(FootballText.when(shown.at) + (shown.broadcast.isEmpty() ? "" : "  ·  " + shown.broadcast) + venue, w / 2f, 376 * k, dim);
        dim.setTextAlign(Paint.Align.LEFT);
        switch (matchTab) {
            case D_SUMMARY: drawSummary(c, shown); break;
            case D_NUMBERS: drawNumbers(c); break;
            case D_LINEUPS: drawLineups(c, shown); break;
            case D_RECORD: drawRecord(c, shown); break;
            default: drawOdds(c, shown); break;
        }
    }

    /** A message in the content panel (loading, or nothing to show). */
    private boolean empty(Canvas c, boolean nothing, String none) {
        if (details != null && !nothing) return false;
        dim.setTextAlign(Paint.Align.CENTER);
        c.drawText(details == null && loadingMatch ? Ui.tr("Carregando…", "Loading…", "Cargando…") : none, w / 2f, 700 * k, dim);
        dim.setTextAlign(Paint.Align.LEFT);
        return true;
    }

    /** Goals and cards on a timeline: the home team's on the left, the away team's on the right, the minute between. */
    private void drawSummary(Canvas c, Football.Match m) {
        panel(c, M, 430, R, 1028);
        if (empty(c, details != null && details.events.isEmpty(), Football.PRE.equals(m.state)
                ? Ui.tr("O jogo ainda não começou.", "It hasn't started yet.", "Aún no empezó.")
                : Ui.tr("Nenhum gol ou cartão.", "No goals or cards.", "Ningún gol o tarjeta."))) return;
        float cx = w / 2f;
        stroke(c, cx, 470 * k, cx, 1000 * k);
        List<Football.Event> events = details.events;
        int shown = Math.min(events.size(), 12);
        for (int i = 0; i < shown; i++) {
            Football.Event e = events.get(i);
            float mid = (492 + i * 42) * k;
            boolean home = e.teamId.equals(m.home.id);
            smallDim.setTextAlign(Paint.Align.CENTER);
            rect.set(cx - 34 * k, mid - 16 * k, cx + 34 * k, mid + 16 * k);
            fill.setShader(null);
            fill.setColor(light ? 0xFFEDEDF0 : 0xFF2C2C2E);
            c.drawRoundRect(rect, 16 * k, 16 * k, fill);
            c.drawText(e.minute, cx, mid + Ui.halfCap(smallDim), smallDim);
            smallDim.setTextAlign(Paint.Align.LEFT);
            float icon = home ? cx - 70 * k : cx + 70 * k;
            if (e.kind == Football.Event.GOAL) {
                fill.setColor(ink(0.9f));
                c.drawCircle(icon, mid, 8 * k, fill);
            } else {
                fill.setColor(e.kind == Football.Event.RED ? 0xFFFF3B30 : 0xFFFFCC00);
                rect.set(icon - 7 * k, mid - 10 * k, icon + 7 * k, mid + 10 * k);
                c.drawRoundRect(rect, 2 * k, 2 * k, fill);
            }
            String who = e.player + (e.kind == Football.Event.GOAL ? "" : e.kind == Football.Event.RED
                    ? "  ·  " + Ui.tr("vermelho", "red", "roja") : "  ·  " + Ui.tr("amarelo", "yellow", "amarilla"));
            small.setTextAlign(home ? Paint.Align.RIGHT : Paint.Align.LEFT);
            c.drawText(who, home ? icon - 24 * k : icon + 24 * k, mid + Ui.halfCap(small), small);
            small.setTextAlign(Paint.Align.LEFT);
        }
    }

    private void stroke(Canvas c, float x1, float y1, float x2, float y2) {
        fill.setShader(null);
        fill.setColor(ink(0.12f));
        c.drawRect(x1 - k, y1, x2 + k, y2, fill);
    }

    /** The numbers side by side, with a bar split by each team's share. */
    private void drawNumbers(Canvas c) {
        panel(c, M, 430, R, 1028);
        if (empty(c, details != null && details.stats.isEmpty(), Ui.tr("Sem números deste jogo.", "No numbers for this match.",
                "Sin números de este partido."))) return;
        float left = 360 * k, right = 1560 * k, cx = w / 2f;
        for (int i = 0; i < details.stats.size() && i < 10; i++) {
            String[] s = details.stats.get(i);
            float mid = (486 + i * 54) * k;
            numbersBold.setTextAlign(Paint.Align.LEFT);
            c.drawText(s[1], (M + 60) * k, mid + Ui.halfCap(numbersBold), numbersBold);
            numbersBold.setTextAlign(Paint.Align.RIGHT);
            c.drawText(s[2], (R - 60) * k, mid + Ui.halfCap(numbersBold), numbersBold);
            smallDim.setTextAlign(Paint.Align.CENTER);
            c.drawText(s[0], cx, mid - 6 * k, smallDim);
            smallDim.setTextAlign(Paint.Align.LEFT);
            double a = number(s[1]), b = number(s[2]);
            if (a + b <= 0) continue;
            float split = left + (float) (a / (a + b)) * (right - left);
            fill.setShader(null);
            fill.setColor(ink(0.75f));
            rect.set(left, mid + 6 * k, split - 3 * k, mid + 14 * k);
            c.drawRoundRect(rect, 4 * k, 4 * k, fill);
            fill.setColor(ink(0.25f));
            rect.set(split + 3 * k, mid + 6 * k, right, mid + 14 * k);
            c.drawRoundRect(rect, 4 * k, 4 * k, fill);
        }
    }

    private static double number(String s) {
        try {
            return Double.parseDouble(s.replace("%", "").replace(',', '.').trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void drawLineups(Canvas c, Football.Match m) {
        panel(c, M, 430, 940, 1028);
        panel(c, 980, 430, R, 1028);
        if (details == null || details.homeLineup.isEmpty()) {
            empty(c, true, Ui.tr("Escalações ainda não divulgadas.", "Line-ups not out yet.", "Alineaciones aún no publicadas."));
            return;
        }
        lineup(c, m.home, details.homeFormation, details.homeLineup, (M + 36) * k);
        lineup(c, m.away, details.awayFormation, details.awayLineup, (980 + 36) * k);
    }

    private void lineup(Canvas c, Football.Team t, String formation, List<String> players, float x) {
        logo(c, t, x + 18 * k, 474 * k, 36 * k);
        c.drawText(t.label() + (formation.isEmpty() ? "" : "  ·  " + formation), x + 50 * k, 482 * k, text);
        for (int i = 0; i < players.size() && i < 11; i++) c.drawText(players.get(i), x, (540 + i * 44) * k, small);
    }

    /** The last meetings between the two, and each team's last five. */
    private void drawRecord(Canvas c, Football.Match m) {
        panel(c, M, 430, 900, 1028);
        panel(c, 930, 430, R, 728);
        panel(c, 930, 752, R, 1028);
        float x = (M + 36) * k, r = (900 - 36) * k;
        c.drawText(Ui.tr("Confrontos diretos", "Head to head", "Enfrentamientos"), x, 478 * k, panelTitle);
        if (details == null) {
            if (loadingMatch) loading(c, x, 540 * k);
            else c.drawText(Ui.tr("Sem dados agora.", "No data right now.", "Sin datos ahora."), x, 540 * k, dim);
            return;
        }
        if (!details.meetingsSummary.isEmpty()) {
            dim.setTextAlign(Paint.Align.RIGHT);
            c.drawText(details.meetingsSummary, r, 478 * k, dim);
            dim.setTextAlign(Paint.Align.LEFT);
        }
        if (details.meetings.isEmpty()) c.drawText(Ui.tr("Nenhum confronto recente.", "No recent meetings.", "Ningún enfrentamiento reciente."), x, 540 * k, dim);
        for (int i = 0; i < details.meetings.size() && i < 6; i++) {
            Football.Match g = details.meetings.get(i);
            float mid = (536 + i * 80) * k;
            c.drawText(Ui.datePattern("format|d/M/yy").format(g.at) + (g.leagueName.isEmpty() ? "" : "  ·  " + g.leagueName), x, mid + 26 * k, smallDim);
            c.drawText(g.home.label(), x, mid - 6 * k, text);
            numbersBold.setTextAlign(Paint.Align.CENTER);
            c.drawText(g.homeScore >= 0 ? g.homeScore + " × " + g.awayScore : "", (x + r) / 2, mid - 6 * k, numbersBold);
            numbersBold.setTextAlign(Paint.Align.RIGHT);
            text.setTextAlign(Paint.Align.RIGHT);
            c.drawText(g.away.label(), r, mid - 6 * k, text);
            text.setTextAlign(Paint.Align.LEFT);
        }
        formPanel(c, m.home, details.homeForm, (930 + 36) * k, (R - 36) * k, 478);
        formPanel(c, m.away, details.awayForm, (930 + 36) * k, (R - 36) * k, 800);
    }

    /** A team's last five: its name, then V/E/D squares with the opponent and score under each. */
    private void formPanel(Canvas c, Football.Team t, List<Football.Match> form, float x, float r, float top) {
        c.drawText(Ui.trf(Ui.tr("Últimos 5 do {team}", "{team}'s last 5", "Últimos 5 de {team}"), "team", t.label()), x, top * k, panelTitle);
        if (form.isEmpty()) {
            c.drawText(Ui.tr("Sem dados.", "No data.", "Sin datos."), x, (top + 60) * k, dim);
            return;
        }
        float col = (r - x) / 5;
        for (int i = 0; i < form.size() && i < 5; i++) {
            Football.Match g = form.get(i);
            float cx = x + col * (i + 0.5f), mid = (top + 64) * k;
            outcome(c, g.outcome(t.id), cx - 20 * k, mid);
            Football.Team them = g.opponent(t.id);
            smallDim.setTextAlign(Paint.Align.CENTER);
            small.setTextAlign(Paint.Align.CENTER);
            c.drawText(them.abbreviation.isEmpty() ? them.label() : them.abbreviation, cx, mid + 58 * k, small);
            c.drawText(scoreFor(g, t.id), cx, mid + 92 * k, smallDim);
            smallDim.setTextAlign(Paint.Align.LEFT);
            small.setTextAlign(Paint.Align.LEFT);
        }
    }

    /** The odds: home win, draw and away win, each as the average decimal odd and the chance it implies. */
    private void drawOdds(Canvas c, Football.Match m) {
        Football.Odds o = odds;
        if (o == null || !o.any()) {
            panel(c, M, 430, R, 1028);
            dim.setTextAlign(Paint.Align.CENTER);
            c.drawText(loadingMatch ? Ui.tr("Carregando…", "Loading…", "Cargando…")
                    : Ui.tr("Sem odds para este jogo.", "No odds for this match.", "Sin cuotas para este partido."), w / 2f, 700 * k, dim);
            dim.setTextAlign(Paint.Align.LEFT);
            return;
        }
        String[] labels = {Ui.trf(Ui.tr("Vitória do {team}", "{team} wins", "Gana {team}"), "team", m.home.label()),
                Ui.tr("Empate", "Draw", "Empate"), Ui.trf(Ui.tr("Vitória do {team}", "{team} wins", "Gana {team}"), "team", m.away.label())};
        double[] values = {o.home, o.draw, o.away};
        float gap = 30, wd = (R - M - 2 * gap) / 3;
        for (int i = 0; i < 3; i++) {
            float left = M + i * (wd + gap);
            panel(c, left, 430, left + wd, 900);
            float cx = (left + wd / 2) * k;
            name.setTextAlign(Paint.Align.CENTER);
            c.drawText(labels[i], cx, 500 * k, name);
            if (Double.isNaN(values[i])) {
                dim.setTextAlign(Paint.Align.CENTER);
                c.drawText("—", cx, 660 * k, dim);
                dim.setTextAlign(Paint.Align.LEFT);
                continue;
            }
            c.drawText(String.format(Ui.locale(), "%.2f", values[i]), cx, 660 * k, oddsBig);
            dim.setTextAlign(Paint.Align.CENTER);
            c.drawText(Ui.trf(Ui.tr("{p}% de chance", "{p}% chance", "{p}% de probabilidad"), "p", o.chance(values[i])), cx, 730 * k, dim);
            dim.setTextAlign(Paint.Align.LEFT);
            // The chance as a bar.
            float bl = cx - 140 * k, br = cx + 140 * k;
            fill.setShader(null);
            fill.setColor(ink(0.12f));
            rect.set(bl, 780 * k, br, 792 * k);
            c.drawRoundRect(rect, 6 * k, 6 * k, fill);
            fill.setColor(ink(0.75f));
            rect.set(bl, 780 * k, bl + (br - bl) * o.chance(values[i]) / 100f, 792 * k);
            c.drawRoundRect(rect, 6 * k, 6 * k, fill);
        }
        String books = String.join(", ", o.bookmakers);
        String source = o.bookmakers.size() == 1
                ? Ui.trf(Ui.tr("Odd decimal de {b}, via ESPN.", "Decimal odds from {b}, via ESPN.", "Cuota decimal de {b}, vía ESPN."), "b", books)
                : Ui.trf(Ui.tr("Média das odds decimais de {n} casas ({b}), via ESPN.", "Average decimal odds of {n} bookmakers ({b}), via ESPN.",
                "Promedio de cuotas de {n} casas ({b}), vía ESPN."), "n", o.bookmakers.size(), "b", books);
        dim.setTextAlign(Paint.Align.CENTER);
        c.drawText(source + "  " + Ui.tr("A chance já desconta a margem das casas.", "The chance leaves out the bookmakers' margin.",
                "La probabilidad descuenta el margen de las casas."), w / 2f, 960 * k, dim);
        dim.setTextAlign(Paint.Align.LEFT);
    }
}
