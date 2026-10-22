package io.freemytv;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.media.tv.TvInputManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * The home screen, styled after the Apple TV app with the macOS 27 glass:
 *  - at the top left, the "For You" title and the tabs (Explore, Movies, Shows); at the top right, icon-only glass
 *    buttons (Live TV, HDMI Inputs, Settings), with the focused one's name below it, and the small crypto quotes
 *    (logo + price) at the right end of the tab row;
 *  - three cards with the titles recommended for right now in the selected tab (Recs, from TMDB); the background is
 *    the first card's photo, heavily blurred and almost white (Backdrop); without one, a light gradient;
 *  - at the bottom, the glass shelf with the apps (icons in each brand's color, like the tvOS Dock; the focused one
 *    grows);
 *  - ↓ on the shelf: everything moves up and the bottom drawer appears (inputs, shortcuts, weather for the next hours
 *    and quotes with the 24 h change); the background lightens (darkens at night). ↑ or Back closes it; ↓ again
 *    opens All Apps, and ↑ from its first row comes back to the home screen;
 *  - from 18:00 to 6:00, a night look: dark background, smoked glass and white text.
 * Measurements are in a 1920×1080 space (k = width / 1920). The screen is split into layers so the TV's weak GPU only
 * redraws what changed.
 */
final class HomeView extends ViewGroup implements Choreographer.FrameCallback, HomeData.Listener, Recs.Listener {
    // tvOS grid (Human Interface Guidelines): content inside the safe area, 80 px from the sides and 60 from top and
    // bottom; 40 px between items; 7 columns of 217 px (the apps). The shelf glass extends 36 px past the tiles (the
    // same padding on all four sides) and the tile corners are concentric with it (glass radius = radius + padding).
    private static final float MARGIN = 80, CONTENT_R = 1840;
    // Hero, anchored at the bottom (a two-line title grows upward).
    private static final float HERO_TITLE_W = 900;
    // Shelf: tiles with corners concentric with the glass (glass radius = tile radius + padding).
    // Shelf like the tvOS 27 Dock: 5:3 icons (210 × 126) in each brand's color, 48 apart, and the glass bar hugging
    // them with 40 of padding (the focused icon grows 20% without touching its edge).
    private static final float SHELF_PAD = 40, TILE_GAP = 48;
    // 16:9 like the apps' TV banners (the art isn't stretched), as tvOS' Dock icons keep their shape.
    private static final float TILE_RADIUS = 22, TILE_ASPECT = 16f / 9;
    // Tile focus (Apple TV style): it grows from the center and its shadow drops (it "rises" toward the viewer).
    private static final float FOCUS_GROW = 0.2f;
    // Corner capsule.
    // Corner capsule: the height and top offset of the tvOS tab bar (68 and 46).
    private static final float CAPSULE_TOP = 46, CAPSULE_H = 68, BUTTON = 56;
    // Bottom drawer (measured with it open; everything moves up by SHIFT).
    private static final float SHIFT = 560, ROW_TITLE = 518, ROW_TOP = 540, ROW_H = 126, CARDS_TOP = 708, CARDS_H = 300;
    // "All Apps" sheet.
    private static final int COLUMNS = 5, VISIBLE_ROWS = 3;
    private static final float GRID_W = 300, GRID_H = 169, GRID_GAP = 40, GRID_ROW = 239, GRID_TOP = 196, GRID_RADIUS = 22;
    private static final int SMOKE = 0xFF111113, INK = 0xFF1C1C1E, WHITE = 0xFFF5F5F7;
    private static final int UP_COLOR = 0xFF30D158, DOWN_COLOR = 0xFFFF453A;
    private static final long TOAST_MS = 6000, FADE_MS = 800;
    /** Blur radii of the shadow and shadowDeep paints (Shadows needs them to draw the same blur on older TVs). */
    private static final float SHADOW_BLUR = 26, DEEP_BLUR = 30;
    private static final int SKY = 0, HERO = 1, STATUS = 2, PLATTER = 3, TILES = 4, DRAWER = 5, SHEET = 6, TOAST = 7, MENU = 8, LAUNCH = 9;
    private static final int Z_STATUS = 0, Z_HERO = 1, Z_SHELF = 2, Z_DRAWER = 3;
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor();
    // Light design (the L_ constants): title, tab bar, three recommendation cards and the shelf.
    // The tab bar is a 68 px capsule.
    private static final float L_TITLE_BASE = 130, L_TABBAR_TOP = 158, L_TABBAR_H = 68;
    // One big 16:9 card and two columns of two small ones (the photos are 16:9: nothing cut), together as wide as the
    // content and centered between the tab bar and the shelf. Each title's logo is on its photo, no text below.
    private static final float L_CARD_GAP = 48, L_BUTTON = 68, L_BUTTON_GAP = 20, L_BUTTON_CY = 103;
    /** Card slots: 0 = the big one; 1 and 2 = the first column of small ones (top, bottom); 3 and 4 = the second. */
    private static final int SLOTS = Recs.ON_SCREEN;
    private static final float L_PLATTER_TOP = 814, L_TILE_H = 126;
    private static final int L_INK = 0xFF1D1D1F, L_DIM = 0xFF6E6E73, L_FAINT = 0xFF8E8E93;
    // The first tab is what to continue (the apps' Watch Next lists, WatchNext); the others, the picks (Recs).
    private static final String[] TABS = {"Continuar assistindo", "Filmes", "Séries"};
    private static final String[] TABS_EN = {"Continue Watching", "Movies", "Shows"};
    private static final String[] TABS_ES = {"Seguir viendo", "Películas", "Series"};
    private static final String[] TAB_KIND = {null, "movie", "tv"};
    private static final int Z_TOP = 4, Z_CARDS = 5;

    private final Activity activity;
    private final Scene scene = new Scene();
    private final HomeData data;
    private final Recs recs;
    private final Click click;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SharedPreferences ui;
    private final TvInputManager tvInputs;
    private final Spoken spoken = new Spoken(this, new Spoken.Source() {
        @Override public void items(List<Integer> out) { a11yItems(out); }
        @Override public String text(int id) { return a11yText(id); }
        @Override public boolean bounds(int id, Rect out) { return a11yBounds(id, out); }
        @Override public int focused() { return a11yFocused(); }
        @Override public void focus(int id) { a11yFocus(id); }
        @Override public void click(int id) { press(); }
    });
    private final RectF a11yRect = new RectF();
    private final Layer sky, heroLayer, status, platter, tiles, drawerLayer, sheetLayer, menuLayer, toastLayer, launchLayer;
    private int zone = Z_SHELF;

    // Night look (like Apple's "Automatic" appearance): darker from 18:00 to 6:00, with a 2 s transition.
    private static final long NIGHT_MS = 2000;
    private boolean nightTarget;
    private float night, nightFrom;     // 0 = day, 1 = night
    private long nightAt;
    private final int[] glassNight = new int[Backdrop.GW * Backdrop.GH], frostNight = new int[Backdrop.FW * Backdrop.FH];
    private final int[] neutralGlassPx = new int[Backdrop.GW * Backdrop.GH], neutralFrostPx = new int[Backdrop.FW * Backdrop.FH];
    private boolean nightPair;          // the glass holds both photos (day and night), blended by `night`
    private Shader nightGradient;

    // At the top: tabs (0 to 2) and icon-only buttons (3 to 5); the three cards
    private int tab, topIndex, cardIndex;
    private List<Recs.Pick> cards = new ArrayList<>();      // the ones on screen (up to 3)
    // The tab's whole list (the top can have up to 20) and where the three on screen start in it; "4–6 of 10".
    private List<Recs.Pick> allCards = new ArrayList<>();
    // Continue watching, read in the background on resume and every tick; `watchingKey` tells when it changed.
    private List<Recs.Pick> watching;
    private String watchingKey = "";
    private boolean watchAllowed = true;
    private static long askedWatchAt;
    private int cardOffset;
    private String cardCounter = "";
    // The top row: the three tabs, then the icon-only buttons (Search, Live TV, HDMI Inputs, TV Settings).
    private static final int BUTTONS = 4, TOP_ITEMS = 3 + BUTTONS;
    private final Spring[] topFocus = new Spring[TOP_ITEMS];
    private final RectF[] topRects = new RectF[TOP_ITEMS];
    private final Spring[] cardFocus = new Spring[SLOTS];
    private final RectF[] cardRects = new RectF[SLOTS];
    // The list moving along (→ past the last column): each title glides from its slot in the `shiftFrom` offset to
    // its slot in `shiftTo`, as cardShift goes from 0 to 1.
    private final Spring cardShift = new Spring(0.26f, 0.9f, 1);
    private int shiftFrom, shiftTo;
    private final RectF slotFrom = new RectF(), slotTo = new RectF();
    private final List<HomeApps.Item> lightButtons = new ArrayList<>();
    private Paint lTitle, lTab, lTabOn, lCardMeta, lQuote, lTip, onPhotoTitle, onPhotoMeta, badgePaint;
    private String badgeText = "";
    private float badgeWidth;
    private String heading = "";
    private float headingWidth;
    private float headingBearing;
    private Shader lightGradient;

    // Shelf
    private List<HomeApps.Item> shelf = new ArrayList<>();
    private Spring[] tileFocus = new Spring[0];
    private RectF[] tileRects = new RectF[0];
    private final Map<String, Bitmap> crispLogos = new HashMap<>();   // logos already sized for the focused tile
    /** Each app's tile like its Apple TV icon: brand colors (top, bottom) and logo (0 = as is, 1 = white, 2 = dark). */
    private static final Map<String, int[]> TILE_STYLE = new HashMap<>();
    static {
        TILE_STYLE.put("netflix", new int[] {0xFF0A0A0A, 0xFF000000, 0});
        TILE_STYLE.put("youtube", new int[] {0xFF1C1C1E, 0xFF09090A, 0});
        TILE_STYLE.put("globoplay", new int[] {0xFFFF8A1F, 0xFFFF2D5A, 1});
        TILE_STYLE.put("prime", new int[] {0xFF2AA8FF, 0xFF0A68D4, 1});
        TILE_STYLE.put("disney", new int[] {0xFF173584, 0xFF08143F, 0});
        TILE_STYLE.put("max", new int[] {0xFF5C30E8, 0xFF221684, 0});
        TILE_STYLE.put("appletv", new int[] {0xFF1C1C1E, 0xFF000000, 0});
    }
    private final android.graphics.PorterDuffColorFilter whiteLogo =
            new android.graphics.PorterDuffColorFilter(0xFFFFFFFF, android.graphics.PorterDuff.Mode.SRC_IN);
    private final RectF platterRect = new RectF();
    private int shelfIndex;

    // Corner capsule
    private List<HomeApps.Item> statusItems = new ArrayList<>();
    private Spring[] statusFocus = new Spring[0];
    private RectF[] statusRects = new RectF[0];
    private final RectF capsuleRect = new RectF();
    private float capsuleInfoW;
    private int statusIndex;

    // Hero and background
    private List<Recs.Pick> picks = new ArrayList<>();
    private int pickIndex, heroSlide = 1;
    private Hero hero, heroFrom;
    private long heroFadeAt, bgFadeAt;
    private final Spring heroFocus = new Spring(0.2f, 0.85f, 0);
    private final RectF heroButton = new RectF();
    private Backdrop bgFrom, bgTo;     // null = gradient (no photo)
    private final int[] frostMix = new int[Backdrop.FW * Backdrop.FH];

    // Bottom drawer
    private List<HomeApps.Item> drawerItems = new ArrayList<>();
    private int drawerInputs;          // how many leading items are inputs (the rest are shortcuts)
    private Spring[] drawerFocus = new Spring[0];
    private RectF[] drawerRects = new RectF[0];
    private final RectF weatherCard = new RectF(), quotesCard = new RectF();
    private final Spring drawer = new Spring(0.34f, 0.92f, 0);
    private boolean drawerOpen;
    private int drawerIndex;

    // "All Apps" sheet
    private List<HomeApps.Item> others = new ArrayList<>();
    private Spring[] gridFocus = new Spring[0];
    private String[] gridLabels;
    private final Spring sheet = new Spring(0.28f, 0.9f, 0);
    private final Spring scroll = new Spring(0.26f, 1f, 0);
    private final RectF focusedCell = new RectF();
    private boolean sheetOpen;
    private int gridIndex;

    // Toasts
    private final ArrayDeque<String> toasts = new ArrayDeque<>();
    private StaticLayout toast;
    private long toastAt;

    // Frames
    private boolean running, tilesDirty, statusDirty, heroDirty, drawerDirty, gridDirty, motionDirty = true;
    private boolean drawerWasMoving;
    private long lastNanos, resumedAt, lastStep;
    private int w, h;
    private float k = 1;
    private String clockText = "";
    private long clockMinute = -1;
    private boolean okDown, backDown;

    private Paint labelPaint, metaPaint, clockPaint, buttonPaint, capsuleText, tipPaint, quotePaint, staleQuote;
    private Paint rowTitle, drawerLabel, cardTitle, cardText, cardDim, cardDimRight, bigTemp, hourPaint, hourTemp;
    private Paint pricePaint, changePaint, sheetTitle;
    private TextPaint titlePaint, gridLabel, toastPaint;
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint imagePaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowSoft = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowDeep = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Shadows shadows = new Shadows();
    private final Map<Bitmap, BitmapShader> imageShaders = new WeakHashMap<>();
    private final Matrix imageMatrix = new Matrix();
    private final Paint imageFill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint veil = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tileFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF tmp = new RectF(), cell = new RectF(), screen = new RectF();

    /** What the hero shows: a recommendation or, without any, the date, the time and the weather. */
    private static final class Hero {
        String label = "", title = "", meta = "";
        boolean clock, button;
        int dots, dot;
        StaticLayout titleLayout;

        boolean sameText(Hero o) {
            return o != null && clock == o.clock && label.equals(o.label) && title.equals(o.title) && meta.equals(o.meta);
        }
    }

    /** One layer of the screen. It draws in full-screen coordinates: the glass needs each pixel's real position. */
    private final class Layer extends View {
        private final int kind;

        Layer(Context c, int kind) {
            super(c);
            this.kind = kind;
        }

        @Override protected void onDraw(Canvas c) {
            if (labelPaint == null) return;
            c.translate(-getLeft(), -getTop());
            // The layer may be shifted (bottom drawer): the glass reads the background from where it really is. The
            // drawer and the top buttons are always drawn as if resting in place (they are only visible there and are
            // not redrawn while they move).
            scene.setOffset(0, kind == DRAWER || kind == STATUS ? 0 : getTranslationY());
            switch (kind) {
                case SKY: drawSky(c); break;
                case HERO: drawLightTop(c); break;
                case STATUS: drawLightButtons(c); break;
                case PLATTER: drawPlatter(c); break;
                case TILES: drawTiles(c); break;
                case DRAWER: drawDrawer(c); break;
                case SHEET: drawSheet(c); break;
                case MENU: drawMenu(c); break;
                case LAUNCH: drawLaunch(c); break;
                default: drawToast(c); break;
            }
            scene.setOffset(0, 0);
        }
    }

