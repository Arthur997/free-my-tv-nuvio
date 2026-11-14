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
 * The football screen, in the home screen's look (the shelf's glass, light by day, dark at night). Three pages, one
 * above the other (↓ and ↑):
 *  - the team: its match live or next in large, its last five results and its next matches, in every competition;
 *  - the table: the team's competitions (← and → switch), its row marked;
 *  - the match: live, or the last one, with the goals and cards, the numbers and the line-ups.
 * Each page is a layer drawn when its data arrives, then only moved. Measurements in a 1920×1080 space.
 */
final class FootballView extends ViewGroup implements Choreographer.FrameCallback {
    private static final float M = 80, R = 1840, TITLE_BASE = 116, RADIUS = 30;
    private static final int TEAM = 0, TABLE = 1, MATCH = 2, PAGES = 3;
    private static final int LIST = 5, TABLE_ROWS = 20;
    private static final int INK = 0xFF1D1D1F, DIM = 0xFF6E6E73, WIN = 0xFF30D158, LOSS = 0xFFFF453A;
    private static final long LIVE_EVERY = 60_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Ui.background("football-screen");
    private final FootballSource source = FootballSource.get();
    private final String teamId = Prefs.footballTeam();
    private final Spring page = new Spring(0.34f, 0.92f, 0);
    private final Layer[] layers = new Layer[PAGES];
    private final Scene scene = new Scene();
    private final Shadows shadows = new Shadows();
    private final Paint glassShadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final DockGlass glass = new DockGlass(scene, shadows, glassShadow);
    private final Paint background = new Paint(), shade = new Paint(), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint image = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF rect = new RectF();
    private boolean running, frameQueued, light = true;
    private long lastNanos;
    private int w, h;
    private float k = 1;

    // The data, as it arrives (UI thread)
    private Football.Overview overview;
    private List<Football.Match> schedule;
    private final List<String> leagues = new ArrayList<>(), leagueNames = new ArrayList<>();
    private final Map<String, Football.Table> tables = new HashMap<>();
    private int league;
    private Football.Details details;
    private Football.Match focusMatch;
    private boolean failed, loadingTable;
    private long updatedAt;

    // Paints
    private Paint title, headerRight, panelTitle, big, name, text, dim, small, smallDim, pill, numbers, numbersBold;

    // Text, made when the data changes
    private String header = "", headerSide = "", heroState = "", heroScore = "", heroWhen = "", heroLeague = "", status = "";
    private final List<Football.Match> last = new ArrayList<>(), next = new ArrayList<>();
    private final List<String[]> lastRows = new ArrayList<>(), nextRows = new ArrayList<>();   // {line1, line2, right, outcome}
    private String matchTitle = "", matchSide = "", matchScore = "", matchState = "";
    private final List<String[]> eventRows = new ArrayList<>();   // {minute, player, team abbreviation, kind}

    private final Runnable minute = new Runnable() {
        @Override public void run() {
            if (!running) return;
            if (isLive()) refreshLive();
            prepare();
            redraw();
            handler.postDelayed(this, LIVE_EVERY);
        }
    };

    FootballView(Activity a) {
        super(a);
        setFocusable(true);
        setWillNotDraw(false);
        for (int i = 0; i < PAGES; i++) {
            layers[i] = new Layer(a, i);
            layers[i].setLayerType(Ui.LEGACY ? LAYER_TYPE_NONE : LAYER_TYPE_HARDWARE, null);
            layers[i].setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(layers[i]);
        }
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
        prepare();
        redraw();
        handler.postDelayed(minute, LIVE_EVERY);
    }

    void pause() {
        running = false;
        handler.removeCallbacks(minute);
        Choreographer.getInstance().removeFrameCallback(this);
        frameQueued = false;
    }

    /** Everything the screen shows: the team, then its season (heavier), then the table and the match. */
    private void load() {
        status = Ui.tr("Carregando…", "Loading…", "Cargando…");
        io.execute(() -> {
            try {
                Football.Overview o = source.overview(teamId);
                handler.post(() -> {
                    overview = o;
                    updatedAt = System.currentTimeMillis();
                    prepare();
                    redraw();
                });
                List<Football.Match> s = source.schedule(teamId);
                handler.post(() -> {
                    seasonArrived(s);
                    loadTable();
                    loadDetails();
                });
            } catch (Exception e) {
                AppLog.w("football screen: " + e.getMessage());
                handler.post(() -> {
                    failed = true;
                    status = Ui.tr("Sem conexão agora.", "No connection right now.", "Sin conexión ahora.");
                    prepare();
                    redraw();
                });
            }
        });
    }

    /** The season: the last and next matches, the competitions (the main one first), and which match to detail. */
    private void seasonArrived(List<Football.Match> s) {
        schedule = s;
        last.clear();
        next.clear();
        leagues.clear();
        leagueNames.clear();
        Football.Match live = null, lastOver = null;
        for (Football.Match m : s) {
            if (m.live()) live = m;
            else if (m.over()) lastOver = m;
        }
        for (int i = s.size() - 1; i >= 0 && last.size() < LIST; i--) if (s.get(i).over()) last.add(s.get(i));
        for (Football.Match m : s) if (Football.PRE.equals(m.state) && next.size() < LIST) next.add(m);
        String main = overview != null && !overview.team.league.isEmpty() ? overview.team.league : Prefs.footballLeague();
        if (!main.isEmpty()) addLeague(main, overview != null ? overview.team.leagueName : "");
        // The other competitions, the most recent first; friendlies have no table.
        for (int i = s.size() - 1; i >= 0; i--) {
            Football.Match m = s.get(i);
            if (!m.league.isEmpty() && !m.league.contains("friendly")) addLeague(m.league, m.leagueName);
        }
        focusMatch = live != null ? live : lastOver != null ? lastOver : next.isEmpty() ? null : next.get(0);
        updatedAt = System.currentTimeMillis();
        status = "";
        prepare();
        redraw();
    }

    private void addLeague(String slug, String label) {
        if (leagues.contains(slug)) return;
        leagues.add(slug);
        leagueNames.add(label);
    }

    private void loadTable() {
        if (leagues.isEmpty() || loadingTable) return;
        String slug = leagues.get(league);
        if (tables.containsKey(slug)) return;
        loadingTable = true;
        io.execute(() -> {
            Football.Table t = null;
            try {
                t = source.table(slug);
            } catch (Exception e) {
                AppLog.w("football table " + slug + ": " + e.getMessage());
            }
            Football.Table result = t;
            handler.post(() -> {
                loadingTable = false;
                tables.put(slug, result);   // null: none (a cup's knockout stage)
                layers[TABLE].invalidate();
            });
        });
    }

    private void loadDetails() {
        Football.Match m = focusMatch;
        if (m == null || m.league.isEmpty() || Football.PRE.equals(m.state)) {
            prepare();
            layers[MATCH].invalidate();
            return;
        }
        io.execute(() -> {
            try {
                Football.Details d = source.details(m.league, m.id);
                handler.post(() -> {
                    details = d;
                    prepare();
                    layers[MATCH].invalidate();
                });
            } catch (Exception e) {
                AppLog.w("football match: " + e.getMessage());
            }
        });
    }

    private boolean isLive() {
        return focusMatch != null && focusMatch.live() || overview != null && overview.match != null && overview.match.live();
    }

    /** While the team plays: the card's data and the match's, every minute (not the season). */
    private void refreshLive() {
        io.execute(() -> {
            try {
                Football.Overview o = source.overview(teamId);
                handler.post(() -> {
                    overview = o;
                    if (o.match != null && focusMatch != null && o.match.id.equals(focusMatch.id)) focusMatch = o.match;
                    updatedAt = System.currentTimeMillis();
                    prepare();
                    redraw();
                    loadDetails();
                });
            } catch (Exception e) {
                AppLog.w("football live: " + e.getMessage());
            }
        });
    }

    // MARK: - Keys: ↑ ↓ pages, ← → the table's competition, Back to the first page (then closes)