    private final TvInputManager.TvInputCallback inputCallback = new TvInputManager.TvInputCallback() {
        @Override public void onInputStateChanged(String inputId, int state) { reload(); }
        @Override public void onInputAdded(String inputId) { reload(); }
        @Override public void onInputRemoved(String inputId) { reload(); }
    };

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            data.refreshIfStale();
            recs.refreshIfStale();
            checkNotices();
            updatePalette();
            checkNight(true);
            updatePicks();      // five new ones every hour
            updateGreeting();
            loadWatching();
            wake();
            handler.postDelayed(this, 15_000);
        }
    };

    HomeView(Activity a) {
        super(a);
        activity = a;
        data = HomeData.get(a);
        recs = Recs.get(a);
        click = new Click(a);
        ui = a.getSharedPreferences("home-ui", Context.MODE_PRIVATE);
        watching = WatchNext.fromJson(ui.getString("watching", "[]"));   // the last list, at once; the new one follows
        tvInputs = (TvInputManager) a.getSystemService(Context.TV_INPUT_SERVICE);
        setFocusable(true);
        sky = new Layer(a, SKY);
        heroLayer = new Layer(a, HERO);
        status = new Layer(a, STATUS);
        platter = new Layer(a, PLATTER);
        tiles = new Layer(a, TILES);
        drawerLayer = new Layer(a, DRAWER);
        sheetLayer = new Layer(a, SHEET);
        menuLayer = new Layer(a, MENU);
        toastLayer = new Layer(a, TOAST);
        launchLayer = new Layer(a, LAUNCH);
        // Hardware layers on the GPU: only the one that changed is redrawn; moving, fading and scaling stay cheap.
        // Not on Android 9 to 11: on older GPUs (a Mi Box 3's Mali-450) redrawing and compositing a big layer on every
        // frame cost more than drawing straight to the screen (measured: focus on the picks 33 → 12 ms a frame, the
        // drawer 19 → 17 ms).
        for (View v : new View[] {heroLayer, status, platter, tiles, drawerLayer, sheetLayer, menuLayer}) v.setLayerType(Ui.LEGACY ? LAYER_TYPE_NONE : LAYER_TYPE_HARDWARE, null);
        for (View v : new View[] {sky, heroLayer, status, platter, tiles, drawerLayer, sheetLayer, menuLayer, toastLayer, launchLayer}) {
            v.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);   // TalkBack reads the items (Spoken)
            addView(v);
        }
        setContentDescription("Free My TV");
        drawerLayer.setVisibility(INVISIBLE);
        for (int i = 0; i < TOP_ITEMS; i++) {
            topFocus[i] = new Spring(0.2f, 0.85f, 0);
            topRects[i] = new RectF();
        }
        for (int i = 0; i < SLOTS; i++) {
            cardFocus[i] = new Spring(0.22f, 0.78f, 0);
            cardRects[i] = new RectF();
        }
        makeLightButtons();
        recs.setLight(true);
        checkNight(false);
        sheetLayer.setVisibility(INVISIBLE);
        menuLayer.setVisibility(INVISIBLE);
        launchLayer.setVisibility(INVISIBLE);
        scene.setMotion(0.6f, 0f);
        updatePalette();
        // First time: the shelf right away (a cold start opens with the apps already there; their art comes from disk);
        // missing logos, the inputs and the shortcuts come from reload(), in the background, before anyone gets there:
        // asking the TV for its inputs here would hold back the first frame.
        setShelf(HomeApps.shelf(a, false));
        setStatusItems(new ArrayList<>());
        setDrawerItems(new ArrayList<>());
    }

    // MARK: - Lifecycle (called by HomeActivity)

    void resume() {
        if (running) return;
        running = true;
        // Settings read once here, not while drawing (the screen is rebuilt when they change).
        showClock = Prefs.topClock();
        showWeatherOn = Prefs.weather();
        showQuotes = Prefs.quotes();
        checkNight(false);
        resumedAt = SystemClock.uptimeMillis();
        lastNanos = 0;
        lastStep = 0;
        data.addListener(this);
        recs.addListener(this);
        data.refreshIfStale();
        recs.refreshIfStale();
        if (tvInputs != null) tvInputs.registerCallback(inputCallback, handler);
        handler.postDelayed(tick, 15_000);
        checkNotices();
        reload();
        askWatchPermission();
        loadWatching();
        launchAt = 0;   // back from the app the transition opened
        launchLayer.setVisibility(INVISIBLE);
        updateGreeting();
        updatePalette();
        updatePicks();
        hideMenu();   // back from App info or the uninstall question
        if (sheetOpen) closeSheet();
        sheet.snap(sheet.target);
        motionDirty = true;
        invalidateAll();
        wake();
        spoken.readAgain();
    }

    void pause() {
        if (!running) return;
        running = false;
        Choreographer.getInstance().removeFrameCallback(this);
        handler.removeCallbacks(idleWake);
        frameQueued = false;
        handler.removeCallbacks(tick);
        data.removeListener(this);
        recs.removeListener(this);
        if (tvInputs != null) tvInputs.unregisterCallback(inputCallback);
    }

    void destroy() {
        pause();
        click.release();
    }

    /** Starting state (Home pressed while the home screen is showing): first app focused, drawer and sheet closed. */
    void reset(boolean animated) {
        hideMenu();
        zone = Z_SHELF;
        shelfIndex = 0;
        sheetOpen = false;
        drawerOpen = false;
        if (animated) {
            sheet.target = 0;
            drawer.target = 0;
        } else {
            sheet.snap(0);
            drawer.snap(0);
        }
        if (pickIndex != 0 && !picks.isEmpty()) {
            pickIndex = 0;
            updatePicks();
        }
        if (tab != 0) selectTab(0);
        retarget(!animated);
        motionDirty = true;
    }

    /** The three corner buttons, with their names in the current language. */
    private void makeLightButtons() {
        lightButtons.clear();
        lightButtons.add(HomeApps.action(HomeApps.SEARCH, "search", Ui.tr("Buscar", "Search", "Buscar"), "search"));
        lightButtons.add(HomeApps.action(HomeApps.LIVE_TV, "live", Ui.tr("TV aberta", "Live TV"), "live_tv"));
        lightButtons.add(HomeApps.action(HomeApps.INPUTS, "inputs", Ui.tr("Entradas HDMI", "HDMI Inputs"), "hdmi"));
        // The gear opens the TV's settings; Free My TV's own are first in More Apps.
        lightButtons.add(HomeApps.action(HomeApps.ANDROID_SETTINGS, "settings", Ui.tr("Ajustes da TV", "TV Settings"), "settings"));
    }

    /** The title at the top left: "Sua TCL" (the TV's brand, never its model), or "Para você" when it has none. */
    private static String heading() {
        String brand = Ui.brand();
        return brand.isEmpty() ? Ui.tr("Para você", "For You") : Ui.tr("Sua " + brand, "Your " + brand, "Tu " + brand);
    }

    private static String[] tabs() {
        return Ui.es ? TABS_ES : Ui.en ? TABS_EN : TABS;
    }

    @Override public void onData() {
        wake();
        updateGreeting();   // the rain coming, from the new forecast
        updatePalette();
        checkNight(true);
        layoutStatus();
        heroDirty = statusDirty = drawerDirty = true;
        platter.invalidate();
    }

    @Override public void onRecs() {
        wake();
        updatePicks();
    }

    private void invalidateAll() {
        for (View v : new View[] {sky, heroLayer, status, platter, tiles, drawerLayer}) v.invalidate();
    }

    // MARK: - Size and layers

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec);
        float kk = width / 1920f;
        measure(sky, width, height);
        measure(heroLayer, width, Math.round(790 * kk));
        measure(status, Math.round(1160 * kk), Math.round(240 * kk));
        // Sized to what they draw (the glass and its shadow; the grown tiles and their shadows): smaller layers are
        // cheaper for the GPU to composite on every frame of the drawer animation.
        measure(platter, width, Math.round(320 * kk));
        measure(tiles, width, Math.round(270 * kk));
        measure(drawerLayer, width, Math.round(600 * kk));
        measure(sheetLayer, width, height);
        measure(menuLayer, width, height);
        measure(launchLayer, width, height);
        measure(toastLayer, Math.round(1000 * kk), Math.round(220 * kk));
        setMeasuredDimension(width, height);
    }

    private static void measure(View v, int width, int height) {
        v.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int width = r - l, height = b - t;
        float kk = width / 1920f;
        sky.layout(0, 0, width, height);
        place(heroLayer, 0, 0);
        place(status, width - status.getMeasuredWidth(), 0);
        place(platter, 0, Math.round(750 * kk));
        place(tiles, 0, Math.round(790 * kk));
        place(drawerLayer, 0, Math.round(470 * kk));
        sheetLayer.layout(0, 0, width, height);
        sheetLayer.setPivotX(width / 2f);
        sheetLayer.setPivotY(height / 2f);
        menuLayer.layout(0, 0, width, height);
        launchLayer.layout(0, 0, width, height);
        place(toastLayer, (width - toastLayer.getMeasuredWidth()) / 2, 0);
    }

    private static void place(View v, int x, int y) {
        v.layout(x, y, x + v.getMeasuredWidth(), y + v.getMeasuredHeight());
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        w = width;
        h = height;
        k = w / 1920f;
        screen.set(0, 0, w, h);
        scene.setSize(w, h);
        makePaints();
        gridLabels = null;
        clockMinute = -1;
        updateClock();
        layoutShelf();
        layoutStatus();
        layoutDrawer();
        layoutLight();
        updateGreeting();
        hero = null;
        heroFrom = null;
        updatePicks();
        motionDirty = true;
    }

    private void makePaints() {
        labelPaint = Ui.text(Ui.medium, 25 * k, 0xC7FFFFFF);
        labelPaint.setLetterSpacing(0.1f);
        titlePaint = new TextPaint(Ui.text(Ui.displayMedium, 76 * k, 0xFFFFFFFF));
        titlePaint.setLetterSpacing(0.005f);
        metaPaint = Ui.text(Ui.medium, 29 * k, 0xD1FFFFFF);
        metaPaint.setLetterSpacing(0.014f);
        clockPaint = Ui.numbers(Ui.thin, 176 * k, 0xFFFFFFFF);
        clockPaint.setLetterSpacing(-0.01f);
        for (Paint p : new Paint[] {labelPaint, titlePaint, metaPaint, clockPaint}) p.setShadowLayer(22 * k, 0, 2 * k, 0x59000000);
        lTitle = Ui.text(Ui.displaySemibold, 76 * k, L_INK);
        lTitle.setLetterSpacing(0.004f);
        heading = heading();
        headingBearing = Ui.leftBearing(lTitle, heading);
        headingWidth = lTitle.measureText(heading);
        greetingPaint = Ui.text(Ui.medium, 30 * k, L_DIM);
        greetingText = greetingFrom = "";
        lTab = Ui.text(Ui.medium, 30 * k, L_DIM);
        lTabOn = Ui.text(Ui.semibold, 30 * k, L_INK);
        // On the photos (over their dark bottom): white in either look, with a soft shadow for the bright ones.
        onPhotoTitle = Ui.text(Ui.displaySemibold, 40 * k, WHITE);
        onPhotoTitle.setShadowLayer(10 * k, 0, 1 * k, 0x66000000);
        onPhotoMeta = Ui.text(Ui.medium, 25 * k, 0xD9FFFFFF);
        onPhotoMeta.setShadowLayer(8 * k, 0, 1 * k, 0x66000000);
        badgePaint = Ui.text(Ui.semibold, 19 * k, 0xF2FFFFFF);
        badgePaint.setLetterSpacing(0.02f);
        badgeText = Ui.tr("Sugestão", "Suggested", "Sugerencia");
        badgeWidth = badgePaint.measureText(badgeText);
        cardTexts.clear();
        lCardMeta = Ui.text(Ui.medium, 25 * k, L_DIM);
        lQuote = Ui.numbers(Ui.regular, 21 * k, L_FAINT);
        lTip = Ui.text(Ui.semibold, 25 * k, L_INK);
        lTip.setTextAlign(Paint.Align.CENTER);
        menuText = Ui.text(Ui.medium, 29 * k, L_INK);
        menuTextHalfCap = Ui.halfCap(menuText);
        menuHeader = Ui.text(Ui.semibold, 23 * k, L_DIM);
        menuHeaderHalfCap = Ui.halfCap(menuHeader);
        lightGradient = null;
        buttonPaint = Ui.text(Ui.semibold, 29 * k, INK);
        capsuleText = Ui.numbers(Ui.medium, 29 * k, 0xF2FFFFFF);
        tipPaint = Ui.text(Ui.semibold, 25 * k, 0xF2FFFFFF);
        tipPaint.setTextAlign(Paint.Align.CENTER);
        tipPaint.setShadowLayer(16 * k, 0, 1 * k, 0x99000000);
        // Quotes: a subtle detail, smaller and dimmer than the rest.
        quotePaint = Ui.numbers(Ui.regular, 21 * k, 0xB8FFFFFF);
        quotePaint.setShadowLayer(14 * k, 0, 1 * k, 0x99000000);
        staleQuote = Ui.text(Ui.medium, 21 * k, 0x99FFFFFF);
        staleQuote.setTextAlign(Paint.Align.RIGHT);
        rowTitle = Ui.text(Ui.semibold, 25 * k, 0xB3FFFFFF);
        drawerLabel = Ui.text(Ui.medium, 23 * k, 0xEBFFFFFF);
        drawerLabel.setTextAlign(Paint.Align.CENTER);
        cardTitle = Ui.text(Ui.semibold, 25 * k, 0xB3FFFFFF);
        cardText = Ui.text(Ui.medium, 29 * k, 0xF2FFFFFF);
        cardDim = Ui.numbers(Ui.medium, 23 * k, 0xADFFFFFF);
        cardDimRight = Ui.text(Ui.medium, 21 * k, 0x99FFFFFF);
        cardDimRight.setTextAlign(Paint.Align.RIGHT);
        bigTemp = Ui.numbers(Ui.light, 100 * k, 0xFFFFFFFF);
        hourPaint = Ui.numbers(Ui.medium, 22 * k, 0xADFFFFFF);
        hourPaint.setTextAlign(Paint.Align.CENTER);
        hourTemp = Ui.numbers(Ui.medium, 26 * k, 0xF2FFFFFF);
        hourTemp.setTextAlign(Paint.Align.CENTER);
        pricePaint = Ui.numbers(Ui.medium, 26 * k, 0xF2FFFFFF);
        pricePaint.setTextAlign(Paint.Align.RIGHT);
        changePaint = Ui.numbers(Ui.semibold, 21 * k, 0xFFFFFFFF);
        changePaint.setTextAlign(Paint.Align.CENTER);
        sheetTitle = Ui.text(Ui.displaySemibold, 40 * k, 0xFFFFFFFF);
        sheetTitle.setTextAlign(Paint.Align.CENTER);
        gridLabel = new TextPaint(Ui.text(Ui.medium, 24 * k, 0x99FFFFFF));
        gridLabel.setTextAlign(Paint.Align.CENTER);
        toastPaint = new TextPaint(Ui.text(Ui.medium, 26 * k, 0xF2FFFFFF));
        shadow.setMaskFilter(new BlurMaskFilter(SHADOW_BLUR * k, BlurMaskFilter.Blur.NORMAL));
        shadowSoft.setMaskFilter(new BlurMaskFilter(12 * k, BlurMaskFilter.Blur.NORMAL));
        shadowDeep.setMaskFilter(new BlurMaskFilter(DEEP_BLUR * k, BlurMaskFilter.Blur.NORMAL));
        halo.setMaskFilter(new BlurMaskFilter(22 * k, BlurMaskFilter.Blur.NORMAL));
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(2 * k);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(2.5f * k);
    }

    // MARK: - Frames: 60 per second while something moves; nothing is redrawn when still

    @Override public void doFrame(long frameTimeNanos) {
        frameQueued = false;
        if (!running) return;
        float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.05f, (frameTimeNanos - lastNanos) / 1e9f);
        lastNanos = frameTimeNanos;
        long up = SystemClock.uptimeMillis();

        boolean tilesMoving = step(tileFocus, dt);
        boolean statusMoving = step(statusFocus, dt);
        boolean heroMoving = heroFocus.step(dt);
        boolean topMoving = step(topFocus, dt), cardsMoving = step(cardFocus, dt) | cardShift.step(dt);
        if (topMoving) status.invalidate();
        boolean extras = tickExtras(up);
        heroMoving |= topMoving | cardsMoving | extras;
        boolean drawerItemsMoving = step(drawerFocus, dt);
        boolean gridMoving = step(gridFocus, dt) | scroll.step(dt);
        boolean drawerMoving = drawer.step(dt);
        boolean sheetMoving = sheet.step(dt);
        boolean toastFading = tickToast(up);
        boolean heroFading = tickHeroFade(up);
        boolean bgFading = tickBackgroundFade(up);
        boolean nightMoving = tickNight(up);
        boolean menuMoving = menu.step(dt), menuRowMoving = menuRow.step(dt);

        if (drawerMoving || sheetMoving || motionDirty) updateMotion();
        // Opening and closing the drawer only moves the cached layers: redrawing the glass on every frame made the TV
        // drop frames (measured: ~50 ms of GPU per frame). The app tiles have no glass; the top buttons fade out as
        // they rise; the drawer is drawn resting in place; the glass shelf is redrawn once the drawer stops, so the
        // glass shows the background where it ended up.
        if (tilesMoving || tilesDirty) tiles.invalidate();
        if (statusMoving || statusDirty || bgFading) status.invalidate();
        if (heroMoving || heroDirty || heroFading) heroLayer.invalidate();
        if (drawerItemsMoving || drawerDirty || bgFading) drawerLayer.invalidate();
        if (gridMoving || gridDirty) sheetLayer.invalidate();
        if (drawerMoving || bgFading) sky.invalidate();
        if (bgFading || (drawerWasMoving && !drawerMoving)) platter.invalidate();
        if (menuMoving || menuDirty) updateMenuMotion();
        if (menuRowMoving || menuDirty) menuLayer.invalidate();
        drawerWasMoving = drawerMoving;
        tilesDirty = statusDirty = heroDirty = drawerDirty = gridDirty = motionDirty = menuDirty = false;
        if (updateClock()) {
            layoutStatus();
            status.invalidate();
            if (showClock) heroLayer.invalidate();   // the clock at the top right
            if (hero != null && hero.clock) {
                refreshHero(false);
                heroLayer.invalidate();
            }
        }

        // Busy: something is moving, or the sheet or the drawer is open.
        boolean busy = tilesMoving || statusMoving || heroMoving || drawerItemsMoving || gridMoving || drawerMoving
                || sheetMoving || toastFading || heroFading || bgFading || nightMoving || sheetOpen || sheet.value > 0.001f
                || drawerOpen || drawer.value > 0.001f || menuMoving || menuRowMoving || extras || launchAt != 0;
        // Still: no frames until something happens (a key, new data, a toast wakes it at once); a slow check meanwhile
        // catches the minute of the clock and anything that changed without waking it.
        if (frameQueued) return;   // something in this frame already woke the next one
        if (busy) {
            frameQueued = true;
            Choreographer.getInstance().postFrameCallback(this);
        } else {
            handler.postDelayed(idleWake, IDLE_CHECK_MS);
        }
    }

    private static final long IDLE_CHECK_MS = 1000;   // the clock's minute; keys and data wake it at once
    private boolean frameQueued;
    private boolean showClock = true, showWeatherOn = true, showQuotes = true;
    private final Runnable idleWake = this::wake;

    /** Back to 60 frames a second (from the first frame of whatever just started to move). */
    private void wake() {
        if (!running || frameQueued) return;
        handler.removeCallbacks(idleWake);
        frameQueued = true;
        lastNanos = 0;   // the first frame steps 1/60 s, not the time spent still
        Choreographer.getInstance().postFrameCallback(this);
    }

    private static boolean step(Spring[] springs, float dt) {
        boolean moving = false;
        for (Spring s : springs) moving |= s.step(dt);
        return moving;
    }

    /** Drawer and sheet: the layers' positions and opacity (without redrawing their content). */
    private void updateMotion() {
        float d = drawer.value, dc = Ui.clamp(d), s = Ui.clamp(sheet.value);
        float home = 1 - s, top = home * (1 - smoothstep(0, 0.32f, dc));
        float shift = SHIFT * k;
        float lift = -d * shift;
        show(heroLayer, top, lift);
        show(status, top, lift);
        show(platter, home, lift);
        show(tiles, home, lift);
        float da = home * smoothstep(0.1f, 0.6f, dc);
        drawerLayer.setTranslationY((1 - d) * shift);
        drawerLayer.setAlpha(da);
        drawerLayer.setVisibility(da > 0.002f ? VISIBLE : INVISIBLE);
        sheetLayer.setVisibility(s > 0.001f || sheetOpen ? VISIBLE : INVISIBLE);
        sheetLayer.setAlpha(s);
        float scale = 1.04f - 0.04f * s;
        sheetLayer.setScaleX(scale);
        sheetLayer.setScaleY(scale);
    }

    private static void show(View v, float alpha, float translationY) {
        v.setAlpha(alpha);
        v.setTranslationY(translationY);
        v.setVisibility(alpha > 0.002f ? VISIBLE : INVISIBLE);
    }

    private boolean updateClock() {
        long now = System.currentTimeMillis();
        long minute = now / 60_000;
        if (minute == clockMinute || clockPaint == null) return false;
        clockMinute = minute;
        clockText = Ui.clock(now);
        return true;
    }

    private void updatePalette() {
        Shaders.sky(scene.palette, System.currentTimeMillis());
        scene.applyPalette();
    }

    // MARK: - Recommendations: hero and background

    private Recs.Pick currentPick() {
        return picks.isEmpty() ? null : picks.get(Math.min(pickIndex, picks.size() - 1));
    }

    /** This hour's picks: the cards and the background image (called when Recs notifies and every 15 s). */
    private void updatePicks() {
        if (labelPaint == null) return;
        updateCards();
    }

    private static boolean samePicks(List<Recs.Pick> a, List<Recs.Pick> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!Recs.key(a.get(i)).equals(Recs.key(b.get(i)))) return false;
        return true;
    }

    /**
     * Rebuilds the hero. With `animate`, a different title slides in while the previous one slides out; details that
     * arrive later (genre, runtime) just appear.
     */
    private void refreshHero(boolean animate) {
        Hero next = makeHero();
        if (next.sameText(hero) && hero.dots == next.dots && hero.dot == next.dot && hero.button == next.button) return;
        boolean other = hero != null && (hero.clock != next.clock || !hero.title.equals(next.title));
        if (animate && other) {
            heroFrom = hero;
            heroFadeAt = SystemClock.uptimeMillis();
        }
        boolean layoutChanged = hero == null || hero.clock != next.clock;
        hero = next;
        heroDirty = true;
        if (layoutChanged) layoutStatus();
    }

    private Hero makeHero() {
        Hero h = new Hero();
        Recs.Pick p = currentPick();
        if (p == null) {
            h.clock = true;
            h.label = Ui.date(System.currentTimeMillis()).toUpperCase(Ui.locale());
            return h;
        }
        h.label = Recs.periodLabel();
        h.title = p.title == null ? "" : p.title;
        h.meta = p.meta == null ? "" : p.meta;
        h.button = p.pkg != null;
        h.dots = picks.size();
        h.dot = pickIndex;
        h.titleLayout = StaticLayout.Builder.obtain(h.title, 0, h.title.length(), titlePaint, Math.max(1, Math.round(HERO_TITLE_W * k)))
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setLineSpacing(0, 0.94f)
                .setIncludePad(false)
                .build();
        return h;
    }

    /** ←/→ on the hero: the next (or previous) of this hour's picks. */
    private boolean browse(int dir) {
        if (picks.size() < 2) return false;
        int next = pickIndex;
        for (int i = 0; i < picks.size(); i++) {
            next = (next + dir + picks.size()) % picks.size();
            if (zone != Z_HERO || picks.get(next).pkg != null) break;
        }
        if (next == pickIndex) return false;
        pickIndex = next;
        heroSlide = dir;
        refreshHero(true);
        Backdrop b = recs.backdrop(currentPick());
        if (b != null) showBackdrop(b);
        spoken.focusMoved();
        return true;
    }

    /** Swaps the background with a cross-fade (from one image to another, from the gradient to an image, or back). */
    private void showBackdrop(Backdrop b) {
        if (b == bgTo) return;
        long up = SystemClock.uptimeMillis();
        // In the middle of another swap, start from whichever is more visible.
        if (bgFadeAt == 0 || fadeEase(bgFadeAt, up) >= 0.5f) bgFrom = bgTo;
        bgTo = b;
        bgFadeAt = up;
        nightPair = false;
        if (b == null || glassOf(b) == null) return;
        if (bgFrom == null || glassOf(bgFrom) == null) {
            scene.setFrost(frostOf(b));
            scene.setGlassImage(glassOf(b));
        } else {
            scene.setGlassImages(glassOf(bgFrom), glassOf(b));
        }
    }

    /** What the glass shows: the photo lightened (by day), darkened (at night) or blended in between. */
    private int[] glassOf(Backdrop b) {
        if (b.nightGlass == null || night <= 0.001f) return b.lightGlass;
        if (night >= 0.999f) return b.nightGlass;
        // During the transition, the blend (only for the current background; a photo fading in uses the nearer side).
        if (b != bgTo) return night < 0.5f ? b.lightGlass : b.nightGlass;
        for (int i = 0; i < glassNight.length; i++) glassNight[i] = mix(b.lightGlass[i], b.nightGlass[i], night);
        return glassNight;
    }

    /** No photo: the glass reads the background's own gray (not Scene's default aurora, which isn't on screen). */
    private void neutralGlass() {
        int color = blend(0xFFEBEBF0, 0xFF141416, night);
        java.util.Arrays.fill(neutralFrostPx, color);
        java.util.Arrays.fill(neutralGlassPx, color);
        scene.setFrost(neutralFrostPx);
        scene.setGlassImage(neutralGlassPx);
        nightPair = false;
    }

    /** What the frosted glass shows (drawer, toasts): the blurred photo, darkened at night. */
    private int[] frostOf(Backdrop b) {
        if (b.nightFrost == null || night <= 0.001f) return b.frost;
        if (night >= 0.999f) return b.nightFrost;
        if (b != bgTo) return night < 0.5f ? b.frost : b.nightFrost;
        for (int i = 0; i < frostNight.length; i++) frostNight[i] = mix(b.frost[i], b.nightFrost[i], night);
        return frostNight;
    }

    // MARK: - Night look

    /** Day or night right now (from the sun at your location); `animate`: with the transition. */
    private void checkNight(boolean animate) {
        boolean want = data.isNight(System.currentTimeMillis());
        if (want == nightTarget) return;
        nightTarget = want;
        if (animate && running) {
            nightFrom = night;
            nightAt = SystemClock.uptimeMillis();
        } else {
            night = want ? 1 : 0;
            nightAt = 0;
            nightChanged();
        }
    }

    /** Advances the day ⇄ night transition; true while it runs. */
    private boolean tickNight(long up) {
        if (nightAt == 0) return false;
        float t = Ui.clamp((up - nightAt) / NIGHT_MS), to = nightTarget ? 1 : 0;
        night = nightFrom + (to - nightFrom) * t * t * (3 - 2 * t);
        if (t >= 1) {
            night = to;
            nightAt = 0;
        }
        nightChanged();
        return true;
    }

    /** Redraws for the current night value (the glass now reads the lightened, blended or darkened photo). */
    private void nightChanged() {
        if (bgFadeAt == 0 && bgTo != null && bgTo.lightGlass != null) {
            scene.setFrost(frostOf(bgTo));
            if (nightAt != 0 && bgTo.nightGlass != null) {
                if (!nightPair) {
                    scene.setGlassImages(bgTo.lightGlass, bgTo.nightGlass);
                    nightPair = true;
                }
                scene.setGlassMix(night);
            } else {
                scene.setGlassImage(glassOf(bgTo));
                nightPair = false;
            }
        } else if (bgFadeAt == 0 && bgTo == null) {
            neutralGlass();
        }
        heroDirty = statusDirty = tilesDirty = drawerDirty = menuDirty = true;
        sky.invalidate();
        platter.invalidate();
    }

    /** How dark it is: 0 = day, 1 = night (in between during the transition). */
    private float dark() {
        return night;
    }

    /** The day color or the night color, depending on the night value (blended during the transition). */
    private int tone(int onLight, int onDark) {
        return blend(onLight, onDark, dark());
    }

    private float tone(float onLight, float onDark) {
        return onLight + (onDark - onLight) * dark();
    }

    /** Text colors for the current night value (dark by day, white at night). */
    private void applyTone() {
        lTitle.setColor(tone(L_INK, WHITE));
        lTab.setColor(tone(L_DIM, 0xFF8E8E93));
        lTabOn.setColor(tone(L_INK, WHITE));
        lCardMeta.setColor(tone(L_DIM, 0xFF98989D));
        lQuote.setColor(tone(L_FAINT, 0xB8FFFFFF));
        lTip.setColor(tone(L_INK, WHITE));
        staleQuote.setColor(tone(L_FAINT, 0x99FFFFFF));
    }

    private boolean tickBackgroundFade(long up) {
        if (bgFadeAt == 0) return false;
        float e = fadeEase(bgFadeAt, up);
        if (bgTo != null && bgFrom != null && glassOf(bgFrom) != null && glassOf(bgTo) != null) {
            int[] from = frostOf(bgFrom), to = frostOf(bgTo);
            for (int i = 0; i < frostMix.length; i++) frostMix[i] = mix(from[i], to[i], e);
            scene.setFrost(frostMix);
            scene.setGlassMix(e);
        }
        if (e >= 1) {
            bgFadeAt = 0;
            bgFrom = null;
            if (bgTo == null) {
                neutralGlass();
            } else {
                scene.setFrost(frostOf(bgTo));
                if (glassOf(bgTo) != null) scene.setGlassImage(glassOf(bgTo));
            }
        }
        return true;
    }

    private boolean tickHeroFade(long up) {
        if (heroFadeAt == 0) return false;
        if (fadeEase(heroFadeAt, up) >= 1) {
            heroFadeAt = 0;
            heroFrom = null;
        }
        return true;
    }

    private float fadeEase(long start, long up) {
        return fadeEase(start, up, FADE_MS);
    }

    private static float fadeEase(long start, long up, float duration) {
        float t = Ui.clamp((up - start) / duration);
        return t * t * (3 - 2 * t);
    }

    private static int mix(int a, int b, float f) {
        int r = Math.round(((a >> 16) & 255) * (1 - f) + ((b >> 16) & 255) * f);
        int g = Math.round(((a >> 8) & 255) * (1 - f) + ((b >> 8) & 255) * f);
        int bl = Math.round((a & 255) * (1 - f) + (b & 255) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    // MARK: - Background

    private void drawSky(Canvas c) {
        drawLightSky(c);
    }

    /** Blends a color on top (the drawer's veil) as part of drawing the image, without another layer. */
    private static android.graphics.ColorFilter veil(float amount, int color) {
        if (amount <= 0.002f) return null;
        float keep = 1 - amount;
        return new android.graphics.ColorMatrixColorFilter(new float[] {
            keep, 0, 0, 0, ((color >> 16) & 255) * amount,
            0, keep, 0, 0, ((color >> 8) & 255) * amount,
            0, 0, keep, 0, (color & 255) * amount,
            0, 0, 0, 1, 0});
    }

    private void drawImage(Canvas c, Bitmap image, float alpha) {
        if (image == null || alpha <= 0.004f) return;
        imagePaint.setAlpha(Math.round(255 * Math.min(1, alpha)));
        c.drawBitmap(image, null, screen, imagePaint);
    }

    // MARK: - Hero

    private static void text(Canvas c, String s, float x, float y, Paint p, int color, float alpha) {
        if (s == null || s.isEmpty()) return;
        p.setColor(Ui.alpha(color, alpha));
        c.drawText(s, x, y, p);
    }

    // MARK: - Corner capsule: time and weather (when the hero shows a recommendation) and icon-only buttons

    /** Live TV, the inputs with a device on, All Apps and the TV's settings (Free My TV's are in All Apps). */
    private static List<HomeApps.Item> statusList(Context c) {
        List<HomeApps.Item> list = new ArrayList<>();
        list.addAll(HomeApps.shortcuts(c));
        list.add(HomeApps.action(HomeApps.ANDROID_SETTINGS, "tv-settings", Ui.tr("Ajustes da TV", "TV Settings"), "settings"));
        return list;
    }

    private void setStatusItems(List<HomeApps.Item> list) {
        boolean same = list.size() == statusItems.size();
        for (int i = 0; same && i < list.size(); i++) same = list.get(i).key.equals(statusItems.get(i).key);
        String focusedKey = statusIndex < statusItems.size() ? statusItems.get(statusIndex).key : null;
        statusItems = list;
        if (!same || statusFocus.length != list.size()) {
            statusFocus = new Spring[list.size()];
            statusRects = new RectF[list.size()];
            int index = Math.min(statusIndex, Math.max(0, list.size() - 1));
            for (int i = 0; i < statusFocus.length; i++) {
                statusFocus[i] = new Spring(0.2f, 0.85f, 0);
                statusRects[i] = new RectF();
                if (list.get(i).key.equals(focusedKey)) index = i;   // a device came on: keep focus on the same button
            }
            statusIndex = index;
            if (zone == Z_STATUS && list.isEmpty()) zone = Z_SHELF;
            aim(statusFocus, zone == Z_STATUS ? statusIndex : -1, true);   // only the capsule's springs
            statusDirty = true;
        }
        layoutStatus();
    }

    private boolean showInfo() {
        return hero != null && !hero.clock;
    }

    private void layoutStatus() {
        if (capsuleText == null) return;
        int n = statusItems.size();
        float b = BUTTON * k, gap = 4 * k, pad = 6 * k;
        float info = 0;
        if (showInfo()) {
            info = 26 * k + capsuleText.measureText(clockText);
            HomeData.Weather wx = data.weather;
            if (!Double.isNaN(wx.temp) && showWeatherOn) {
                info += 22 * k + Symbols.width(getContext(), Symbols.weather(wx.code, wx.isDay), 24 * k) + 8 * k
                        + capsuleText.measureText(HomeData.degrees(wx.temp));
            }
            info += 22 * k;
        }
        capsuleInfoW = info;
        float buttons = n * b + Math.max(0, n - 1) * gap;
        float right = CONTENT_R * k, top = CAPSULE_TOP * k;
        float left = right - pad - buttons - (info > 0 ? info + 12 * k : pad);
        capsuleRect.set(left, top, right, top + CAPSULE_H * k);
        float x = right - pad - buttons, cy = capsuleRect.centerY();
        for (int i = 0; i < n; i++) {
            statusRects[i].set(x, cy - b / 2, x + b, cy + b / 2);
            x += b + gap;
        }
        statusDirty = true;
    }

    // MARK: - Quotes and shelf

    private void setShelf(List<HomeApps.Item> list) {
        boolean same = list.size() == shelf.size();
        for (int i = 0; same && i < list.size(); i++) same = list.get(i).key.equals(shelf.get(i).key);
        shelf = list;
        if (!same || tileFocus.length != list.size()) {
            tileFocus = new Spring[list.size()];
            tileRects = new RectF[list.size()];
            for (int i = 0; i < tileFocus.length; i++) {
                tileFocus[i] = new Spring(0.22f, 0.74f, 0);
                tileRects[i] = new RectF();
            }
            shelfIndex = Math.min(shelfIndex, Math.max(0, list.size() - 1));
            retarget(true);
            layoutShelf();
        }
        tilesDirty = true;
        platter.invalidate();
    }

    private void layoutShelf() {
        int n = shelf.size();
        float pad = SHELF_PAD * k, gap = TILE_GAP * k, th = L_TILE_H * k;
        float full = (CONTENT_R - MARGIN) * k;
        float tw = Math.min(th * TILE_ASPECT, (full - Math.max(0, n - 1) * gap) / Math.max(1, n));
        float width = n * tw + Math.max(0, n - 1) * gap, left = (w - width) / 2f;
        float top = L_PLATTER_TOP * k;
        platterRect.set(left - pad, top, left + width + pad, top + th + 2 * pad);
        float x = left, y = top + pad;
        for (int i = 0; i < n; i++) {
            tileRects[i].set(x, y, x + tw, y + th);
            x += tw + gap;
        }
        crispLogos.clear();
    }

    /** Shelf layer: the glass bar and its shadow (it only changes with the background). */
    private void drawPlatter(Canvas c) {
        if (shelf.isEmpty()) return;
        float radius = (TILE_RADIUS + SHELF_PAD) * k;
        tmp.set(platterRect);
        tmp.offset(0, 18 * k);
        shadow.setColor(tone(0x1F000000, 0x52000000));
        shadows.draw(c, tmp, radius, SHADOW_BLUR * k, shadow);
        // White glass by day; smoked glass at night.
        scene.liquid(c, platterRect, radius, tone(0xFFFFFFFF, SMOKE), tone(0.42f, 0.12f), tone(0.03f, 0.08f),
                tone(30, 34) * k, tone(22, 28) * k, tone(0.65f, 0.95f), 1);
    }

    /** The quotes row's text and sizes, for one set of coins, prices and text size. */
    private final class Quotes {
        final int[] coins;
        final double[] prices;
        final float size, cap, halfCap;
        final String[] values;
        final float[] glyphs, widths;

        Quotes(int[] coins, double[] all, Paint text) {
            this.coins = coins;
            size = text.getTextSize();
            cap = Ui.cap(text);
            halfCap = Ui.halfCap(text);
            int n = coins.length;
            prices = new double[n];
            values = new String[n];
            glyphs = new float[n];
            widths = new float[n];
            for (int i = 0; i < n; i++) {
                prices[i] = all[coins[i]];
                values[i] = HomeData.compact(prices[i]);
                glyphs[i] = Glyphs.width(getContext(), HomeData.COINS[coins[i]], cap);
                widths[i] = text.measureText(values[i]);
            }
        }

        boolean matches(int[] now, double[] all, float textSize) {
            if (now != coins || textSize != size) return false;
            for (int i = 0; i < coins.length; i++) if (Double.compare(all[coins[i]], prices[i]) != 0) return false;
            return true;
        }
    }

    private Quotes quotes;

    /** Coin logo + price in the configured currency, in a row at the right end of the tab bar. */
    private void drawQuotes(Canvas c) {
        float right = platterRect.isEmpty() ? CONTENT_R * k : platterRect.right - SHELF_PAD * k;
        applyTone();
        Paint text = lQuote;
        // The corner buttons' names appear in this row: the quotes fade out while one of them has focus.
        float fade = 1;
        for (int i = 3; i < TOP_ITEMS; i++) fade = Math.min(fade, 1 - Ui.clamp(topFocus[i].value));
        if (fade <= 0.01f) return;
        int textColor = text.getColor();
        text.setColor(Ui.alpha(textColor, fade));
        int n = HomeData.coinCount();
        if (n == 0) { text.setColor(textColor); return; }
        // Measured once per set of prices: this row is redrawn on every frame of the cards' focus animation, and
        // measuring text there cost ~3 ms a frame on a Mi Box 3.
        int[] coins = HomeData.active();
        Quotes q = quotes;
        if (q == null || !q.matches(coins, data.prices, text.getTextSize())) {
            q = quotes = new Quotes(coins, data.prices, text);
        }
        // On the tab row, at the right.
        float base = (L_TABBAR_TOP + L_TABBAR_H / 2) * k + q.halfCap;
        float cap = q.cap, gap = 6 * k, spacing = 24 * k;
        float total = spacing * (n - 1);
        for (int i = 0; i < n; i++) total += q.glyphs[i] + gap + q.widths[i];
        float x = right - total;
        for (int i = 0; i < n; i++) {
            Glyphs.coin(getContext(), c, HomeData.COINS[coins[i]], x + q.glyphs[i] / 2, base - cap / 2, cap,
                    Ui.alpha(tone(0xB38E8E93, 0x8CFFFFFF), fade));
            x += q.glyphs[i] + gap;
            c.drawText(q.values[i], x, base, text);
            x += q.widths[i] + spacing;
        }
        text.setColor(textColor);
        String stale = HomeData.staleness(data.pricesAt, 5 * 60_000L);
        if (!stale.isEmpty()) c.drawText(stale, right - total - 24 * k, base, staleQuote);
    }

    private void drawTiles(Canvas c) {
        // The focused one last: it grows over its neighbors without being covered by them.
        int top = -1;
        float most = 0.02f;
        for (int i = 0; i < shelf.size(); i++) {
            if (tileFocus[i].value > most) {
                most = tileFocus[i].value;
                top = i;
            }
        }
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < shelf.size(); i++) {
                if ((pass == 0) == (i == top)) continue;
                drawShelfTile(c, i);
            }
        }
    }

    /**
     * App icon like the ones in the tvOS 27 Dock: the brand color edge to edge (a soft gradient) with the logo.
     * Focused: it grows 20% from the center, with a deep shadow (it "rises" toward the viewer) and a subtle glow at the
     * top. No border and no reflection, as Apple does it.
     */
    private void drawShelfTile(Canvas c, int i) {
        HomeApps.Item it = shelf.get(i);
        float f = tileFocus[i].value, fc = Ui.clamp(f), s = 1 + FOCUS_GROW * f;
        RectF base = tileRects[i];
        float bw = base.width(), bh = base.height(), r0 = TILE_RADIUS * k;
        float cx = base.centerX(), cy = base.centerY();
        cell.set(cx - bw * s / 2, cy - bh * s / 2, cx + bw * s / 2, cy + bh * s / 2);
        // Concentric with the glass while it grows (outer radius = inner radius + padding): the padding left above it
        // shrinks, so its corner opens up by as much (22 at rest, ~35 focused, never less than the scaled one).
        float inset = SHELF_PAD * k - (bh * s - bh) / 2;
        float r = Math.max(r0 * s, (TILE_RADIUS + SHELF_PAD) * k - inset);
        // Shadow at the resting icon size, enlarged by the transform (so the blur stays cached): short at rest, deep
        // and lower when focused.
        c.save();
        c.translate(cx, cy);
        c.scale(s, s);
        tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
        tmp.offset(0, (4 + 14 * f) * k);
        shadowDeep.setColor(Ui.alpha(0xFF000000, tone(0.14f, 0.3f) + tone(0.18f, 0.3f) * fc));
        shadows.draw(c, tmp, r0, DEEP_BLUR * k, shadowDeep);
        c.restore();
        int[] style = TILE_STYLE.get(it.key);
        if (it.logo != null && style != null) {
            LinearGradient brand = tileGradients.get(it.key);
            if (brand == null) {
                brand = new LinearGradient(0, 0, 0, 1, style[0], style[1], Shader.TileMode.CLAMP);
                tileGradients.put(it.key, brand);
            }
            drawUnitShader(c, cell, r, tileFill, brand, 1);
            if (fc > 0.01f) drawUnitShader(c, cell, r, tileFill, TILE_SHEEN, fc);
            drawLogo(c, it, cell, s, style[2]);
        } else if (it.logo != null) {
            tileFill.setShader(null);
            tileFill.setColor(0xFF161618);
            c.drawRoundRect(cell, r, r, tileFill);
            drawLogo(c, it, cell, s, 0);
        } else {
            if (it.tile != null) roundImage(c, it.tile, cell, r);
        }
    }

    /**
     * `image` stretched over `cell`, with corners of radius `r`. Android 12+ clips to the rounded shape; on Android 9 to
     * 11 that anti-aliased clip costs the GPU a mask on every frame of a focus animation (measured on a Mi Box 3: most of
     * a 49 ms frame), so there the image is the shader of a rounded rectangle instead, which looks the same.
     */
    private void roundImage(Canvas c, Bitmap image, RectF cell, float r) {
        if (!Ui.LITE) {
            clip.reset();
            clip.addRoundRect(cell, r, r, Path.Direction.CW);
            c.save();
            c.clipPath(clip);
            c.drawBitmap(image, null, cell, bitmapPaint);
            c.restore();
            return;
        }
        BitmapShader shader = imageShaders.get(image);
        if (shader == null) {
            shader = new BitmapShader(image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            imageShaders.put(image, shader);
        }
        imageMatrix.setScale(cell.width() / image.getWidth(), cell.height() / image.getHeight());
        imageMatrix.postTranslate(cell.left, cell.top);
        shader.setLocalMatrix(imageMatrix);
        imageFill.setShader(shader);
        c.drawRoundRect(cell, r, r, imageFill);
        imageFill.setShader(null);
    }

    /**
     * Logo at its "optical" size (all with the same amount of ink, plus each one's own adjustment, never past 80% × 62%
     * of the tile), centered and already filtered down to the focused tile size: when focused it is drawn pixel for
     * pixel, without resampling.
     */
    private void drawLogo(Canvas c, HomeApps.Item it, RectF box, float s, int mode) {
        Bitmap logo = crispLogo(it, mode == 2 && it.logoDark != null);
        if (logo == null) return;
        float scale = s / (1 + FOCUS_GROW);
        float dw = logo.getWidth() * scale, dh = logo.getHeight() * scale;
        float left = box.centerX() - dw / 2, top = box.centerY() - dh / 2;
        if (scale > 0.998f) {
            left = Math.round(left);
            top = Math.round(top);
        }
        tmp.set(left, top, left + dw, top + dh);
        bitmapPaint.setColorFilter(mode == 1 ? whiteLogo : null);
        c.drawBitmap(logo, null, tmp, bitmapPaint);
        bitmapPaint.setColorFilter(null);
    }

    private Bitmap crispLogo(HomeApps.Item it, boolean dark) {
        Bitmap src = dark ? it.logoDark : it.logo;
        if (tileRects.length == 0) return src;
        String key = it.key + "@" + System.identityHashCode(src) + "@" + Math.round(tileRects[0].width());
        Bitmap cached = crispLogos.get(key);
        if (cached != null) return cached;
        float bw = tileRects[0].width() * (1 + FOCUS_GROW), bh = tileRects[0].height() * (1 + FOCUS_GROW);
        float lw = src.getWidth(), lh = src.getHeight();
        float scale = (float) Math.sqrt(bw * bh * 0.085f / (lw * lh * Math.max(0.08f, it.logoInk))) * it.logoScale;
        scale = Math.min(scale, Math.min(bw * 0.76f / lw, bh * 0.58f / lh));
        Bitmap out = downscale(src, Math.round(lw * scale), Math.round(lh * scale));
        crispLogos.put(key, out);
        return out;
    }

    /** Halves the size while it can (a 2×2 average, no aliasing), then finishes at the exact size. */
    private static Bitmap downscale(Bitmap src, int w, int h) {
        w = Math.max(1, w);
        h = Math.max(1, h);
        Bitmap b = src;
        while (b.getWidth() / 2 >= w && b.getHeight() / 2 >= h) {
            Bitmap half = Bitmap.createScaledBitmap(b, b.getWidth() / 2, b.getHeight() / 2, true);
            if (b != src) b.recycle();
            b = half;
        }
        Bitmap out = Bitmap.createScaledBitmap(b, w, h, true);
        if (b != src && b != out) b.recycle();
        return out;
    }

    // MARK: - Bottom drawer

    private static List<HomeApps.Item> drawerList(Context c) {
        List<HomeApps.Item> list = HomeApps.allInputs(c);
        list.addAll(HomeApps.drawerShortcuts());
        return list;
    }

    private void setDrawerItems(List<HomeApps.Item> list) {
        int inputs = 0;
        while (inputs < list.size() && (list.get(inputs).kind == HomeApps.INPUT || list.get(inputs).kind == HomeApps.LIVE_TV)) inputs++;
        boolean same = list.size() == drawerItems.size();
        for (int i = 0; same && i < list.size(); i++) {
            same = list.get(i).key.equals(drawerItems.get(i).key) && list.get(i).connected == drawerItems.get(i).connected;
        }
        drawerItems = list;
        drawerInputs = inputs;
        if (drawerFocus.length != list.size()) {
            drawerFocus = new Spring[list.size()];
            drawerRects = new RectF[list.size()];
            for (int i = 0; i < drawerFocus.length; i++) {
                drawerFocus[i] = new Spring(0.2f, 0.85f, 0);
                drawerRects[i] = new RectF();
            }
            drawerIndex = Math.min(drawerIndex, Math.max(0, list.size() - 1));
            retarget(true);
        }
        layoutDrawer();
        if (!same) drawerDirty = true;
    }

    private void layoutDrawer() {
        int n = drawerItems.size();
        float left = MARGIN * k, right = CONTENT_R * k;
        float gap = 18 * k, groupGap = 56 * k;
        int groups = drawerInputs > 0 && drawerInputs < n ? 2 : 1;
        float tw = Math.min(230 * k, (right - left - Math.max(0, n - groups) * gap - (groups - 1) * groupGap) / Math.max(1, n));
        float x = left, top = ROW_TOP * k;
        for (int i = 0; i < n; i++) {
            if (i == drawerInputs && i > 0) x += groupGap - gap;
            drawerRects[i].set(x, top, x + tw, top + ROW_H * k);
            x += tw + gap;
        }
        float cardsTop = CARDS_TOP * k, split = left + (right - left) * 0.585f;
        boolean wx = showWeatherOn, qt = showQuotes && HomeData.active().length > 0;
        // A card that is off gives its room to the other one.
        weatherCard.set(left, cardsTop, qt ? split - 16 * k : right, cardsTop + CARDS_H * k);
        quotesCard.set(wx ? split + 16 * k : left, cardsTop, right, cardsTop + CARDS_H * k);
        drawerDirty = true;
    }

    private void openDrawer() {
        if (drawerItems.isEmpty()) return;
        drawerOpen = true;
        drawer.target = 1;
        zone = Z_DRAWER;
        drawerIndex = shelf.isEmpty() ? 0 : nearest(drawerRects, tileRects[shelfIndex].centerX());
        for (int i = 0; i < drawerFocus.length; i++) drawerFocus[i].snap(i == drawerIndex ? 1 : 0);
        drawerDirty = true;
    }

    private void closeDrawer() {
        drawerOpen = false;
        drawer.target = 0;
        zone = Z_SHELF;
        for (Spring f : drawerFocus) f.snap(0);   // the drawer goes away: its focus doesn't need to animate
        if (!shelf.isEmpty() && drawerIndex < drawerRects.length) shelfIndex = nearest(tileRects, drawerRects[drawerIndex].centerX());
    }

    /** Color for the drawer's text and symbols: dark by day, white at night. */
    private int drawerInk(float alpha) {
        return Ui.alpha(tone(L_INK, 0xFFFFFFFF), alpha);
    }

    /** Glass for a drawer card or button: white by day, smoked at night (both during the transition). */
    private void drawerGlass(Canvas c, RectF r, float radius, float lightTint, int shadowColor, float drop) {
        float dk = dark();
        if (dk < 0.999f) {
            tmp.set(r);
            tmp.offset(0, drop);
            shadow.setColor(Ui.alpha(shadowColor, 1 - dk));
            shadows.draw(c, tmp, radius, SHADOW_BLUR * k, shadow);
            scene.glass(c, r, radius, 0xFFFFFFFF, lightTint, 0, 0, 0.02f, 1, 1 - dk);
        }
        if (dk > 0.001f) scene.glass(c, r, radius, SMOKE, 0.4f, 0, 0, 0.02f, 1, dk);
    }

    private void drawerCard(Canvas c, RectF r) {
        drawerGlass(c, r, 30 * k, 0.72f, 0x1A000000, 10 * k);
    }

    private void drawDrawer(Canvas c) {
        if (drawerItems.isEmpty()) return;
        rowTitle.setColor(tone(L_DIM, 0xB3FFFFFF));
        cardTitle.setColor(tone(L_DIM, 0xB3FFFFFF));
        cardText.setColor(drawerInk(0.95f));
        cardDim.setColor(tone(L_DIM, 0xADFFFFFF));
        cardDimRight.setColor(tone(L_FAINT, 0x99FFFFFF));
        bigTemp.setColor(drawerInk(1));
        hourPaint.setColor(tone(L_DIM, 0xADFFFFFF));
        hourTemp.setColor(drawerInk(0.95f));
        pricePaint.setColor(drawerInk(0.95f));
        float titleBase = ROW_TITLE * k;
        if (drawerInputs > 0) c.drawText(Ui.tr("Entradas", "Inputs"), drawerRects[0].left + 4 * k, titleBase, rowTitle);
        if (drawerInputs < drawerItems.size()) c.drawText(Ui.tr("Atalhos", "Shortcuts"), drawerRects[drawerInputs].left + 4 * k, titleBase, rowTitle);
        int top = -1;
        for (int i = 0; i < drawerItems.size(); i++) if (drawerFocus[i].value > 0.02f && (top < 0 || drawerFocus[i].value > drawerFocus[top].value)) top = i;
        for (int i = 0; i < drawerItems.size(); i++) if (i != top) drawDrawerTile(c, i);
        if (top >= 0) drawDrawerTile(c, top);
        if (showWeatherOn) drawWeatherCard(c, weatherCard);
        if (showQuotes && HomeData.active().length > 0) drawQuotesCard(c, quotesCard);
    }

    /** Square drawer button: symbol and name; an input with no device is dimmed. Focused: white, with dark text. */
    private void drawDrawerTile(Canvas c, int i) {
        HomeApps.Item it = drawerItems.get(i);
        float f = Ui.clamp(drawerFocus[i].value), s = 1 + 0.07f * f;
        RectF base = drawerRects[i];
        float cx = base.centerX(), cy = base.centerY();
        cell.set(cx - base.width() * s / 2, cy - base.height() * s / 2, cx + base.width() * s / 2, cy + base.height() * s / 2);
        float r = 20 * k * s;
        if (f > 0.01f) {
            tmp.set(cell);
            tmp.offset(0, 12 * k * f);
            shadow.setColor(Ui.alpha(0xFF000000, 0.45f * f));
            shadows.draw(c, tmp, r, SHADOW_BLUR * k, shadow);
        }
        if (f < 0.99f) drawerGlass(c, cell, r, 0.7f, 0x14000000, 6 * k);
        if (f > 0.01f) {
            fill.setShader(null);
            fill.setColor(Ui.alpha(WHITE, f));
            c.drawRoundRect(cell, r, r, fill);
        }
        boolean off = it.kind == HomeApps.INPUT && !it.connected;
        int ink = blend(tone(off ? 0xFF8E8E93 : L_INK, off ? 0x6BFFFFFF : 0xF2FFFFFF), INK, f);
        Symbols.draw(getContext(), c, it.symbol, cx, cell.top + cell.height() * 0.4f, 34 * k * s, ink);
        drawerLabel.setColor(ink);
        drawerLabel.setTextSize(23 * k * s);
        float room = cell.width() - 16 * k, wide = drawerLabel.measureText(it.label);
        // A long name ("Home Settings", an input you renamed) shrinks a little before it gets cut.
        if (wide > room) drawerLabel.setTextSize(Math.max(18 * k * s, 23 * k * s * room / wide));
        String label = drawerLabel.measureText(it.label) <= room ? it.label   // fits: nothing to cut (no allocation)
                : TextUtils.ellipsize(it.label, new TextPaint(drawerLabel), room, TextUtils.TruncateAt.END).toString();
        c.drawText(label, cx, cell.top + cell.height() * 0.8f, drawerLabel);
        drawerLabel.setTextSize(23 * k);
    }

    /** Weather for your location: now (large), the next 6 hours, and sunrise and sunset. */
    private void drawWeatherCard(Canvas c, RectF r) {
        drawerCard(c, r);
        if (Ui.LITE) weatherSnapshot.draw(c, r, drawerCardsKey(), cc -> drawWeather(cc, r));
        else drawWeather(c, r);
    }

    private void drawWeather(Canvas c, RectF r) {
        float x = r.left + 34 * k;
        c.drawText(Prefs.city().isEmpty() ? Ui.tr("Clima", "Weather") : Prefs.city(), x, r.top + 54 * k, cardTitle);
        HomeData.Weather wx = data.weather;
        if (Double.isNaN(wx.temp)) {
            c.drawText(Ui.tr("Sem dados do clima agora.", "No weather data right now."), x, r.top + 120 * k, cardDim);
            return;
        }
        String symbol = Symbols.weather(wx.code, wx.isDay);
        float sw = Symbols.width(getContext(), symbol, 52 * k);
        Symbols.draw(getContext(), c, symbol, x + sw / 2, r.top + 132 * k, 52 * k, drawerInk(0.95f));
        String now = HomeData.degrees(wx.temp);
        c.drawText(now, x + sw + 18 * k - Ui.leftBearing(bigTemp, now), r.top + 170 * k, bigTemp);
        c.drawText(HomeData.condition(wx.code, wx.isDay), x, r.top + 220 * k, cardText);
        c.drawText(Ui.en ? "H:" + HomeData.degrees(wx.max) + "   L:" + HomeData.degrees(wx.min)
                : "Máx. " + HomeData.degrees(wx.max) + "   Mín. " + HomeData.degrees(wx.min), x, r.top + 258 * k, cardDim);
        float split = r.left + 392 * k;
        fill.setShader(null);
        fill.setColor(tone(0x1A000000, 0x24FFFFFF));
        c.drawRoundRect(split - k, r.top + 34 * k, split + k, r.bottom - 34 * k, k, k, fill);
        float hx = split + 30 * k, hw = (r.right - 30 * k - hx) / 6;
        for (int i = 0; i < wx.hours.size() && i < 6; i++) {
            float hc = hx + hw * (i + 0.5f);
            c.drawText(HomeData.hour(wx.hours.get(i)), hc, r.top + 96 * k, hourPaint);
            Symbols.draw(getContext(), c, Symbols.weather(wx.hourCode.get(i), wx.hourDay.get(i)), hc, r.top + 142 * k, 30 * k, drawerInk(0.9f));
            c.drawText(HomeData.degrees(wx.hourTemp.get(i)), hc, r.top + 196 * k, hourTemp);
        }
        float sx = hx, base = r.top + 258 * k;
        if (wx.sunrise != 0) sx = sunItem(c, "sunrise", Ui.tr("Nascer ", "Sunrise ") + Ui.time(wx.sunrise), sx, base) + 36 * k;
        if (wx.sunset != 0) sunItem(c, "sunset", Ui.tr("Pôr do sol ", "Sunset ") + Ui.time(wx.sunset), sx, base);
    }

    private float sunItem(Canvas c, String symbol, String label, float x, float base) {
        float size = 22 * k, sw = Symbols.width(getContext(), symbol, size);
        Symbols.draw(getContext(), c, symbol, x + sw / 2, base - Ui.halfCap(cardDim), size, tone(L_DIM, 0xB3FFFFFF));
        c.drawText(label, x + sw + 10 * k, base, cardDim);
        return x + sw + 10 * k + cardDim.measureText(label);
    }

    /** Dollar, euro and bitcoin in reais with the 24 h change (green/red, like Apple's Stocks app). */
    private void drawQuotesCard(Canvas c, RectF r) {
        drawerCard(c, r);
        if (Ui.LITE) quotesSnapshot.draw(c, r, drawerCardsKey(), cc -> drawPrices(cc, r));
        else drawPrices(c, r);
    }

    /**
     * What the drawer cards show depends on: the data, the settings, the language, day or night, and the minute (the
     * sunrise and sunset, "12 min ago").
     */
    private String drawerCardsKey() {
        return data.weatherAt + "|" + data.pricesAt + "|" + Prefs.version + "|" + Ui.language + "|"
                + Float.floatToIntBits(dark()) + "|" + System.currentTimeMillis() / 60_000;
    }

    /**
     * Android 9 to 11: a drawer card's content (its text and symbols, not its glass) kept as a bitmap. The drawer is
     * redrawn on every frame of its focus animation, and laying that text out again took ~15 ms a frame on a Mi Box 3.
     */
    private static final class Snapshot {
        private Bitmap bitmap;
        private String key = "";

        void draw(Canvas c, RectF r, String key, Consumer<Canvas> content) {
            int left = Math.round(r.left), top = Math.round(r.top);
            int w = Math.max(1, Math.round(r.right) - left), h = Math.max(1, Math.round(r.bottom) - top);
            if (bitmap == null || bitmap.getWidth() != w || bitmap.getHeight() != h) {
                bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                this.key = "";
            }
            if (!key.equals(this.key)) {
                bitmap.eraseColor(0);
                Canvas cc = new Canvas(bitmap);
                cc.translate(-left, -top);
                content.accept(cc);
                this.key = key;
            }
            c.drawBitmap(bitmap, left, top, null);
        }
    }

    private final Snapshot weatherSnapshot = new Snapshot(), quotesSnapshot = new Snapshot();

    private void drawPrices(Canvas c, RectF r) {
        float x = r.left + 34 * k, right = r.right - 34 * k;
        c.drawText(Ui.tr("Cotações", "Rates"), x, r.top + 54 * k, cardTitle);
        String stale = HomeData.staleness(data.pricesAt, 5 * 60_000L);
        c.drawText(stale.isEmpty() ? Ui.tr("variação em 24 h", "24h change") : Ui.tr("de ", "", "de ") + stale, right, r.top + 54 * k, cardDimRight);
        float cap = Ui.cap(cardText);
        int[] coins = HomeData.active();
        for (int j = 0; j < coins.length; j++) {
            int i = coins[j];
            float base = r.top + (110 + 52 * j) * k, mid = base - cap / 2;
            float gw = Glyphs.width(getContext(), HomeData.COINS[i], cap);
            Glyphs.coin(getContext(), c, HomeData.COINS[i], x + 14 * k, mid, cap, tone(L_DIM, 0xCCFFFFFF));
            c.drawText(HomeData.coinName(i), x + Math.max(gw, 28 * k) / 2 + 30 * k, base, cardText);
            c.drawText(HomeData.brl(data.prices[i]), right - 128 * k, base, pricePaint);
            double change = data.changes[i];
            if (Double.isNaN(change)) continue;
            int color = change >= 0 ? tone(0xFF248A3D, UP_COLOR) : tone(0xFFD70015, DOWN_COLOR);
            tmp.set(right - 108 * k, mid - 17 * k, right, mid + 17 * k);
            fill.setShader(null);
            fill.setColor(Ui.alpha(color, 0.2f));
            c.drawRoundRect(tmp, 17 * k, 17 * k, fill);
            changePaint.setColor(color);
            c.drawText(HomeData.change(change), tmp.centerX(), mid + Ui.halfCap(changePaint), changePaint);
        }
    }

    // MARK: - Focus

    /** Sets the springs' targets from the current focus (snap: no animation). */
    private void retarget(boolean snap) {
        // Only the group whose focus changed is redrawn (moving along the shelf doesn't redraw the hero or capsule).
        boolean tilesChanged = aim(tileFocus, !sheetOpen && zone == Z_SHELF ? shelfIndex : -1, snap);
        boolean statusChanged = aim(statusFocus, !sheetOpen && zone == Z_STATUS ? statusIndex : -1, snap);
        boolean drawerChanged = aim(drawerFocus, !sheetOpen && zone == Z_DRAWER ? drawerIndex : -1, snap);
        boolean heroChanged = heroFocus.target != (!sheetOpen && zone == Z_HERO ? 1 : 0);
        heroFocus.target = !sheetOpen && zone == Z_HERO ? 1 : 0;
        if (snap) heroFocus.snap(heroFocus.target);
        boolean topChanged = aim(topFocus, !sheetOpen && zone == Z_TOP ? topIndex : -1, snap);
        cardFocusMoved();
        heroChanged |= aim(cardFocus, !sheetOpen && zone == Z_CARDS ? cardIndex : -1, snap) | topChanged;
        statusChanged |= topChanged;
        tilesDirty |= snap || tilesChanged;
        statusDirty |= snap || statusChanged;
        heroDirty |= snap || heroChanged;
        drawerDirty |= snap || drawerChanged;
        spoken.focusMoved();
    }

    /** Aims a group's springs (only `focused` at 1); returns true if any target changed. */
    private static boolean aim(Spring[] springs, int focused, boolean snap) {
        boolean changed = false;
        for (int i = 0; i < springs.length; i++) {
            float target = i == focused ? 1 : 0;
            changed |= springs[i].target != target;
            springs[i].target = target;
            if (snap) springs[i].snap(target);
        }
        return changed;
    }

    // MARK: - "All Apps" sheet

    private void setOthers(List<HomeApps.Item> list) {
        others = list;
        gridFocus = new Spring[list.size()];
        for (int i = 0; i < gridFocus.length; i++) gridFocus[i] = new Spring(0.22f, 0.82f, 0);
        gridLabels = null;
        gridIndex = Math.min(gridIndex, Math.max(0, list.size() - 1));
        retargetGrid(true);
        gridDirty = true;
    }

    private void openSheet() {
        sheetOpen = true;
        gridIndex = 0;
        scroll.snap(0);
        retargetGrid(true);
        retarget(false);
        sheet.target = 1;
        gridDirty = true;
    }

    private void closeSheet() {
        sheetOpen = false;
        sheet.target = 0;
        if (pendingOthers != null) {
            setOthers(pendingOthers);
            pendingOthers = null;
        }
        retarget(false);
    }

    private List<HomeApps.Item> pendingOthers;   // All Apps' new list, read while it was open

    private void retargetGrid(boolean snap) {
        for (int i = 0; i < gridFocus.length; i++) {
            gridFocus[i].target = sheetOpen && i == gridIndex ? 1 : 0;
            if (snap) gridFocus[i].snap(gridFocus[i].target);
        }
        int rows = Math.max(1, (others.size() + COLUMNS - 1) / COLUMNS);
        int visible = Math.min(rows, VISIBLE_ROWS);
        int r = gridIndex / COLUMNS;
        float target = scroll.target;
        if (r < target) target = r;
        else if (r > target + visible - 1) target = r - visible + 1;
        scroll.target = Math.max(0, Math.min(rows - visible, target));
        if (snap) scroll.snap(scroll.target);
        spoken.focusMoved();
    }

    private final RectF gridCell = new RectF();   // reused for every app in the grid (no allocation per frame)

    private void drawSheet(Canvas c) {
        c.drawColor(0xA8000000);
        float shift = scroll.value * GRID_ROW * k;
        c.drawText(Ui.tr("Todos os Apps", "All Apps"), w / 2f, 124 * k - shift, sheetTitle);
        int n = others.size();
        if (gridLabels == null || gridLabels.length != n) {
            gridLabels = new String[n];
            for (int i = 0; i < n; i++) {
                gridLabels[i] = TextUtils.ellipsize(others.get(i).label, gridLabel, (GRID_W - 10) * k, TextUtils.TruncateAt.END).toString();
            }
        }
        float gridWidth = COLUMNS * GRID_W + (COLUMNS - 1) * GRID_GAP;
        float left = (1920 - gridWidth) / 2 * k;
        float gridTop = GRID_TOP * k;
        for (int i = 0; i < n; i++) {
            int r = i / COLUMNS, col = i % COLUMNS;
            float y = gridTop + r * GRID_ROW * k - shift;
            if (y > h || y + GRID_ROW * k < 0) continue;
            float x = left + col * (GRID_W + GRID_GAP) * k;
            RectF base = gridCell;
            base.set(x, y, x + GRID_W * k, y + GRID_H * k);
            float f = gridFocus[i].value;
            drawCard(c, others.get(i).tile, base, GRID_RADIUS * k, f, 0.08f);
            if (sheetOpen && i == gridIndex) focusedCell.set(cell);
            gridLabel.setColor(blend(0x99FFFFFF, 0xFFFFFFFF, Ui.clamp(f)));
            c.drawText(gridLabels[i], base.centerX(), base.bottom + (40 + 6 * f) * k, gridLabel);
        }
    }

    /** 16:9 sheet card. Focused (the shelf's subtle focus): it grows, with a shadow, a soft sheen and a thin border. */
    private void drawCard(Canvas c, Bitmap image, RectF base, float radius, float f, float grow) {
        float s = 1 + grow * f;
        float cx = base.centerX(), cy = base.centerY();
        cell.set(cx - base.width() * s / 2, cy - base.height() * s / 2, cx + base.width() * s / 2, cy + base.height() * s / 2);
        float r = radius * s;
        float fc = Ui.clamp(f);
        if (f > 0.01f) {
            tmp.set(cell);
            tmp.offset(0, 16 * k * f);
            shadow.setColor(Ui.alpha(0xFF000000, 0.55f * fc));
            shadows.draw(c, tmp, r, SHADOW_BLUR * k, shadow);
        }
        if (image != null) roundImage(c, image, cell, r);
        if (f > 0.01f) drawUnitShader(c, cell, r, sheen, GRID_SHEEN, fc);
        if (fc < 0.99f) {
            veil.setColor(Ui.alpha(0xFF000000, 0.08f * (1 - fc)));
            c.drawRoundRect(cell, r, r, veil);
        }
        border.setColor(0x14FFFFFF);
        c.drawRoundRect(cell, r, r, border);
        if (f > 0.01f) {
            rim.setShader(null);
            rim.setColor(Ui.alpha(0xFFFFFFFF, 0.4f * fc));
            rim.setStrokeWidth(1.5f * k);
            c.drawRoundRect(cell, r, r, rim);
            rim.setStrokeWidth(2.5f * k);
        }
    }

    // MARK: - App options (hold OK on an app): a rounded panel that slides out at its side

    private static final int MENU_OPEN = 0, MENU_INFO = 1, MENU_UNINSTALL = 2, MENU_LEFT = 3, MENU_RIGHT = 4,
            MENU_SHELF_ADD = 5, MENU_SHELF_REMOVE = 6;
    // The rows' corners are concentric with the panel's (panel radius = row radius + padding).
    private static final float MENU_ROW = 72, MENU_PAD = 12, MENU_HEADER = 58, MENU_GAP = 28, MENU_ROW_RADIUS = 22;
    private static final float MENU_MIN_W = 380, MENU_MAX_W = 560, MENU_TEXT_X = 22, MENU_LABEL_X = 54;
    private final Spring menu = new Spring(0.22f, 0.86f, 0);
    private final Spring menuRow = new Spring(0.18f, 0.9f, 0);   // the focus pill's position, in rows
    private final RectF menuRect = new RectF(), menuAnchor = new RectF();
    private final List<Integer> menuActions = new ArrayList<>();
    private String[] menuLabels = new String[0];
    private String menuTitle = "";
    private HomeApps.Item menuApp;
    private boolean menuOpen, menuDirty, menuOnRight, menuFromShelf;
    private int menuIndex;
    private Paint menuText, menuHeader;
    private float menuTextHalfCap, menuHeaderHalfCap;

    /** Opens the options of the focused app (shelf or All Apps); false when the focus isn't on an app. */
    private boolean openMenu() {
        HomeApps.Item it = focusedApp();
        if (it == null) return false;
        menuApp = it;
        menuFromShelf = !sheetOpen;
        buildMenu(MENU_OPEN);
        menuRow.snap(menuIndex);
        menuOpen = true;
        menu.target = 1;
        menuDirty = true;
        click.open();
        announceForAccessibility(it.label + ". " + TextUtils.join(", ", menuLabels));
        return true;
    }

    /** The app under the focus, with the rectangle it's drawn in (menuAnchor); null for anything that isn't an app. */
    private HomeApps.Item focusedApp() {
        HomeApps.Item it;
        if (sheetOpen) {
            if (others.isEmpty()) return null;
            it = others.get(gridIndex);
            menuAnchor.set(focusedCell);
        } else if (zone == Z_SHELF && !shelf.isEmpty()) {
            it = shelf.get(shelfIndex);
            RectF t = tileRects[shelfIndex];
            float gx = t.width() * FOCUS_GROW / 2, gy = t.height() * FOCUS_GROW / 2;
            menuAnchor.set(t.left - gx, t.top - gy, t.right + gx, t.bottom + gy);
        } else {
            return null;
        }
        return it.kind == HomeApps.APP && it.pkg != null ? it : null;
    }

    /**
     * The options for menuApp: open, move along the shelf (from the shelf), put on or take off the shelf, its info, and
     * uninstall (apps the person installed). The focus stays on `keep` when it's still there (after a move).
     */
    private void buildMenu(int keep) {
        List<String> order = HomeApps.shelfPackages(getContext());
        menuActions.clear();
        menuActions.add(MENU_OPEN);
        if (menuFromShelf && shelfIndex > 0) menuActions.add(MENU_LEFT);
        if (menuFromShelf && shelfIndex < shelf.size() - 1) menuActions.add(MENU_RIGHT);
        if (order.contains(menuApp.pkg)) menuActions.add(MENU_SHELF_REMOVE);
        else if (order.size() < HomeApps.MAX_SHELF) menuActions.add(MENU_SHELF_ADD);
        menuActions.add(MENU_INFO);
        if (HomeApps.removable(getContext(), menuApp.pkg)) menuActions.add(MENU_UNINSTALL);
        menuLabels = new String[menuActions.size()];
        for (int i = 0; i < menuLabels.length; i++) menuLabels[i] = menuLabel(menuActions.get(i));
        int at = menuActions.indexOf(keep);
        // At an end of the shelf the move that was focused is gone: the other direction takes the focus.
        if (at < 0 && (keep == MENU_LEFT || keep == MENU_RIGHT)) at = menuActions.indexOf(keep == MENU_LEFT ? MENU_RIGHT : MENU_LEFT);
        menuIndex = Math.max(0, at);
        menuRow.target = menuIndex;
        layoutMenu();
    }

    private static String menuLabel(int action) {
        switch (action) {
            case MENU_OPEN: return Ui.tr("Abrir", "Open");
            case MENU_LEFT: return Ui.tr("Mover para a esquerda", "Move left", "Mover a la izquierda");
            case MENU_RIGHT: return Ui.tr("Mover para a direita", "Move right", "Mover a la derecha");
            case MENU_SHELF_ADD: return Ui.tr("Pôr na prateleira", "Add to shelf", "Agregar al estante");
            case MENU_SHELF_REMOVE: return Ui.tr("Tirar da prateleira", "Remove from shelf", "Quitar del estante");
            case MENU_INFO: return Ui.tr("Informações do app", "App info");
            default: return Ui.tr("Desinstalar", "Uninstall");
        }
    }

    private static String menuSymbol(int action) {
        switch (action) {
            case MENU_OPEN: return "play";
            case MENU_LEFT: return "arrow_back";
            case MENU_RIGHT: return "arrow_forward";
            case MENU_SHELF_ADD: return "add";
            case MENU_SHELF_REMOVE: return "remove";
            case MENU_INFO: return "info";
            default: return "delete";
        }
    }

    /** Beside the app, on the right when it fits (on the left otherwise), centered on it and kept on screen. */
    private void layoutMenu() {
        float pad = MENU_PAD * k, widest = 0;
        for (String label : menuLabels) widest = Math.max(widest, menuText.measureText(label));
        float width = Math.max(MENU_MIN_W * k, Math.min(MENU_MAX_W * k, widest + (MENU_LABEL_X + 2 * MENU_TEXT_X) * k + 2 * pad));
        float height = 2 * pad + MENU_HEADER * k + menuLabels.length * MENU_ROW * k;
        float gap = MENU_GAP * k, edge = 40 * k;
        menuOnRight = menuAnchor.right + gap + width <= w - edge;
        float left = menuOnRight ? menuAnchor.right + gap : Math.max(edge, menuAnchor.left - gap - width);
        float top = Math.max(60 * k, Math.min(h - 60 * k - height, menuAnchor.centerY() - height / 2));
        menuRect.set(left, top, left + width, top + height);
        float room = width - 2 * (pad + MENU_TEXT_X * k);
        menuTitle = TextUtils.ellipsize(menuApp.label, new TextPaint(menuHeader), room, TextUtils.TruncateAt.END).toString();
        menuLayer.setPivotX(menuOnRight ? menuRect.left : menuRect.right);
        menuLayer.setPivotY(menuRect.centerY());
    }

    private void closeMenu() {
        menuOpen = false;
        menu.target = 0;
        spoken.focusMoved();
    }

    /** Closed at once, without the animation (the home screen comes back, or Home was pressed). */
    private void hideMenu() {
        menuOpen = false;
        menu.snap(0);
        menuDirty = true;
        updateMenuMotion();
    }

    /** ↑/↓ between the options; ← or → toward the app closes the panel. */
    private void moveMenu(int dx, int dy) {
        if (dx != 0) {
            if ((dx < 0) == menuOnRight) {
                closeMenu();
                click.move();
            }
            return;
        }
        int next = Math.max(0, Math.min(menuLabels.length - 1, menuIndex + dy));
        if (next == menuIndex) return;
        menuIndex = next;
        menuRow.target = next;
        click.move();
        announceForAccessibility(menuLabels[next]);
    }

    private void chooseMenu() {
        HomeApps.Item it = menuApp;
        int action = menuActions.get(menuIndex);
        if (menuFromShelf) {
            // The shelf may have been read again meanwhile (an input came on): the focus goes back to this app.
            int at = -1;
            for (int i = 0; i < shelf.size() && at < 0; i++) if (shelf.get(i).key.equals(it.key)) at = i;
            if (at < 0) {
                closeMenu();
                return;
            }
            shelfIndex = at;
        }
        if (action == MENU_LEFT || action == MENU_RIGHT) {
            moveOnShelf(action == MENU_LEFT ? -1 : 1, action);   // the panel stays: press again to keep moving
            return;
        }
        closeMenu();
        if (action == MENU_OPEN) {
            activate();
            return;
        }
        click.open();
        if (action == MENU_SHELF_ADD || action == MENU_SHELF_REMOVE) {
            putOnShelf(it, action == MENU_SHELF_ADD);
            return;
        }
        if (action == MENU_INFO) {
            if (!HomeApps.openInfo(activity, it.pkg)) showToast(Ui.tr("Não deu para abrir as informações do app.", "Couldn't open the app's info."));
        } else if (!HomeApps.uninstall(activity, it.pkg)) {
            showToast(Ui.tr("Não dá para desinstalar este app por aqui.", "Couldn't uninstall this app here."));
        }
    }

    /** Swaps the focused shelf app with its neighbor; the focus (and the panel) go along with it. */
    private void moveOnShelf(int dir, int action) {
        int from = shelfIndex, to = from + dir;
        if (to < 0 || to >= shelf.size()) return;
        List<String> order = new ArrayList<>(HomeApps.shelfPackages(getContext()));
        int a = order.indexOf(shelfKey(shelf.get(from))), b = order.indexOf(shelfKey(shelf.get(to)));
        if (a < 0 || b < 0) return;
        java.util.Collections.swap(order, a, b);
        saveShelf(order);
        List<HomeApps.Item> list = new ArrayList<>(shelf);
        java.util.Collections.swap(list, from, to);
        shelf = list;
        Spring focused = tileFocus[from];   // the grown tile keeps its size in its new place
        tileFocus[from] = tileFocus[to];
        tileFocus[to] = focused;
        shelfIndex = to;
        retarget(false);
        tilesDirty = true;
        click.move();
        focusedApp();   // the panel follows the app to its new place
        buildMenu(action);
        menuDirty = true;
    }

    /** Puts the app at the end of the shelf, or takes it off. */
    private void putOnShelf(HomeApps.Item it, boolean on) {
        List<String> order = new ArrayList<>(HomeApps.shelfPackages(getContext()));
        if (on && (order.size() >= HomeApps.MAX_SHELF || order.contains(it.pkg))) return;
        if (on) order.add(it.pkg);
        else order.remove(it.pkg);
        saveShelf(order);
        if (!on && menuFromShelf && shelfIndex < shelf.size()) {
            List<HomeApps.Item> list = new ArrayList<>(shelf);
            list.remove(shelfIndex);
            setShelf(list);   // gone at once; the reload below confirms it
        }
        reload(true);
        showToast(on ? Ui.tr(it.label + " está na prateleira.", it.label + " is on the shelf.", it.label + " está en el estante.")
                : Ui.tr(it.label + " saiu da prateleira.", it.label + " is off the shelf.", it.label + " salió del estante."));
    }

    /** What the shelf order (Prefs) calls an item: its package, or the live TV key. */
    private static String shelfKey(HomeApps.Item it) {
        return it.kind == HomeApps.LIVE_TV ? HomeApps.LIVE_TV_KEY : it.pkg;
    }

    /** Saves the shelf without the home screen starting over on the way back (as a change in Settings makes it). */
    private void saveShelf(List<String> order) {
        Prefs.setShelf(order);
        if (activity instanceof HomeActivity) ((HomeActivity) activity).settingsSeen();
    }

    /** The panel's fade, slide and growth (view properties only: its content isn't redrawn while it moves). */
    private void updateMenuMotion() {
        float v = menu.value, a = Ui.clamp(v), s = 0.94f + 0.06f * v;
        menuLayer.setAlpha(a);
        menuLayer.setVisibility(a > 0.002f ? VISIBLE : INVISIBLE);
        menuLayer.setScaleX(s);
        menuLayer.setScaleY(s);
        menuLayer.setTranslationX((1 - v) * 24 * k * (menuOnRight ? -1 : 1));
    }

    /**
     * The panel in the current look: white with dark text by day, dark gray with white text at night. The app's name
     * on top, then the options; the focused one sits on a pill in the opposite color (dark by day, white at night),
     * which slides between them. Uninstall is in red.
     */
    private void drawMenu(Canvas c) {
        if (menuApp == null) return;
        float pad = MENU_PAD * k, row = MENU_ROW * k, rowRadius = MENU_ROW_RADIUS * k, radius = rowRadius + pad;
        tmp.set(menuRect);
        tmp.offset(0, 12 * k);
        shadow.setColor(Ui.alpha(0xFF000000, tone(0.14f, 0.36f)));
        shadows.draw(c, tmp, radius, SHADOW_BLUR * k, shadow);
        fill.setShader(null);
        fill.setColor(tone(0xFAFFFFFF, 0xFA2C2C2E));
        c.drawRoundRect(menuRect, radius, radius, fill);
        border.setColor(tone(0x14000000, 0x1FFFFFFF));
        c.drawRoundRect(menuRect, radius, radius, border);
        float x = menuRect.left + pad + MENU_TEXT_X * k;
        menuHeader.setColor(tone(L_DIM, 0xFF98989D));
        c.drawText(menuTitle, x, menuRect.top + pad + MENU_HEADER * k / 2 + menuHeaderHalfCap, menuHeader);
        float rowsTop = menuRect.top + pad + MENU_HEADER * k, pillTop = rowsTop + menuRow.value * row;
        cell.set(menuRect.left + pad, pillTop, menuRect.right - pad, pillTop + row);
        fill.setColor(tone(L_INK, WHITE));
        c.drawRoundRect(cell, rowRadius, rowRadius, fill);
        for (int i = 0; i < menuLabels.length; i++) {
            float on = Ui.clamp(1 - Math.abs(menuRow.value - i));   // how much of the pill is behind this row
            boolean danger = menuActions.get(i) == MENU_UNINSTALL;
            int rest = danger ? tone(0xFFD70015, DOWN_COLOR) : tone(L_INK, WHITE);
            int lit = danger ? tone(DOWN_COLOR, 0xFFD70015) : tone(WHITE, L_INK);
            int ink = blend(rest, lit, on);
            float cy = rowsTop + i * row + row / 2;
            Symbols.draw(getContext(), c, menuSymbol(menuActions.get(i)), x + 14 * k, cy, 26 * k, ink);
            menuText.setColor(ink);
            c.drawText(menuLabels[i], x + MENU_LABEL_X * k, cy + menuTextHalfCap, menuText);
        }
    }

    // MARK: - Focus on a card: the background, a light sweep and a preview follow it

    /** The card under the focus (an index in the tab's list), or -1. */
    private int focusedCard = -1;
    private long cardFocusAt;
    /** How long the focus rests on a card before its synopsis shows. */
    private static final long PREVIEW_MS = 2000, PREVIEW_FADE_MS = 350, SWEEP_DELAY_MS = 90, SWEEP_MS = 650;
    private final Runnable previewWake = this::wake;

    /** The focus moved: the card it rests on now (or none), and when it got there. */
    private void cardFocusMoved() {
        int j = !sheetOpen && !menuOpen && zone == Z_CARDS && cardIndex < cards.size() ? shiftTo + cardIndex : -1;
        if (j == focusedCard) return;
        focusedCard = j;
        cardFocusAt = SystemClock.uptimeMillis();
        handler.removeCallbacks(previewWake);
        if (j >= 0) handler.postDelayed(previewWake, PREVIEW_MS);   // the screen is still by then: wake it for the preview
        updateBackground();
    }

    /** The background follows the focused card (or the first one, with the focus elsewhere), cross-faded. */
    private void updateBackground() {
        Recs.Pick p = focusedCard >= 0 && focusedCard < allCards.size() ? allCards.get(focusedCard)
                : cards.isEmpty() ? null : cards.get(0);
        Backdrop b = p == null ? null : recs.backdrop(p);
        if (p == null || b != null) showBackdrop(b);
    }

    /** True while something here moves: the sweep, the preview fading in, the greeting changing. */
    private boolean tickExtras(long up) {
        boolean moving = false;
        if (focusedCard >= 0) {
            long since = up - cardFocusAt;
            moving = since < SWEEP_DELAY_MS + SWEEP_MS || (since >= PREVIEW_MS && since < PREVIEW_MS + PREVIEW_FADE_MS + 50);
        }
        if (greetingAt != 0) {
            if (up - greetingAt > GREETING_FADE_MS) greetingAt = 0;
            else moving = true;
        }
        if (launchAt != 0) {
            long since = up - launchAt;
            if (!launchOpened && since >= LAUNCH_OPEN_AT_MS) {
                launchOpened = true;
                openNow(launchItem, screen);   // the app starts while the card finishes growing
            }
            // An app that never shows up (the activity stays): the card goes away by itself.
            if (since > 2500) {
                launchAt = 0;
                launchLayer.setVisibility(INVISIBLE);
            }
            launchLayer.invalidate();
            moving = true;
        }
        return moving;
    }

    /** How far the synopsis has faded in on the focused card (0 to 1). */
    private float previewAmount() {
        long since = SystemClock.uptimeMillis() - cardFocusAt - PREVIEW_MS;
        return since <= 0 ? 0 : smoothstep(0, 1, since / (float) PREVIEW_FADE_MS);
    }

    // A soft band of light that crosses the card once when the focus lands on it (the tvOS sheen).
    private static final LinearGradient SWEEP = new LinearGradient(0, 0, 1, 0, new int[] {0x00FFFFFF, 0x30FFFFFF, 0x00FFFFFF},
            new float[] {0, 0.5f, 1}, Shader.TileMode.CLAMP);

    private void drawSweep(Canvas c, RectF card, float r) {
        float t = (SystemClock.uptimeMillis() - cardFocusAt - SWEEP_DELAY_MS) / (float) SWEEP_MS;
        if (t <= 0 || t >= 1) return;
        float e = smoothstep(0, 1, t), band = card.width() * 0.45f;
        unitMatrix.setScale(band, card.height());
        unitMatrix.postSkew(-0.35f, 0, 0, 0);
        unitMatrix.postTranslate(card.left - band + e * (card.width() + band * 1.6f), card.top);
        SWEEP.setLocalMatrix(unitMatrix);
        sheen.setShader(SWEEP);
        c.drawRoundRect(card, r, r, sheen);
        sheen.setShader(null);
    }

    // The synopsis, laid out once per title and card size (no work while it fades in).
    private final Map<String, StaticLayout> previewText = new HashMap<>();
    private TextPaint previewPaint, previewSmallPaint;

    /** After resting on a card: the rating and the year, then the synopsis, over the darkened photo. */
    private void drawPreview(Canvas c, Recs.Pick p, RectF card, float r, float big, float a) {
        fill.setShader(null);
        fill.setColor(Ui.alpha(0xFF000000, 0.62f * a));
        c.drawRoundRect(card, r, r, fill);
        boolean large = big > 0.5f;
        float pad = card.width() * 0.05f, width = card.width() - 2 * pad;
        String key = Recs.key(p) + (large ? "L" : "S") + Math.round(width);
        StaticLayout text = previewText.get(key);
        if (text == null) {
            if (previewText.size() > 40) previewText.clear();
            if (previewPaint == null) {
                previewPaint = new TextPaint(Ui.text(Ui.regular, 26 * k, 0xEBFFFFFF));
                previewSmallPaint = new TextPaint(Ui.text(Ui.regular, 21 * k, 0xEBFFFFFF));
            }
            text = StaticLayout.Builder.obtain(p.overview, 0, p.overview.length(), large ? previewPaint : previewSmallPaint,
                    Math.max(1, Math.round(width)))
                    .setMaxLines(large ? 4 : 3).setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(0, 1.05f).build();
            previewText.put(key, text);
        }
        String facts = previewFacts(p);
        onPhotoMeta.setAlpha(Math.round(230 * a));
        float bottom = card.bottom - pad * 0.8f;
        float top = bottom - text.getHeight();
        c.save();
        c.translate(card.left + pad, top);
        TextPaint tp = large ? previewPaint : previewSmallPaint;
        tp.setAlpha(Math.round(235 * a));
        text.draw(c);
        c.restore();
        if (!facts.isEmpty()) c.drawText(facts, card.left + pad, top - 14 * k, onPhotoMeta);
    }

    /** "★ 7,8 · 2024" (the rating with the language's decimal mark), or just the year. */
    private static String previewFacts(Recs.Pick p) {
        String rating = p.rating > 0 ? "\u2605 " + Ui.once("rating", p.rating, () -> String.format(Ui.locale(), "%.1f", p.rating)) : "";
        return rating.isEmpty() ? p.year : p.year.isEmpty() ? rating : rating + "  \u00b7  " + p.year;
    }

    // MARK: - The greeting beside the title: the time of day and, when it's coming, the rain

    private static final long GREETING_FADE_MS = 600;
    private String greetingText = "", greetingFrom = "";
    private long greetingAt;
    private Paint greetingPaint;

    /** "Boa noite · Chuva às 21h": made again on every tick; a change cross-fades. */
    private void updateGreeting() {
        if (greetingPaint == null) return;
        int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        String text = hour >= 5 && hour < 12 ? Ui.tr("Bom dia", "Good morning", "Buenos días")
                : hour >= 12 && hour < 18 ? Ui.tr("Boa tarde", "Good afternoon", "Buenas tardes")
                : Ui.tr("Boa noite", "Good evening", "Buenas noches");
        String rain = showWeatherOn ? rainSoon() : "";
        if (!rain.isEmpty()) text += "  \u00b7  " + rain;
        if (text.equals(greetingText)) return;
        greetingFrom = greetingText;
        greetingText = text;
        greetingAt = greetingFrom.isEmpty() ? 0 : SystemClock.uptimeMillis();
        heroDirty = true;
        wake();
    }

    /** "Chovendo agora", or "Chuva às 18h" for the first rainy hour in the next six; "" when it stays dry. */
    private String rainSoon() {
        HomeData.Weather wx = data.weather;
        if (Double.isNaN(wx.temp)) return "";
        if (rainy(wx.code)) return Ui.tr("Chovendo agora", "Raining now", "Lloviendo ahora");
        for (int i = 0; i < wx.hours.size(); i++) {
            if (rainy(wx.hourCode.get(i))) {
                String at = HomeData.hour(wx.hours.get(i));
                return Ui.tr("Chuva às " + at, "Rain at " + at, "Lluvia a las " + at);
            }
        }
        return "";
    }

    private static boolean rainy(int code) {
        return code >= 51 && code <= 67 || code >= 80 && code <= 82 || code >= 95;
    }

    private void drawGreeting(Canvas c) {
        if (greetingText.isEmpty()) return;
        float x = MARGIN * k - headingBearing + headingWidth + 32 * k, base = L_TITLE_BASE * k;
        int color = tone(L_DIM, 0xFF98989D);
        float t = greetingAt == 0 ? 1 : Ui.clamp((SystemClock.uptimeMillis() - greetingAt) / (float) GREETING_FADE_MS);
        if (t < 1 && !greetingFrom.isEmpty()) {
            greetingPaint.setColor(Ui.alpha(color, 1 - t));
            c.drawText(greetingFrom, x, base, greetingPaint);
        }
        greetingPaint.setColor(Ui.alpha(color, t));
        c.drawText(greetingText, x, base, greetingPaint);
    }

    // MARK: - Opening an app: the card grows to the whole screen as the app starts

    private static final long LAUNCH_MS = 280, LAUNCH_OPEN_AT_MS = 140;
    private long launchAt;
    private boolean launchOpened;
    private HomeApps.Item launchItem;
    private Bitmap launchBitmap;
    private final RectF launchStart = new RectF(), launchRect = new RectF();

    /** The picture the transition grows: the card's photo, or the app's art; null = open the plain way. */
    private Bitmap launchArt(HomeApps.Item it) {
        if (Ui.LITE) return null;   // weak TVs: no full-screen frames on the way to the app
        if (zone == Z_CARDS && !sheetOpen && cardIndex < cards.size()) {
            Recs.Pick p = cards.get(cardIndex);
            Backdrop b = recs.backdrop(p);
            return b != null ? b.wide : recs.preview(p);
        }
        return it.tile;
    }

    /** Where the card is drawn now (the focused one is a little bigger), in screen coordinates. */
    private RectF launchFrom(RectF r) {
        float grow = zone == Z_SHELF && !sheetOpen ? FOCUS_GROW : sheetOpen ? 0.08f : 0.05f;
        float dy = zone == Z_SHELF && !sheetOpen ? tiles.getTranslationY() : 0;
        float gx = r.width() * grow / 2, gy = r.height() * grow / 2;
        launchStart.set(r.left - gx, r.top - gy + dy, r.right + gx, r.bottom + gy + dy);
        return launchStart;
    }

    private void startLaunch(HomeApps.Item it, Bitmap art, RectF from) {
        launchItem = it;
        launchBitmap = art;
        if (from != launchStart) launchStart.set(from);
        launchAt = SystemClock.uptimeMillis();
        launchOpened = false;
        launchLayer.setVisibility(VISIBLE);
        launchLayer.invalidate();
        wake();
    }

    private void drawLaunch(Canvas c) {
        if (launchAt == 0 || launchBitmap == null) return;
        float t = Ui.clamp((SystemClock.uptimeMillis() - launchAt) / (float) LAUNCH_MS), e = 1 - (1 - t) * (1 - t) * (1 - t);
        launchRect.set(launchStart.left + (screen.left - launchStart.left) * e, launchStart.top + (screen.top - launchStart.top) * e,
                launchStart.right + (screen.right - launchStart.right) * e, launchStart.bottom + (screen.bottom - launchStart.bottom) * e);
        float r = 18 * k * (1 - e);
        roundImage(c, launchBitmap, launchRect, r);
        // The last part fades to black, as the app's first frame does.
        float dark = smoothstep(0.55f, 1f, t);
        if (dark > 0) {
            fill.setShader(null);
            fill.setColor(Ui.alpha(0xFF000000, dark));
            c.drawRoundRect(launchRect, r, r, fill);
        }
    }

    // MARK: - Toasts

    void showToast(String text) {
        wake();
        toasts.add(text);
        announceForAccessibility(text);
    }

    /** Advances the toast queue; returns true while a toast fades in or out (animation). */
    private boolean tickToast(long up) {
        if (toast != null && up - toastAt > TOAST_MS) {
            toast = null;
            toastLayer.invalidate();
        }
        if (toast == null && toastPaint != null) {
            String next = toasts.poll();
            if (next != null) {
                toast = layout(next);
                toastAt = up;
            }
        }
        if (toast == null) return false;
        long age = up - toastAt;
        boolean fading = age < 350 || age > TOAST_MS - 450;
        if (fading) toastLayer.invalidate();
        return fading;
    }

    private void drawToast(Canvas c) {
        if (toast == null) return;
        float age = (SystemClock.uptimeMillis() - toastAt) / 1000f;
        float a = Math.min(Ui.clamp(age / 0.3f), Ui.clamp((TOAST_MS / 1000f - age) / 0.4f));
        if (a <= 0) return;
        float padX = 28 * k, padY = 18 * k, icon = 26 * k, iconW = Symbols.width(getContext(), "bell", icon);
        float tw = toast.getWidth() + iconW + 16 * k, th = toast.getHeight();
        float toastTop = 64 * k - (1 - a) * 16 * k;
        tmp.set((w - tw) / 2f - padX, toastTop, (w + tw) / 2f + padX, toastTop + th + 2 * padY);
        int save = c.saveLayerAlpha(tmp.left - 4, tmp.top - 4, tmp.right + 4, tmp.bottom + 4, Math.round(255 * a));
        scene.glass(c, tmp, Math.min(tmp.height() / 2, 32 * k), SMOKE, 0.55f, 0, 0, 0.02f, 1);
        Symbols.draw(getContext(), c, "bell", tmp.left + padX + iconW / 2, tmp.centerY(), icon, 0xEBFFFFFF);
        c.translate(tmp.left + padX + iconW + 16 * k, tmp.top + padY);
        toast.draw(c);
        c.restoreToCount(save);
    }

    private StaticLayout layout(String text) {
        int max = Math.round(720 * k);
        int width = (int) Math.min(max, Math.ceil(Layout.getDesiredWidth(text, toastPaint)));
        return StaticLayout.Builder.obtain(text, 0, text.length(), toastPaint, Math.max(1, width))
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build();
    }

    /** New notices from the main process (mode switches, the check after the TV boots): those from the last 24 h. */
    private void checkNotices() {
        final Context c = getContext().getApplicationContext();
        LOADER.execute(() -> {
            JSONArray list = HomeMode.state(c).optJSONArray("notices");
            if (list == null) return;
            long seen = ui.getLong("seenNotice", 0), newest = seen;
            long cutoff = System.currentTimeMillis() - 24 * 3600_000L;
            final List<String> fresh = new ArrayList<>();
            for (int i = list.length() - 1; i >= 0; i--) {   // the list comes newest first
                JSONObject o = list.optJSONObject(i);
                if (o == null) continue;
                long at = o.optLong("at");
                if (at <= seen || at < cutoff) continue;
                fresh.add(o.optString("text"));
                newest = Math.max(newest, at);
            }
            if (newest > seen) ui.edit().putLong("seenNotice", newest).apply();
            if (!fresh.isEmpty()) handler.post(() -> { for (String t : fresh) showToast(t); });
        });
    }

    // MARK: - Lists (in the background, so returning to the home screen isn't delayed)

    private void reload() {
        reload(false);
    }

    /**
     * The inputs and shortcuts every time; the shelf and All Apps only when an app was installed, updated or removed
     * since the last look (Android counts those changes), or with `apps` (the shelf was just edited).
     */
    private void reload(boolean apps) {
        wake();
        final Context c = getContext().getApplicationContext();
        LOADER.execute(() -> {
            android.content.pm.ChangedPackages changed = c.getPackageManager().getChangedPackages(Math.max(0, appsSeen));
            final boolean appsChanged = apps || appsSeen < 0 || changed != null;
            if (changed != null) appsSeen = changed.getSequenceNumber();
            else if (appsSeen < 0) appsSeen = 0;
            final List<HomeApps.Item> s = appsChanged ? HomeApps.shelf(c, true) : null;
            final List<HomeApps.Item> st = statusList(c);
            final List<HomeApps.Item> d = drawerList(c);
            final List<HomeApps.Item> o = appsChanged ? HomeApps.others(c) : null;
            handler.post(() -> {
                if (s != null) setShelf(s);
                setStatusItems(st);
                setDrawerItems(d);
                // All Apps open: its new list waits until it closes (the grid doesn't change under the focus).
                if (o != null && sheetOpen) pendingOthers = o;
                else if (o != null) setOthers(o);
            });
        });
    }

    /** Android's count of app changes (installs, updates, removals) at the last look; -1 = not looked yet. */
    private volatile int appsSeen = -1;

    // MARK: - Keys: only the arrows, OK and Back (system keys pass through to the system)

    boolean onKey(KeyEvent e) {
        wake();   // the answer to a key starts on the next frame
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down) move(-1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) move(1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down) move(0, -1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) move(0, 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                // Opens on release, and only if the press happened here: the release of an OK pressed inside another
                // app opens nothing.
                // Held (the remote repeats the key while it's down): the focused app's options instead; its release
                // then opens nothing.
                if (down) {
                    if (e.getRepeatCount() == 0) okDown = true;
                    else if (okDown && !menuOpen && heldLong(e) && openMenu()) okDown = false;
                } else {
                    if (okDown && !e.isCanceled() && spoken.keyClick()) press();
                    okDown = false;
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
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

    private static boolean heldLong(KeyEvent e) {
        return e.isLongPress() || e.getEventTime() - e.getDownTime() >= ViewConfiguration.getLongPressTimeout();
    }

    /** OK: the menu's focused option while it's open, otherwise the focused item. */
    private void press() {
        if (menuOpen) chooseMenu();
        else activate();
    }

    private void back() {
        if (menuOpen) {
            closeMenu();
        } else if (sheetOpen) {
            closeSheet();
        } else if (zone == Z_DRAWER) {
            closeDrawer();
            retarget(false);
        } else if (zone != Z_SHELF) {
            zone = Z_SHELF;
            retarget(false);
        } else {
            return;
        }
        click.move();
    }

    private void move(int dx, int dy) {
        if (menuOpen) {
            moveMenu(dx, dy);
            return;
        }
        if (sheetOpen) {
            moveGrid(dx, dy);
            return;
        }
        if (moveLight(dx, dy)) return;
        switch (zone) {
            case Z_SHELF:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(shelf.size() - 1, shelfIndex + dx));
                    if (next == shelfIndex) return;
                    shelfIndex = next;
                } else if (dy < 0) {
                    if (hero != null && hero.button) {
                        zone = Z_HERO;
                    } else if (!statusItems.isEmpty()) {
                        zone = Z_STATUS;
                        statusIndex = shelf.isEmpty() ? 0 : nearest(statusRects, tileRects[shelfIndex].centerX());
                    } else {
                        return;
                    }
                } else if (drawerItems.isEmpty()) {
                    openSheet();   // no drawer: straight to All Apps
                } else {
                    openDrawer();
                }
                break;
            case Z_HERO:
                if (dx != 0) {
                    if (!browse(dx)) return;
                } else if (dy < 0) {
                    if (statusItems.isEmpty()) return;
                    zone = Z_STATUS;
                    statusIndex = 0;
                } else {
                    zone = Z_SHELF;
                }
                break;
            case Z_STATUS:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(statusItems.size() - 1, statusIndex + dx));
                    if (next == statusIndex) return;
                    statusIndex = next;
                } else if (dy > 0) {
                    if (hero != null && hero.button) {
                        zone = Z_HERO;
                    } else {
                        zone = Z_SHELF;
                        if (!shelf.isEmpty()) shelfIndex = nearest(tileRects, statusRects[statusIndex].centerX());
                    }
                } else {
                    return;
                }
                break;
            default:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(drawerItems.size() - 1, drawerIndex + dx));
                    if (next == drawerIndex) return;
                    drawerIndex = next;
                } else if (dy < 0) {
                    closeDrawer();
                } else {
                    openSheet();   // ↓ all the way down: All Apps
                }
                break;
        }
        retarget(false);
        click.move();
    }

    private static int nearest(RectF[] rects, float x) {
        int best = 0;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < rects.length; i++) {
            float d = Math.abs(rects[i].centerX() - x);
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }

    private void moveGrid(int dx, int dy) {
        int n = others.size();
        if (n == 0) return;
        int r = gridIndex / COLUMNS, col = gridIndex % COLUMNS;
        int rows = (n + COLUMNS - 1) / COLUMNS;
        int next = gridIndex;
        if (dx != 0) {
            int candidate = gridIndex + dx;
            if (candidate >= 0 && candidate < n && candidate / COLUMNS == r) next = candidate;
        } else if (dy < 0 && r == 0) {
            // ↑ all the way up: back to the home screen, the drawer closed too.
            closeSheet();
            if (drawerOpen) closeDrawer();
            retarget(false);
            click.move();
            return;
        } else {
            int targetRow = r + dy;
            if (targetRow >= 0 && targetRow < rows) next = Math.min(n - 1, targetRow * COLUMNS + col);
        }
        if (next == gridIndex) return;
        gridIndex = next;
        retargetGrid(false);
        click.move();
        gridDirty = true;
    }

    private void activate() {
        HomeApps.Item it;
        RectF r;
        if (sheetOpen) {
            if (others.isEmpty()) return;
            it = others.get(gridIndex);
            r = focusedCell;
        } else if (zone == Z_HERO) {
            Recs.Pick p = currentPick();
            if (p == null || p.pkg == null) return;
            it = Recs.item(p);
            r = heroButton;
        } else if (zone == Z_STATUS) {
            if (statusItems.isEmpty()) return;
            it = statusItems.get(statusIndex);
            r = statusRects[statusIndex];
        } else if (zone == Z_DRAWER) {
            if (drawerItems.isEmpty()) return;
            it = drawerItems.get(drawerIndex);
            r = drawerRects[drawerIndex];
        } else if (zone == Z_TOP) {
            if (topIndex < 3) {
                selectTab(topIndex);
                return;
            }
            it = lightButtons.get(topIndex - 3);
            r = topRects[topIndex];
        } else if (zone == Z_CARDS) {
            Recs.Pick p = cardIndex < cards.size() ? cards.get(cardIndex) : null;
            if (p == null || p.pkg == null) return;
            it = Recs.item(p);
            r = cardRects[cardIndex];
        } else {
            if (shelf.isEmpty()) return;
            it = shelf.get(shelfIndex);
            r = tileRects[shelfIndex];
        }
        click.open();
        if (it.kind == HomeApps.ALL_APPS) {
            openSheet();
            return;
        }
        if (it.kind == HomeApps.INPUTS) {
            openDrawer();
            for (int i = 0; i < drawerItems.size(); i++) {
                if (drawerItems.get(i).kind == HomeApps.INPUT) {
                    drawerIndex = i;
                    break;
                }
            }
            retarget(false);
            return;
        }
        Bitmap art = launchArt(it);
        if (art != null && (zone == Z_CARDS || zone == Z_SHELF || sheetOpen)) {
            startLaunch(it, art, launchFrom(r));
            return;
        }
        openNow(it, r);
    }

    private void openNow(HomeApps.Item it, RectF r) {
        boolean opened = HomeApps.open(activity, it, this, Math.round(r.left), Math.round(r.top),
                Math.round(r.width()), Math.round(r.height()));
        if (!opened) {
            launchAt = 0;
            launchLayer.setVisibility(INVISIBLE);
            showToast(Ui.tr("Não deu para abrir ", "Couldn't open ") + it.label + ".");
        }
    }

    // MARK: - TalkBack: every reachable item as a virtual node (Spoken). Id = group * 1000 + index + 1.

    private static final int GRID = 9;

    private static int a11yId(int group, int index) {
        return group * 1000 + index + 1;
    }

    @Override public AccessibilityNodeProvider getAccessibilityNodeProvider() {
        return spoken;
    }

    private void a11yItems(List<Integer> out) {
        if (sheetOpen) {
            for (int i = 0; i < others.size(); i++) out.add(a11yId(GRID, i));
            return;
        }
        for (int i = 0; i < 3 + lightButtons.size(); i++) out.add(a11yId(Z_TOP, i));
        for (int i = 0; i < Math.min(SLOTS, cards.size()); i++) out.add(a11yId(Z_CARDS, i));
        for (int i = 0; i < shelf.size(); i++) out.add(a11yId(Z_SHELF, i));
        for (int i = 0; i < drawerItems.size(); i++) out.add(a11yId(Z_DRAWER, i));
    }

    private String a11yText(int id) {
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        switch (group) {
            case GRID:
                if (i >= others.size()) return "";
                return label(others, i) + ", " + (i + 1) + Ui.tr(" de ", " of ") + others.size();
            case Z_TOP:
                if (i >= 3) return label(lightButtons, i - 3);
                return tabs()[i] + Ui.tr(", aba ", ", tab ") + (i + 1) + Ui.tr(" de 3", " of 3");
            case Z_CARDS: {
                if (i >= cards.size()) return "";
                Recs.Pick p = cards.get(i);
                return (suggestion(p) ? badgeText + ": " : "") + pickText(p);
            }
            case Z_SHELF: return label(shelf, i);
            case Z_DRAWER: return label(drawerItems, i);
            default: return "";
        }
    }

    /**
     * Where the item is now. Items off screen (the closed drawer, the sheet's hidden rows, the top while the drawer is
     * open) get a thin strip at that edge, so TalkBack's arrows can still reach them; going there brings them in.
     */
    private boolean a11yBounds(int id, Rect out) {
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        float dy;
        switch (group) {
            case GRID: {
                if (!sheetOpen || i >= others.size()) return false;
                float gridWidth = COLUMNS * GRID_W + (COLUMNS - 1) * GRID_GAP;
                float x = ((1920 - gridWidth) / 2 + (i % COLUMNS) * (GRID_W + GRID_GAP)) * k;
                float y = (GRID_TOP + (i / COLUMNS - scroll.target) * GRID_ROW) * k;
                a11yRect.set(x, y, x + GRID_W * k, y + GRID_H * k);
                dy = 0;
                break;
            }
            case Z_TOP:
                if (i >= 3 + lightButtons.size()) return false;
                a11yRect.set(topRects[i]);
                dy = (i < 3 ? heroLayer : status).getTranslationY();
                break;
            case Z_CARDS:
                if (i >= Math.min(SLOTS, cards.size())) return false;
                a11yRect.set(cardRects[i]);
                dy = heroLayer.getTranslationY();
                break;
            case Z_SHELF:
                if (i >= tileRects.length) return false;
                a11yRect.set(tileRects[i]);
                dy = tiles.getTranslationY();
                break;
            case Z_DRAWER:
                if (i >= drawerRects.length) return false;
                a11yRect.set(drawerRects[i]);
                dy = drawerOpen ? 0 : h;
                break;
            default:
                return false;
        }
        a11yRect.offset(0, dy);
        float edge = 24 * k;
        if (a11yRect.top >= h - edge) a11yRect.set(a11yRect.left, h - edge, a11yRect.right, h);
        else if (a11yRect.bottom <= edge) a11yRect.set(a11yRect.left, 0, a11yRect.right, edge);
        a11yRect.round(out);
        return out.width() > 0 && out.height() > 0;
    }

    private int a11yFocused() {
        if (sheetOpen) return others.isEmpty() ? -1 : a11yId(GRID, gridIndex);
        switch (zone) {
            case Z_TOP: return a11yId(Z_TOP, topIndex);
            case Z_CARDS: return cardIndex < cards.size() ? a11yId(Z_CARDS, cardIndex) : -1;
            case Z_SHELF: return shelf.isEmpty() ? -1 : a11yId(Z_SHELF, shelfIndex);
            case Z_DRAWER: return drawerItems.isEmpty() ? -1 : a11yId(Z_DRAWER, drawerIndex);
            default: return -1;
        }
    }

    /** TalkBack moved to an item: the remote's focus goes there (opening or closing the drawer on the way). */
    private void a11yFocus(int id) {
        wake();
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        if (group == GRID) {
            if (!sheetOpen || i >= others.size()) return;
            gridIndex = i;
            retargetGrid(false);
            gridDirty = true;
            click.move();
            return;
        }
        if (sheetOpen) return;
        switch (group) {
            case Z_DRAWER:
                if (i >= drawerItems.size()) return;
                if (!drawerOpen) openDrawer();
                drawerIndex = i;
                break;
            case Z_TOP:
                if (i >= 3 + lightButtons.size()) return;
                if (drawerOpen) closeDrawer();
                topIndex = i;
                if (i < 3) selectTab(i);
                break;
            case Z_CARDS:
                if (i >= cards.size()) return;
                if (drawerOpen) closeDrawer();
                cardIndex = i;
                break;
            case Z_SHELF:
                if (i >= shelf.size()) return;
                if (drawerOpen) closeDrawer();
                shelfIndex = i;
                break;
            default:
                return;
        }
        zone = group;
        retarget(false);
        click.move();
    }

    private static String label(List<HomeApps.Item> list, int i) {
        return i >= 0 && i < list.size() && list.get(i).label != null ? list.get(i).label : "";
    }

    /** A recommendation: title, details and the app that plays it. */
    private static String pickText(Recs.Pick p) {
        if (p == null) return "";
        StringBuilder s = new StringBuilder(p.title == null ? "" : p.title);
        if (p.meta != null && !p.meta.isEmpty()) s.append(", ").append(p.meta);
        String app = Recs.serviceName(p);
        if (app != null && !app.isEmpty()) s.append(". App: ").append(app);
        return s.toString();
    }

    // MARK: - Light design: title, tabs, cards and icon-only buttons

    private void layoutLight() {
        if (lTab == null) return;
        // Text tabs; the focus pill extends 26 px past the text (the first tab's text starts at the margin).
        float pad = 26 * k, tabTop = L_TABBAR_TOP * k, tabBottom = (L_TABBAR_TOP + L_TABBAR_H) * k;
        float x = MARGIN * k - pad;
        for (int i = 0; i < 3; i++) {
            float tw = Math.max(lTabOn.measureText(tabs()[i]), lTab.measureText(tabs()[i]));
            topRects[i].set(x, tabTop, x + tw + 2 * pad, tabBottom);
            x = topRects[i].right + 4 * k;
        }
        float b = L_BUTTON * k, right = CONTENT_R * k, cy = L_BUTTON_CY * k;
        for (int i = BUTTONS - 1; i >= 0; i--) {
            topRects[3 + i].set(right - b, cy - b / 2, right, cy + b / 2);
            right -= b + L_BUTTON_GAP * k;
        }
        float g = L_CARD_GAP * k, left = MARGIN * k, width = (CONTENT_R - MARGIN) * k;
        float above = (L_TABBAR_TOP + L_TABBAR_H) * k, below = L_PLATTER_TOP * k;
        // Big card H tall, small ones (H − g) / 2, all 16:9, filling the width: 16/9·H + 2g + 2·16/9·(H − g) / 2.
        float bh = Math.min((9 * width - 2 * g) / 32, below - above - 60 * k), bw = bh * 16 / 9;
        float sh = (bh - g) / 2, sw = sh * 16 / 9;
        float top = above + (below - above - bh) / 2;
        cardRects[0].set(left, top, left + bw, top + bh);
        for (int col = 0; col < 2; col++) {
            for (int row = 0; row < 2; row++) {
                float sx = left + bw + g + col * (sw + g), sy = top + row * (sh + g);
                cardRects[1 + col * 2 + row].set(sx, sy, sx + sw, sy + sh);
            }
        }
        heroDirty = statusDirty = true;
    }

    /**
     * The three cards on screen for the current tab (Explore, Movies, Shows), from cardOffset in the tab's list, and the
     * background taken from the first one.
     */
    private void updateCards() {
        List<Recs.Pick> all = tab == 0 ? withSuggestions(watching) : recs.now(TAB_KIND[tab]);
        allCards = all;   // the same titles may come back with new details (how much is left)
        cardOffset = Math.max(0, Math.min(cardOffset, all.size() - SLOTS));
        if (cardShift.value >= 0.999f) shiftFrom = shiftTo = cardOffset;   // not mid-glide
        List<Recs.Pick> list = new ArrayList<>(all.subList(cardOffset, Math.min(all.size(), cardOffset + SLOTS)));
        if (!samePicks(list, cards)) {
            cards = list;
            cardIndex = Math.min(cardIndex, Math.max(0, list.size() - 1));
            if (zone == Z_CARDS && list.isEmpty()) {
                zone = Z_SHELF;
                retarget(false);
            }
        }
        cardCounter = all.size() > SLOTS ? (cardOffset + 1) + "–" + (cardOffset + list.size()) + Ui.tr(" de ", " of ") + all.size() : "";
        // A queue, in this order (the photos load one at a time, first asked first): the five on screen and the next
        // column of this tab, then the first five of Explore, Movies and Shows, so switching tabs is instant. The rest
        // of a top's list costs nothing until it comes up.
        for (int i = cardOffset; i < Math.min(all.size(), cardOffset + SLOTS + 2); i++) recs.backdrop(all.get(i));
        for (Recs.Pick p : recs.wanted()) recs.backdrop(p);
        updateBackground();
        heroDirty = true;
        spoken.focusMoved();    // new picks under the focused card
    }

    /**
     * Continue watching with fewer titles than cards: the empty ones get picks from the top (movies and shows, as the
     * other tabs have them), marked "Suggested"; never a title that is already there to continue.
     */
    private List<Recs.Pick> withSuggestions(List<Recs.Pick> continuing) {
        if (continuing.size() >= SLOTS) return continuing;
        List<Recs.Pick> out = new ArrayList<>(continuing);
        java.util.Set<String> titles = new java.util.HashSet<>();
        for (Recs.Pick p : continuing) titles.add(sameTitle(p.title));
        for (Recs.Pick p : recs.now(null)) {
            if (out.size() >= SLOTS) break;
            if (titles.add(sameTitle(p.title))) out.add(p);
        }
        return out;
    }

    private static String sameTitle(String title) {
        return title == null ? "" : title.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /** A pick filling an empty card of Continue watching (it has no link to where it stopped). */
    private boolean suggestion(Recs.Pick p) {
        return tab == 0 && p.intentUri == null;
    }

    private void selectTab(int index) {
        if (index == tab) return;
        tab = index;
        cardOffset = shiftFrom = shiftTo = 0;
        cardShift.snap(1);
        updateCards();
        heroDirty = true;
    }

    /**
     * Background: the first card's photo, heavily blurred and almost white; without it, a light gradient. At night, the
     * same photo almost black (or a dark gradient), drawn on top during the transition.
     */
    private void drawLightSky(Canvas c) {
        long up = SystemClock.uptimeMillis();
        float e = bgFadeAt == 0 ? 1 : fadeEase(bgFadeAt, up);
        boolean fading = bgFadeAt != 0;
        // Drawer open: the background lightens (by day) or darkens (at night), as a filter on the drawing itself.
        android.graphics.ColorFilter v = veil(tone(0.32f, 0.42f) * Ui.clamp(drawer.value), blend(0xFFFFFFFF, 0xFF000000, night));
        if (bgTo == null || (fading && bgFrom == null)) {
            if (lightGradient == null) lightGradient = new LinearGradient(0, 0, 0, h, 0xFFF5F5F7, 0xFFE2E2E7, Shader.TileMode.CLAMP);
            if (nightGradient == null) nightGradient = new LinearGradient(0, 0, 0, h, 0xFF1C1C1E, 0xFF0B0B0D, Shader.TileMode.CLAMP);
            fill.setColorFilter(v);
            if (night < 0.999f) {
                fill.setShader(lightGradient);
                c.drawRect(screen, fill);
            }
            if (night > 0.001f) {
                fill.setShader(nightGradient);
                fill.setAlpha(Math.round(255 * night));
                c.drawRect(screen, fill);
                fill.setAlpha(255);
            }
            fill.setShader(null);
            fill.setColorFilter(null);
        }
        imagePaint.setColorFilter(v);
        if (fading && bgFrom != null) drawLightImage(c, bgFrom, bgTo == null ? 1 - e : 1);
        if (bgTo != null) drawLightImage(c, bgTo, fading ? e : 1);
        imagePaint.setColorFilter(null);
    }

    private void drawLightImage(Canvas c, Backdrop b, float alpha) {
        if (night < 0.999f || b.nightImage == null) drawImage(c, b.lightImage, alpha);
        if (night > 0.001f) drawImage(c, b.nightImage, alpha * night);
    }

    private void drawLightTop(Canvas c) {
        applyTone();
        c.drawText(heading, MARGIN * k - headingBearing, L_TITLE_BASE * k, lTitle);
        drawGreeting(c);
        // Text tabs (the selected one in bold, in the main text color); with focus on the tabs, the selected one gets
        // the white pill with a shadow and grows a little (the tvOS focus).
        float focus = Ui.clamp(topFocus[tab].value);
        for (int i = 0; i < 3; i++) {
            RectF r = topRects[i];
            if (i == tab && focus > 0.01f) {
                float s = 1 + 0.05f * focus;
                cell.set(r.centerX() - r.width() * s / 2, r.centerY() - r.height() * s / 2,
                        r.centerX() + r.width() * s / 2, r.centerY() + r.height() * s / 2);
                float rr = cell.height() / 2;
                if (focus > 0.01f) {
                    tmp.set(cell);
                    tmp.offset(0, 8 * k * focus);
                    shadow.setColor(Ui.alpha(0xFF000000, tone(0.22f, 0.4f) * focus));
                    shadows.draw(c, tmp, rr, SHADOW_BLUR * k, shadow);
                }
                fill.setShader(null);
                fill.setColor(Ui.alpha(0xFFFFFFFF, focus));
                c.drawRoundRect(cell, rr, rr, fill);
            }
            Paint p = i == tab ? lTabOn : lTab;
            if (i == tab) lTabOn.setColor(blend(tone(L_INK, WHITE), L_INK, focus));   // always dark on the white pill
            c.drawText(tabs()[i], r.centerX() - p.measureText(tabs()[i]) / 2, r.centerY() + Ui.halfCap(p), p);
        }
        drawCards(c);
        if (tab == 0 && watching.isEmpty() && cards.isEmpty()) drawNothingToContinue(c);
        if (!cardCounter.isEmpty()) {
            // Above the last column, on the right: where the top's list is ("5–9 of 20").
            float cw = lCardMeta.measureText(cardCounter);
            c.drawText(cardCounter, cardRects[3].right - cw, cardRects[3].top - 18 * k, lCardMeta);
        }
        if (showClock) drawTopClock(c);
        else if (HomeData.coinCount() > 0) drawQuotes(c);
    }

    /** Brasília's time, whatever zone the TV is set to. */
    private final java.text.SimpleDateFormat brasilia = brasiliaFormat();
    private long topMinute = -1, topWeatherAt = -1;
    private String topTime = "", topTemp = "", topSymbol = "";
    private float topTimeW, topTempW;

    private static java.text.SimpleDateFormat brasiliaFormat() {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US);
        f.setTimeZone(java.util.TimeZone.getTimeZone("America/Sao_Paulo"));
        return f;
    }

    /**
     * The clock (24 h, Brasília) and the weather now (symbol + temperature), where the prices were and in their style:
     * a quiet line at the right end of the tab bar. Text is formatted and measured once a minute (or per forecast).
     */
    private void drawTopClock(Canvas c) {
        float right = CONTENT_R * k;   // the right margin, under the corner buttons (the shelf may be narrow)
        applyTone();
        Paint text = lQuote;
        float fade = 1;   // the corner buttons' names show in this row: fade out while one has focus
        for (int i = 3; i < TOP_ITEMS; i++) fade = Math.min(fade, 1 - Ui.clamp(topFocus[i].value));
        if (fade <= 0.01f) return;
        long now = System.currentTimeMillis();
        if (now / 60_000 != topMinute) {
            topMinute = now / 60_000;
            topTime = brasilia.format(new java.util.Date(now));
            topTimeW = text.measureText(topTime);
        }
        HomeData.Weather wx = data.weather;
        boolean showWeather = showWeatherOn && !Double.isNaN(wx.temp);
        if (data.weatherAt != topWeatherAt) {
            topWeatherAt = data.weatherAt;
            topTemp = HomeData.degrees(wx.temp);
            topTempW = text.measureText(topTemp);
            topSymbol = Symbols.weather(wx.code, wx.isDay);
        }
        float base = (L_TABBAR_TOP + L_TABBAR_H / 2) * k + Ui.halfCap(text);
        float cap = Ui.cap(text), gap = 6 * k, spacing = 24 * k, size = cap * 1.4f;
        float symbolW = showWeather ? Symbols.width(getContext(), topSymbol, size) : 0;
        float total = topTimeW + (showWeather ? spacing + symbolW + gap + topTempW : 0);
        int textColor = text.getColor();
        text.setColor(Ui.alpha(textColor, fade));
        float x = right - total;
        c.drawText(topTime, x, base, text);
        if (showWeather) {
            x += topTimeW + spacing;
            Symbols.draw(getContext(), c, topSymbol, x + symbolW / 2, base - cap / 2, size,
                    Ui.alpha(tone(0xB38E8E93, 0x8CFFFFFF), fade));
            c.drawText(topTemp, x + symbolW + gap, base, text);
        }
        text.setColor(textColor);
    }

    /**
     * The cards: every title between the two offsets while the list moves (the ones leaving fade out, the new ones fade
     * in), only the five on screen otherwise; the focused one last, so it grows over its neighbors.
     */
    private void drawCards(Canvas c) {
        int first = Math.min(shiftFrom, shiftTo), last = Math.min(allCards.size(), Math.max(shiftFrom, shiftTo) + SLOTS);
        int focused = zone == Z_CARDS ? shiftTo + cardIndex : -1;
        for (int j = first; j < last; j++) if (j != focused) drawCard(c, j);
        if (focused >= first && focused < last) drawCard(c, focused);
    }

    /** Where slot `s` is; past the edges (the titles leaving and arriving), one column further out. */
    private void slotRect(int s, RectF out) {
        if (s >= 0 && s < SLOTS) {
            out.set(cardRects[s]);
        } else if (s < 0) {
            slotRect(s + 2, out);
            out.offset(-(cardRects[0].width() + L_CARD_GAP * k), 0);
        } else {
            slotRect(s - 2, out);
            out.offset(cardRects[3].left - cardRects[1].left, 0);
        }
    }

    /**
     * Recommendation card j of the tab's list: the photo (rounded, with a soft shadow) and, over its darkened bottom, the
     * title's logo (or its name, when TMDB has no logo). The big card also says the genre and where it streams. When
     * focused, it grows 5%, rises and lights up its border.
     */
    private void drawCard(Canvas c, int j) {
        Recs.Pick p = allCards.get(j);
        float t = cardShift.value, tc = Ui.clamp(t);
        int from = j - shiftFrom, to = j - shiftTo;
        slotRect(from, slotFrom);
        slotRect(to, slotTo);
        float alpha = (from >= 0 && from < SLOTS ? 1 - tc : 0) + (to >= 0 && to < SLOTS ? tc : 0);
        if (alpha <= 0.01f) return;
        float bw = slotFrom.width() + (slotTo.width() - slotFrom.width()) * t;
        float bh = slotFrom.height() + (slotTo.height() - slotFrom.height()) * t;
        float bx = slotFrom.centerX() + (slotTo.centerX() - slotFrom.centerX()) * t;
        float by = slotFrom.centerY() + (slotTo.centerY() - slotFrom.centerY()) * t;
        float v = to >= 0 && to < SLOTS ? cardFocus[to].value : 0, f = Ui.clamp(v), s = 1 + 0.05f * v;
        float r0 = 18 * k, cy = by - 6 * k * v;
        cell.set(bx - bw * s / 2, cy - bh * s / 2, bx + bw * s / 2, cy + bh * s / 2);
        float r = r0 * s;
        int save = alpha < 0.99f ? c.saveLayerAlpha(cell.left - 60 * k, cell.top - 60 * k, cell.right + 60 * k,
                cell.bottom + 80 * k, Math.round(255 * alpha)) : -1;
        c.save();
        c.translate(bx, cy);
        c.scale(s, s);
        tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
        tmp.offset(0, (10 + 14 * f) * k);
        shadowDeep.setColor(Ui.alpha(0xFF000000, tone(0.14f, 0.32f) + tone(0.2f, 0.28f) * f));
        shadows.draw(c, tmp, r0, DEEP_BLUR * k, shadowDeep);
        c.restore();
        // How big this card is now, from a small one (0) to the big one (1): it changes while the list moves.
        float big = Ui.clamp((bw - cardRects[1].width()) / (cardRects[0].width() - cardRects[1].width()));
        Backdrop b = recs.backdrop(p);
        Bitmap img = b != null ? (big > 0.5f ? b.wide : b.small) : recs.preview(p);   // the saved one until it's built
        if (img != null) {
            roundImage(c, img, cell, r);
        } else {
            fill.setShader(null);
            fill.setColor(tone(0xFFDADADF, 0xFF2C2C2E));
            c.drawRoundRect(cell, r, r, fill);
        }
        // A title with no picture at all (some apps' Watch Next rows) still shows its name on the placeholder.
        boolean named = img != null || p.backdrop == null;
        // The apps' own pictures for what to continue usually have the name written on them: only the details (app,
        // time left) go on top, and only on the big card.
        boolean ownName = p.intentUri != null && img != null;
        if (ownName && big < 0.01f) named = false;
        if (named) drawUnitShader(c, cell, r, veil, CARD_SCRIM, 1);
        if (f > 0.01f) {
            drawUnitShader(c, cell, r, sheen, CARD_SHEEN, f);
            drawUnitShader(c, cell, r, rim, CARD_RIM, f);
        }
        boolean focused = j == focusedCard;
        if (focused && img != null) drawSweep(c, cell, r);
        float preview = focused ? previewAmount() : 0;
        if (preview > 0 && p.overview.isEmpty()) preview = 0;   // nothing to say (Continue Watching's rows)
        float lift = p.progress > 0.01f ? drawProgress(c, cell, p.progress, s) : 0;
        if (named && preview < 0.99f) {
            // The name gives way to the synopsis (a layer only while the two cross).
            int under = preview > 0.01f ? c.saveLayerAlpha(cell, Math.round(255 * (1 - preview))) : -1;
            drawCardName(c, p, cell, big, lift, !ownName);
            if (under >= 0) c.restoreToCount(under);
        }
        if (preview > 0.01f) drawPreview(c, p, cell, r, big, preview);
        if (suggestion(p)) drawBadge(c, cell, s);
        if (save >= 0) c.restoreToCount(save);
    }

    /** The logo (or the name) at the bottom left of the photo; on the big card, the genre and the app below it. */
    private void drawCardName(Canvas c, Recs.Pick p, RectF card, float big, float lift, boolean withName) {
        String[] text = cardTexts(p);
        float cw = card.width(), ch = card.height();
        float x = card.left + cw * 0.05f;
        float metaAlpha = big * big;
        float bottom = card.bottom - lift - ch * (0.08f + 0.02f * big) - (metaAlpha > 0.01f && !text[2].isEmpty() ? 40 * k * big : 0);
        if (metaAlpha > 0.01f && !text[2].isEmpty()) {
            onPhotoMeta.setAlpha(Math.round(217 * metaAlpha));
            c.drawText(text[2], x, card.bottom - lift - ch * 0.08f, onPhotoMeta);
        }
        if (!withName) return;
        Bitmap logo = recs.logo(p);
        if (logo != null) {
            float maxW = cw * (0.5f - 0.08f * big), maxH = ch * (0.3f - 0.04f * big);
            float scale = Math.min(maxW / logo.getWidth(), maxH / logo.getHeight());
            tmp.set(x, bottom - logo.getHeight() * scale, x + logo.getWidth() * scale, bottom);
            c.drawBitmap(logo, null, tmp, bitmapPaint);
            return;
        }
        onPhotoTitle.setTextSize((24 + 16 * big) * k);
        c.drawText(big > 0.5f ? text[1] : text[0], x, bottom, onPhotoTitle);
    }

    /**
     * How much was watched, as on Google TV: a thin bar along the bottom of the photo. Returns the room it takes, so
     * the name sits above it.
     */
    private float drawProgress(Canvas c, RectF card, float progress, float s) {
        float pad = 16 * k * s, height = 6 * k * s, bottom = card.bottom - 14 * k * s;
        tmp.set(card.left + pad, bottom - height, card.right - pad, bottom);
        fill.setShader(null);
        fill.setColor(0x4DFFFFFF);
        c.drawRoundRect(tmp, height / 2, height / 2, fill);
        tmp.right = tmp.left + Math.max(height, tmp.width() * progress);
        fill.setColor(0xF2FFFFFF);
        c.drawRoundRect(tmp, height / 2, height / 2, fill);
        return 14 * k * s + height;
    }

    /** "Suggested", small at the top left of the photo: this card isn't something you were watching. */
    private void drawBadge(Canvas c, RectF card, float s) {
        float padX = 12 * k, h = 32 * k, inset = 14 * k * s;
        tmp.set(card.left + inset, card.top + inset, card.left + inset + badgeWidth + 2 * padX, card.top + inset + h);
        fill.setShader(null);
        fill.setColor(0x8C000000);
        c.drawRoundRect(tmp, h / 2, h / 2, fill);
        c.drawText(badgeText, tmp.left + padX, tmp.centerY() + Ui.halfCap(badgePaint), badgePaint);
    }

    /** Continue watching with nothing in it: why, in the cards' place. */
    private void drawNothingToContinue(Canvas c) {
        String title = watchAllowed ? Ui.tr("Nada para continuar ainda", "Nothing to continue yet", "Nada para seguir todavía")
                : Ui.tr("Sem acesso ao que você assiste", "No access to what you watch", "Sin acceso a lo que ves");
        String line = watchAllowed
                ? Ui.tr("O que você começar no YouTube, na Netflix e em outros apps aparece aqui.",
                        "What you start on YouTube, Netflix and other apps shows up here.",
                        "Lo que empieces en YouTube, Netflix y otras apps aparece aquí.")
                : Ui.tr("Aceite quando a TV pedir, ou troque o Início de novo em Free My TV → Início.",
                        "Allow it when the TV asks, or switch the home screen again in Free My TV → Home Screen.",
                        "Acéptalo cuando el TV lo pida, o cambia el inicio otra vez en Free My TV → Inicio.");
        float cx = (cardRects[0].left + cardRects[4].right) / 2, cy = (cardRects[0].top + cardRects[0].bottom) / 2;
        float tw = lTabOn.measureText(title), lw = lCardMeta.measureText(line);
        lTabOn.setColor(tone(L_INK, WHITE));
        c.drawText(title, cx - tw / 2, cy - 8 * k, lTabOn);
        c.drawText(line, cx - lw / 2, cy + 36 * k, lCardMeta);
    }

    /** Reads the apps' Watch Next lists in the background; the tab is redrawn only when something changed. */
    private void loadWatching() {
        final Context c = getContext().getApplicationContext();
        LOADER.execute(() -> {
            final boolean allowed = WatchNext.allowed(c);
            final List<Recs.Pick> list = WatchNext.load(c);
            StringBuilder key = new StringBuilder();
            for (Recs.Pick p : list) key.append(Recs.key(p)).append(p.meta).append(Math.round(p.progress * 100)).append('|');
            final String newKey = key.toString();
            handler.post(() -> {
                if (newKey.equals(watchingKey) && allowed == watchAllowed) return;
                watchingKey = newKey;
                watchAllowed = allowed;
                watching = list;
                ui.edit().putString("watching", WatchNext.toJson(list)).apply();
                if (tab == 0) updateCards();
            });
        });
    }

    /** No permission yet (the switch grants it; an older switch didn't): the TV asks once in a while. */
    private void askWatchPermission() {
        boolean askedLately = askedWatchAt != 0 && SystemClock.uptimeMillis() - askedWatchAt < 10 * 60_000L;
        if (WatchNext.allowed(activity) || askedLately) return;
        askedWatchAt = SystemClock.uptimeMillis();
        activity.requestPermissions(new String[] {WatchNext.PERMISSION}, 1);
    }

    // Each title's name and details cut to fit (small card, big card), made once: no work while the cards move.
    private final Map<Recs.Pick, String[]> cardTexts = new HashMap<>();

    private String[] cardTexts(Recs.Pick p) {
        String[] t = cardTexts.get(p);
        String meta = p.meta == null ? "" : p.meta;
        if (t != null && t[3].equals(meta)) return t;   // the details arrive after the title
        if (cardTexts.size() > 60) cardTexts.clear();
        float small = cardRects[1].width() * 0.9f, wide = cardRects[0].width() * 0.9f;
        String title = p.title == null ? "" : p.title;
        TextPaint name = new TextPaint(onPhotoTitle);
        name.setTextSize(24 * k);
        String smallTitle = TextUtils.ellipsize(title, name, small, TextUtils.TruncateAt.END).toString();
        name.setTextSize(40 * k);
        String wideTitle = TextUtils.ellipsize(title, name, wide, TextUtils.TruncateAt.END).toString();
        String details = TextUtils.ellipsize(meta, new TextPaint(onPhotoMeta), wide, TextUtils.TruncateAt.END).toString();
        t = new String[] {smallTitle, wideTitle, details, meta};
        cardTexts.put(p, t);
        return t;
    }

    // Gradients made once, from 0 to 1, and stretched over each shape by a matrix: the focus animation used to build
    // new ones on every frame. The focus amount goes in the paint's alpha, which scales the gradient's.
    private static final LinearGradient CARD_SHEEN = new LinearGradient(0, 0, 0.5f, 1, 0x38FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
    /** The photo's bottom darkened a little, so the logo and the text read on any image. */
    private static final LinearGradient CARD_SCRIM = new LinearGradient(0, 0.4f, 0, 1, 0x00000000, 0x9E000000, Shader.TileMode.CLAMP);
    private static final LinearGradient CARD_RIM = new LinearGradient(0, 0, 0, 1, 0xE6FFFFFF, 0x59FFFFFF, Shader.TileMode.CLAMP);
    private static final LinearGradient TILE_SHEEN = new LinearGradient(0, 0, 0, 0.5f, 0x14FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
    private static final LinearGradient GRID_SHEEN = new LinearGradient(0, 0, 0, 0.5f, 0x1AFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
    private final Map<String, LinearGradient> tileGradients = new HashMap<>();   // brand colors, by app
    private final Matrix unitMatrix = new Matrix();

    private void drawUnitShader(Canvas c, RectF shape, float r, Paint paint, LinearGradient gradient, float amount) {
        unitMatrix.setScale(shape.width(), shape.height());
        unitMatrix.postTranslate(shape.left, shape.top);
        gradient.setLocalMatrix(unitMatrix);
        paint.setShader(gradient);
        paint.setAlpha(Math.round(255 * Ui.clamp(amount)));
        c.drawRoundRect(shape, r, r, paint);
        paint.setShader(null);
        paint.setAlpha(255);
    }

    /**
     * Live TV, HDMI Inputs and Settings: icon-only glass circles (light by day, smoked at night); the focused one turns
     * white with a dark icon (the tvOS focus) and its name appears below it.
     */
    private void drawLightButtons(Canvas c) {
        applyTone();
        for (int i = 0; i < BUTTONS; i++) {
            RectF r = topRects[3 + i];
            float f = Ui.clamp(topFocus[3 + i].value), s = 1 + 0.1f * f, rad = r.width() / 2 * s;
            cell.set(r.centerX() - rad, r.centerY() - rad, r.centerX() + rad, r.centerY() + rad);
            tmp.set(cell);
            tmp.offset(0, (6 + 8 * f) * k);
            shadow.setColor(Ui.alpha(0xFF000000, tone(0.1f, 0.3f) + tone(0.14f, 0.2f) * f));
            shadows.draw(c, tmp, rad, SHADOW_BLUR * k, shadow);
            scene.liquid(c, cell, rad, tone(0xFFFFFFFF, SMOKE), tone(0.55f, 0.5f), tone(0.02f, 0.01f), 12 * k, 8 * k,
                    tone(0.6f, 0.45f), 1 - f);
            if (f > 0.01f) {
                fill.setShader(null);
                fill.setColor(Ui.alpha(0xFFFFFFFF, f));
                c.drawCircle(cell.centerX(), cell.centerY(), rad, fill);
            }
            Symbols.draw(getContext(), c, lightButtons.get(i).symbol, cell.centerX(), cell.centerY(), 27 * k * s,
                    blend(tone(L_INK, 0xEBFFFFFF), L_INK, f));
            if (f > 0.01f) {
                String label = lightButtons.get(i).label;
                float half = lTip.measureText(label) / 2;
                float x = Math.min(r.centerX(), CONTENT_R * k - half);
                lTip.setAlpha(Math.round(255 * f));
                c.drawText(label, x, r.bottom + (40 + 4 * f) * k, lTip);
            }
        }
    }

    private boolean moveLight(int dx, int dy) {
        switch (zone) {
            case Z_SHELF:
                if (dy >= 0 || shelf.isEmpty()) return false;
                if (cards.isEmpty()) {
                    zone = Z_TOP;
                    topIndex = tab;
                } else {
                    zone = Z_CARDS;
                    cardIndex = nearestCard(tileRects[shelfIndex].centerX(), true);
                }
                break;
            case Z_CARDS:
                if (!moveCards(dx, dy)) return true;
                break;
            case Z_TOP:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(TOP_ITEMS - 1, topIndex + dx));
                    if (next == topIndex) return true;
                    topIndex = next;
                    if (topIndex < 3) selectTab(topIndex);   // like Apple TV tabs: focusing a tab selects it
                } else if (dy > 0) {
                    if (cards.isEmpty()) {
                        zone = Z_SHELF;
                    } else {
                        zone = Z_CARDS;
                        cardIndex = nearestCard(topRects[topIndex].centerX(), false);
                    }
                } else {
                    return true;
                }
                break;
            default:
                return false;
        }
        retarget(false);
        click.move();
        return true;
    }

    /**
     * The arrows on the cards (big one on the left, two columns of two on the right). Past the right column, the list
     * moves one column along (the bottom left small one becomes the big one); ← on the big one moves it back. ↑ from
     * the top row goes to the tabs, ↓ from the bottom row to the shelf. False: nothing to do.
     */
    private boolean moveCards(int dx, int dy) {
        int next = cardIndex;
        if (dx > 0) {
            if (cardIndex >= 3) return slideCards(Math.min(2, allCards.size() - SLOTS - cardOffset));
            next = cardIndex == 0 ? 1 : cardIndex + 2;
        } else if (dx < 0) {
            if (cardIndex == 0) return slideCards(-Math.min(2, cardOffset));
            next = cardIndex <= 2 ? 0 : cardIndex - 2;
        } else if (dy < 0) {
            if (cardIndex == 0 || cardIndex % 2 == 1) {
                zone = Z_TOP;
                topIndex = tab;     // as on Apple TV: moving up to the tabs focuses the selected tab
                return true;
            }
            next = cardIndex - 1;
        } else {
            if (cardIndex == 0 || cardIndex % 2 == 0 || cardIndex + 1 >= cards.size()) {
                zone = Z_SHELF;
                if (!shelf.isEmpty()) shelfIndex = nearest(tileRects, tileRects.length, cardRects[cardIndex].centerX());
                return true;
            }
            next = cardIndex + 1;
        }
        // A slot with no title (fewer than five): the nearest one before it.
        while (next >= cards.size() && next > 0) next--;
        if (next == cardIndex) return false;
        cardIndex = next;
        return true;
    }

    /** Moves the tab's list `by` titles (the cards glide to their new slots); false when there is nothing more. */
    private boolean slideCards(int by) {
        if (by == 0) return false;
        shiftFrom = cardOffset;
        cardOffset += by;
        shiftTo = cardOffset;
        cardShift.snap(0);
        cardShift.target = 1;
        updateCards();
        shiftTo = cardOffset;   // updateCards may clamp it
        return true;
    }

    /** The card closest to `x` in the top row (from the tabs) or the bottom row (from the shelf); the big one is in both. */
    private int nearestCard(float x, boolean bottomRow) {
        int best = 0;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < Math.min(SLOTS, cards.size()); i++) {
            if (i != 0 && (i % 2 == 0) != bottomRow) continue;
            float d = Math.abs(cardRects[i].centerX() - x);
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }

    private static int nearest(RectF[] rects, int count, float x) {
        int best = 0;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < Math.min(count, rects.length); i++) {
            float d = Math.abs(rects[i].centerX() - x);
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }

    // MARK: - Helpers

    private static float smoothstep(float e0, float e1, float x) {
        float t = Ui.clamp((x - e0) / (e1 - e0));
        return t * t * (3 - 2 * t);
    }

    private static int blend(int a, int b, float f) {
        int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return (Math.round(aa + (ba - aa) * f) << 24) | (Math.round(ar + (br - ar) * f) << 16)
                | (Math.round(ag + (bg - ag) * f) << 8) | Math.round(ab + (bb - ab) * f);
    }
}