    boolean onKey(KeyEvent e) {
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        int target = Math.round(page.target);
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down && target < PAGES - 1) go(target + 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down && target > 0) go(target - 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down && target == TABLE && leagues.size() > 1) {
                    int n = leagues.size();
                    league = (league + (e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : n - 1)) % n;
                    loadTable();
                    layers[TABLE].invalidate();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (down && target < PAGES - 1) go(target + 1);
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (target == 0) return false;   // the activity closes
                if (!down) go(0);
                return true;
            default:
                return false;
        }
    }

    private void go(int to) {
        page.target = to;
        announceForAccessibility(to == TEAM ? header : to == TABLE ? Ui.tr("Tabela", "Table", "Tabla") : matchTitle);
        wake();
    }

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
        prepare();
        place();
    }

    /** The pages where the scroll has them (moved, not redrawn). */
    private void place() {
        for (int i = 0; i < PAGES; i++) layers[i].setTranslationY((i - page.value) * h);
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
        title = Ui.text(Ui.displaySemibold, 50 * k, ink(1));
        headerRight = Ui.text(Ui.medium, 24 * k, dimInk());
        headerRight.setTextAlign(Paint.Align.RIGHT);
        panelTitle = Ui.text(Ui.semibold, 23 * k, dimInk());
        big = Ui.numbers(Ui.displayMedium, 92 * k, ink(1));
        big.setTextAlign(Paint.Align.CENTER);
        name = Ui.text(Ui.medium, 30 * k, ink(0.95f));
        name.setTextAlign(Paint.Align.CENTER);
        text = Ui.text(Ui.medium, 27 * k, ink(0.95f));
        dim = Ui.text(Ui.medium, 24 * k, dimInk());
        small = Ui.numbers(Ui.medium, 24 * k, ink(0.95f));
        smallDim = Ui.numbers(Ui.medium, 22 * k, dimInk());
        pill = Ui.text(Ui.semibold, 20 * k, 0xFFFFFFFF);
        pill.setTextAlign(Paint.Align.CENTER);
        numbers = Ui.numbers(Ui.medium, 24 * k, ink(0.9f));
        numbers.setTextAlign(Paint.Align.RIGHT);
        numbersBold = Ui.numbers(Ui.semibold, 24 * k, ink(1));
        numbersBold.setTextAlign(Paint.Align.RIGHT);
    }

    /** One page, drawn in its own place (the scroll moves it). */
    private final class Layer extends View {
        private final int kind;

        Layer(Context c, int kind) {
            super(c);
            this.kind = kind;
        }

        @Override protected void onDraw(Canvas c) {
            if (w == 0 || title == null) return;
            if (kind == TEAM) drawTeam(c);
            else if (kind == TABLE) drawTable(c);
            else drawMatch(c);
        }
    }

    @Override protected void onDraw(Canvas c) {
        if (w == 0) return;
        c.drawRect(0, 0, w, h, background);
        c.drawRect(0, 0, w, h, shade);
    }

    // MARK: - Text (made here when the data changes, not while drawing)

    private void prepare() {
        Football.Overview o = overview;
        header = o != null ? o.team.name : Prefs.footballTeamName();
        headerSide = o == null ? status : o.standing.isEmpty() ? status : o.standing;
        Football.Match m = o == null ? null : o.match;
        if (focusMatch != null && focusMatch.live()) m = focusMatch;
        if (m != null) {
            heroState = FootballText.state(m);
            heroScore = m.homeScore >= 0 ? FootballText.score(m) : Ui.time(m.at);
            heroWhen = FootballText.when(m.at) + (m.broadcast.isEmpty() ? "" : "  ·  " + m.broadcast);
            heroLeague = m.leagueName;
        }
        lastRows.clear();
        for (Football.Match x : last) lastRows.add(row(x, true));
        nextRows.clear();
        for (Football.Match x : next) nextRows.add(row(x, false));
        prepareMatch();
    }

    /** A list row: the opponent, when and where (home or away), the score from the team's side, and V/E/D. */
    private String[] row(Football.Match x, boolean played) {
        Football.Team them = x.opponent(teamId);
        boolean home = x.home.id.equals(teamId);
        String place = home ? Ui.tr("em casa", "home", "en casa") : Ui.tr("fora", "away", "fuera");
        String when = played ? Ui.datePattern("format|d/M").format(x.at) : FootballText.when(x.at);
        int mine = home ? x.homeScore : x.awayScore, theirs = home ? x.awayScore : x.homeScore;
        String right = played && mine >= 0 ? mine + " × " + theirs : x.broadcast;
        return new String[] {them.label(), when + "  ·  " + place + (x.leagueName.isEmpty() ? "" : "  ·  " + x.leagueName), right,
                played ? x.outcome(teamId) : "", them.id};
    }

    private void prepareMatch() {
        Football.Match m = details != null && details.match != null ? details.match : focusMatch;
        eventRows.clear();
        if (m == null) {
            matchTitle = Ui.tr("Jogo", "Match", "Partido");
            return;
        }
        matchTitle = m.live() ? Ui.tr("Ao vivo", "Live", "En vivo") : m.over() ? Ui.tr("Último jogo", "Last match", "Último partido")
                : Ui.tr("Próximo jogo", "Next match", "Próximo partido");
        matchSide = (m.leagueName.isEmpty() && focusMatch != null ? focusMatch.leagueName : m.leagueName) + "  ·  " + FootballText.when(m.at);
        matchScore = m.homeScore >= 0 ? FootballText.score(m) : Ui.time(m.at);
        matchState = FootballText.state(m);
        if (details == null) return;
        for (Football.Event e : details.events) {
            String team = e.teamId.equals(m.home.id) ? m.home.abbreviation : m.away.abbreviation;
            eventRows.add(new String[] {e.minute, e.player, team, String.valueOf(e.kind)});
        }
    }

    // MARK: - Drawing

    private void panel(Canvas c, float left, float top, float right, float bottom) {
        rect.set(left * k, top * k, right * k, bottom * k);
        glass.draw(c, rect, RADIUS * k, light ? 0 : 1, k, 1);
    }

    private void header(Canvas c, String left, String right) {
        c.drawText(left, M * k - Ui.leftBearing(title, left), TITLE_BASE * k, title);
        c.drawText(right, R * k, TITLE_BASE * k, headerRight);
    }

    private void logo(Canvas c, String id, String initials, float cx, float cy, float size) {
        Bitmap b = FootballLogos.get(getContext(), id, this::redraw);
        rect.set(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2);
        if (b != null) {
            c.drawBitmap(b, null, rect, image);
            return;
        }
        fill.setShader(null);
        fill.setColor(ink(0.1f));
        c.drawCircle(cx, cy, size / 2, fill);
        if (initials.isEmpty() || size < 40 * k) return;   // no logo (yet): the team's initials in its place
        smallDim.setTextAlign(Paint.Align.CENTER);
        c.drawText(initials, cx, cy + Ui.halfCap(smallDim), smallDim);
        smallDim.setTextAlign(Paint.Align.LEFT);
    }

    private void drawTeam(Canvas c) {
        header(c, header, headerSide);
        Football.Overview o = overview;
        Football.Match m = focusMatch != null && focusMatch.live() ? focusMatch : o == null ? null : o.match;
        panel(c, M, 160, R, 470);
        float cx = w / 2f;
        if (m == null) {
            dim.setTextAlign(Paint.Align.CENTER);
            c.drawText(failed ? status : o == null ? Ui.tr("Carregando…", "Loading…", "Cargando…")
                    : Ui.tr("Nenhum jogo marcado.", "No match scheduled.", "Ningún partido programado."), cx, 330 * k, dim);
            dim.setTextAlign(Paint.Align.LEFT);
        } else {
            c.drawText(heroLeague, (M + 40) * k, 214 * k, panelTitle);
            stateBadge(c, m, heroState, (R - 40) * k, 206 * k);
            float side = 420 * k, cy = 300 * k;
            logo(c, m.home.id, m.home.abbreviation, cx - side, cy, 110 * k);
            logo(c, m.away.id, m.away.abbreviation, cx + side, cy, 110 * k);
            c.drawText(m.home.label(), cx - side, 400 * k, name);
            c.drawText(m.away.label(), cx + side, 400 * k, name);
            c.drawText(heroScore, cx, cy + Ui.halfCap(big), big);
            dim.setTextAlign(Paint.Align.CENTER);
            c.drawText(heroWhen, cx, 440 * k, dim);
            dim.setTextAlign(Paint.Align.LEFT);
        }
        drawList(c, M, 940, Ui.tr("Últimos jogos", "Last matches", "Últimos partidos"), lastRows, true);
        drawList(c, 980, R, Ui.tr("Próximos jogos", "Next matches", "Próximos partidos"), nextRows, false);
    }

    /** "AO VIVO 67'" in a red pill; otherwise the state in gray, right-aligned at `right`. */
    private void stateBadge(Canvas c, Football.Match m, String state, float right, float mid) {
        if (m.live()) {
            String s = state.toUpperCase(Ui.locale());
            float tw = pill.measureText(s);
            rect.set(right - tw - 30 * k, mid - 18 * k, right, mid + 18 * k);
            fill.setShader(null);
            fill.setColor(light ? 0xFFD70015 : LOSS);
            c.drawRoundRect(rect, 18 * k, 18 * k, fill);
            c.drawText(s, rect.centerX(), mid + Ui.halfCap(pill), pill);
        } else {
            dim.setTextAlign(Paint.Align.RIGHT);
            c.drawText(state, right, mid + Ui.halfCap(dim), dim);
            dim.setTextAlign(Paint.Align.LEFT);
        }
    }

    private void drawList(Canvas c, float left, float right, String label, List<String[]> rows, boolean played) {
        panel(c, left, 500, right, 1028);
        float x = (left + 36) * k, r = (right - 36) * k;
        c.drawText(label, x, 552 * k, panelTitle);
        if (rows.isEmpty()) {
            c.drawText(schedule == null ? Ui.tr("Carregando…", "Loading…", "Cargando…") : Ui.tr("Nenhum.", "None.", "Ninguno."), x, 620 * k, dim);
            return;
        }
        for (int i = 0; i < rows.size(); i++) {
            String[] row = rows.get(i);
            float top = (580 + i * 88) * k, mid = top + 40 * k;
            if (i > 0) {
                fill.setShader(null);
                fill.setColor(ink(0.08f));
                c.drawRect(x, top - 4 * k, r, top - 4 * k + Math.max(1, k), fill);
            }
            float tx = x;
            if (played) {
                // The result in a small colored square: V green, E gray, D red.
                String code = row[3];
                int color = "W".equals(code) ? (light ? 0xFF248A3D : WIN) : "L".equals(code) ? (light ? 0xFFD70015 : LOSS) : ink(0.35f);
                rect.set(tx, mid - 20 * k, tx + 40 * k, mid + 20 * k);
                fill.setColor(color);
                c.drawRoundRect(rect, 10 * k, 10 * k, fill);
                c.drawText(FootballText.outcome(code), rect.centerX(), mid + Ui.halfCap(pill), pill);
                tx += 56 * k;
            }
            logo(c, row[4], "", tx + 22 * k, mid, 44 * k);
            tx += 60 * k;
            c.drawText(row[0], tx, mid - 4 * k, text);
            c.drawText(row[1], tx, mid + 28 * k, smallDim);
            if (!row[2].isEmpty()) {
                Paint p = played ? numbersBold : smallDim;
                p.setTextAlign(Paint.Align.RIGHT);
                c.drawText(row[2], r, mid + Ui.halfCap(p), p);
                p.setTextAlign(played ? Paint.Align.RIGHT : Paint.Align.LEFT);
            }
        }
    }

    private void drawTable(Canvas c) {
        String name = leagues.isEmpty() ? "" : leagueNames.get(league);
        String side = leagues.size() > 1 ? "←  " + (league + 1) + " / " + leagues.size() + "  →" : "";
        header(c, Ui.tr("Tabela", "Table", "Tabla") + (name.isEmpty() ? "" : "  ·  " + name), side);
        panel(c, M, 160, R, 1028);
        float x = (M + 40) * k;
        Football.Table t = leagues.isEmpty() ? null : tables.get(leagues.get(league));
        if (t == null) {
            boolean waiting = schedule == null || (!leagues.isEmpty() && !tables.containsKey(leagues.get(league)));
            c.drawText(waiting ? Ui.tr("Carregando…", "Loading…", "Cargando…")
                    : Ui.tr("Sem tabela nesta fase.", "No table at this stage.", "Sin tabla en esta fase."), x, 230 * k, dim);
            return;
        }
        // The group the team is in (a cup's group stage), else the first.
        int g = 0;
        for (int i = 0; i < t.rows.size(); i++) for (Football.TableRow row : t.rows.get(i)) if (row.team.id.equals(teamId)) g = i;
        List<Football.TableRow> rows = t.rows.isEmpty() ? new ArrayList<>() : t.rows.get(g);
        String group = t.groups.isEmpty() ? "" : t.groups.get(g);
        c.drawText(group.isEmpty() ? t.name : group, x, 212 * k, panelTitle);
        // Column titles.
        float[] cols = {1300, 1410, 1510, 1610, 1710, 1800};
        String[] heads = {Ui.tr("P", "Pts", "Pts"), Ui.tr("J", "P", "J"), Ui.tr("V", "W", "G"), Ui.tr("E", "D", "E"), Ui.tr("D", "L", "P"), Ui.tr("SG", "GD", "DG")};
        smallDim.setTextAlign(Paint.Align.RIGHT);
        for (int i = 0; i < cols.length; i++) c.drawText(heads[i], cols[i] * k, 212 * k, smallDim);
        smallDim.setTextAlign(Paint.Align.LEFT);
        // A window of 20 rows around the team (a 36-team league phase doesn't fit).
        int at = 0;
        for (int i = 0; i < rows.size(); i++) if (rows.get(i).team.id.equals(teamId)) at = i;
        int first = Math.max(0, Math.min(rows.size() - TABLE_ROWS, at - TABLE_ROWS / 2));
        for (int i = first; i < rows.size() && i < first + TABLE_ROWS; i++) {
            Football.TableRow row = rows.get(i);
            float top = (232 + (i - first) * 39) * k, mid = top + 19 * k;
            boolean mine = row.team.id.equals(teamId);
            if (mine) {
                rect.set((M + 16) * k, top, (R - 16) * k, top + 38 * k);
                fill.setShader(null);
                fill.setColor(light ? 0xFFFFFFFF : 0xFFF5F5F7);
                c.drawRoundRect(rect, 12 * k, 12 * k, fill);
            }
            int on = mine ? INK : ink(0.95f);
            numbers.setColor(on);
            numbersBold.setColor(on);
            text.setColor(on);
            c.drawText(String.valueOf(row.rank == 0 ? i + 1 : row.rank), x + 30 * k, mid + Ui.halfCap(numbers), numbers);
            logo(c, row.team.id, row.team.abbreviation, x + 70 * k, mid, 28 * k);
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

    private void drawMatch(Canvas c) {
        header(c, matchTitle, matchSide);
        Football.Match m = details != null && details.match != null ? details.match : focusMatch;
        panel(c, M, 160, R, 370);
        float cx = w / 2f;
        if (m == null) {
            dim.setTextAlign(Paint.Align.CENTER);
            c.drawText(schedule == null ? Ui.tr("Carregando…", "Loading…", "Cargando…") : Ui.tr("Nenhum jogo.", "No match.", "Ningún partido."),
                    cx, 280 * k, dim);
            dim.setTextAlign(Paint.Align.LEFT);
            return;
        }
        float side = 420 * k, cy = 250 * k;
        logo(c, m.home.id, m.home.abbreviation, cx - side - 150 * k, cy + 15 * k, 90 * k);
        logo(c, m.away.id, m.away.abbreviation, cx + side + 150 * k, cy + 15 * k, 90 * k);
        c.drawText(m.home.label(), cx - side + 40 * k, cy + 25 * k, name);
        c.drawText(m.away.label(), cx + side - 40 * k, cy + 25 * k, name);
        c.drawText(matchScore, cx, cy + Ui.halfCap(big), big);
        stateBadge(c, m, matchState, (R - 40) * k, 206 * k);
        drawEvents(c, m);
        drawStats(c);
        drawLineups(c, m);
    }

    private void drawEvents(Canvas c, Football.Match m) {
        panel(c, M, 400, 660, 1028);
        float x = (M + 36) * k;
        c.drawText(Ui.tr("Gols e cartões", "Goals and cards", "Goles y tarjetas"), x, 452 * k, panelTitle);
        if (eventRows.isEmpty()) {
            c.drawText(details == null ? (Football.PRE.equals(m.state) ? Ui.tr("O jogo ainda não começou.", "It hasn't started yet.",
                    "Aún no empezó.") : Ui.tr("Carregando…", "Loading…", "Cargando…")) : Ui.tr("Nenhum.", "None.", "Ninguno."), x, 510 * k, dim);
            return;
        }
        for (int i = 0; i < eventRows.size() && i < 12; i++) {
            String[] e = eventRows.get(i);
            float mid = (492 + i * 44) * k;
            c.drawText(e[0], x, mid + Ui.halfCap(smallDim), smallDim);
            int kind = Integer.parseInt(e[3]);
            float ix = x + 86 * k;
            fill.setShader(null);
            if (kind == Football.Event.GOAL) {
                fill.setColor(ink(0.9f));
                c.drawCircle(ix, mid, 9 * k, fill);
            } else {
                fill.setColor(kind == Football.Event.RED ? 0xFFFF3B30 : 0xFFFFCC00);
                rect.set(ix - 7 * k, mid - 10 * k, ix + 7 * k, mid + 10 * k);
                c.drawRoundRect(rect, 2 * k, 2 * k, fill);
            }
            c.drawText(e[1], ix + 24 * k, mid + Ui.halfCap(small), small);
            smallDim.setTextAlign(Paint.Align.RIGHT);
            c.drawText(e[2], (660 - 36) * k, mid + Ui.halfCap(smallDim), smallDim);
            smallDim.setTextAlign(Paint.Align.LEFT);
        }
    }

    private void drawStats(Canvas c) {
        panel(c, 690, 400, 1270, 1028);
        float x = (690 + 36) * k, r = (1270 - 36) * k, cx = (690 + 1270) / 2f * k;
        c.drawText(Ui.tr("Números", "Numbers", "Números"), x, 452 * k, panelTitle);
        if (details == null || details.stats.isEmpty()) {
            c.drawText(details == null ? "" : Ui.tr("Sem números deste jogo.", "No numbers for this match.", "Sin números de este partido."), x, 510 * k, dim);
            return;
        }
        smallDim.setTextAlign(Paint.Align.CENTER);
        for (int i = 0; i < details.stats.size() && i < 10; i++) {
            String[] s = details.stats.get(i);
            float mid = (500 + i * 52) * k;
            c.drawText(s[1], x, mid + Ui.halfCap(small), small);
            c.drawText(s[0], cx, mid + Ui.halfCap(smallDim), smallDim);
            small.setTextAlign(Paint.Align.RIGHT);
            c.drawText(s[2], r, mid + Ui.halfCap(small), small);
            small.setTextAlign(Paint.Align.LEFT);
        }
        smallDim.setTextAlign(Paint.Align.LEFT);
    }

    private void drawLineups(Canvas c, Football.Match m) {
        panel(c, 1300, 400, R, 1028);
        float x = (1300 + 36) * k, half = (R - 1300 - 72) / 2f * k;
        c.drawText(Ui.tr("Escalações", "Line-ups", "Alineaciones"), x, 452 * k, panelTitle);
        if (details == null || details.homeLineup.isEmpty()) {
            c.drawText(details == null ? "" : Ui.tr("Ainda sem escalação.", "No line-ups yet.", "Aún sin alineaciones."), x, 510 * k, dim);
            return;
        }
        lineup(c, m.home.abbreviation + "  " + details.homeFormation, details.homeLineup, x);
        lineup(c, m.away.abbreviation + "  " + details.awayFormation, details.awayLineup, x + half + 12 * k);
    }

    private void lineup(Canvas c, String head, List<String> players, float x) {
        c.drawText(head.trim(), x, 496 * k, smallDim);
        for (int i = 0; i < players.size() && i < 11; i++) {
            String p = players.get(i);
            // The surname is what fits a narrow column: "10  Arrascaeta".
            int space = p.indexOf("  ");
            String jersey = space < 0 ? "" : p.substring(0, space);
            String full = space < 0 ? p : p.substring(space + 2);
            String last = full.contains(" ") ? full.substring(full.lastIndexOf(' ') + 1) : full;
            c.drawText((jersey.isEmpty() ? "" : jersey + "  ") + last, x, (540 + i * 44) * k, small);
        }
    }
}
