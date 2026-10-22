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

    static final class SettingsView extends View implements Choreographer.FrameCallback {
        private static final int MAIN = 0, HOME_CHOICE = 1, HOME_SETTINGS = 2, REMOTE_KEYS = 3, PICKER = 4, SHELF_EDIT = 5, MORE = 6,
                WATCH_APPS = 7;
        private static final float LIST_ROW_H = 68, LIST_ROW_GAP = 6;
        private static final int PICK_VISIBLE = 9;
        private static final float LIST_X = 1000, LIST_R = 1824, LIST_TOP = 186, ROW_H = 66, ROW_GAP = 6, CHOICE_H = 104;   // the menu: 7 rows and the notes below fit
        private static final int AMBER = 0xFFFFC266, INK = 0xFF1C1C1E;

        private final SettingsActivity activity;
        // The background: For You's (light by day, dark at night, or as the theme says), with a soft shade at the edges.
        private boolean lightTheme = true;
        private final Paint backdrop = new Paint(), shade = new Paint();
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Spring[] pop = new Spring[12];
        private final List<String> appPkgs = new ArrayList<>(), appLabels = new ArrayList<>();
        // The list picker (the app of a remote button): what it shows, which one is in use, where we are.
        private final List<String> pickLabels = new ArrayList<>();
        // The shelf editor: the apps it can hold (selected ones first, in order) and the selection.
        private final List<String[]> shelfRows = new ArrayList<>();
        private final List<String> shelfOrder = new ArrayList<>();
        private final List<String[]> watchRows = new ArrayList<>();   // More Settings > Continue watching: {package, name}
        private int pickCurrent, pickScroll;
        // Where the picker came from (that screen and row get the focus back), its title, and what a pick does.
        private int pickReturnScreen, pickReturnRow;
        private String pickTitle = "";
        private java.util.function.IntConsumer pickApply;
        private final RectF tmp = new RectF(), rowRect = new RectF(), focusBox = new RectF();
        private JSONObject state = new JSONObject(), recs = new JSONObject();
        private boolean hasKey;
        private int screen = MAIN, focus;
        private boolean running, okDown, backDown, wasBusy;
        private StaticLayout errorLayout;   // the last error, wrapped (rebuilt only when it changes)
        private String errorShown;
        private final Spoken spoken;
        private long startedAt, lastNanos, pendingAt, lastSky;
        private int w, h;
        private float k = 1;
        private Paint title, subtitle, version, rowLabel, rowValue, rowSub, header, cellLabel, cellValue, warn, spinner, white, shadow;
        private TextPaint notice;
        // Made with the size and the theme, not on every frame: a list redraws on every frame of its focus animation.
        private Paint heading, hint;
        private final TextPaint fit = new TextPaint();   // for cutting a long name to its row

        private final Runnable poll = new Runnable() {
            @Override public void run() {
                if (!running) return;
                state = HomeMode.state(getContext());
                hasKey = Recs.hasKey(getContext());
                recs = Recs.status(getContext());
                boolean nowBusy = !state.optString("busy").isEmpty();
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
            for (int i = 0; i < pop.length; i++) pop[i] = new Spring(0.28f, 0.85f, 0);
            setFocusable(true);
            setContentDescription("Free My TV");
            // TalkBack: one item per row, id = row + 1.
            spoken = new Spoken(this, new Spoken.Source() {
                @Override public void items(List<Integer> out) { for (int i = 1; i <= rowCount(); i++) out.add(i); }
                @Override public String text(int id) { return id >= 1 && id <= rowCount() ? rowText(id - 1) : ""; }
                @Override public boolean bounds(int id, Rect out) {
                    if (id < 1 || id > rowCount()) return false;
                    rowBox(id - 1).round(out);
                    return true;
                }
                @Override public int focused() { return focus + 1; }
                @Override public void focus(int id) {
                    focus = id - 1;
                    invalidate();
                }
                @Override public void click(int id) { activate(); }
            });
        }

        @Override public AccessibilityNodeProvider getAccessibilityNodeProvider() {
            return spoken;
        }

        /** A row as TalkBack reads it: its name and its value, as on screen. */
        private String rowText(int row) {
            switch (screen) {
                case MAIN: {
                    if (row == 0) {
                        String busyText = state.optString("busy");
                        String value = busy() ? (busyText.isEmpty() ? Ui.tr("Começando…", "Starting…") : busyText)
                                : freeActive() ? "Free My TV" : Ui.tr("Original", "Original");
                        return Ui.tr("Início, ", "Home Screen, ") + value;
                    }
                    String[] names = {Ui.tr("Tela de Descanso, ver agora", "Screensaver, preview"), Ui.tr("Ajustes do Início", "Home Settings"),
                            Ui.tr("Mais Ajustes", "More Settings"), Ui.tr("Prateleira", "Shelf"), Ui.tr("Botões do Controle", "Remote Buttons"),
                            Ui.tr("Ajustes da TV", "TV Settings")};
                    return row - 1 < names.length ? names[row - 1] : "";
                }
                case HOME_CHOICE: {
                    String name = row == 0 ? "Free My TV, " + Ui.tr("sem anúncios, com a tela de descanso da TV", "no ads, with the TV's own screensaver")
                            : "Original, " + Ui.tr("o Início de fábrica da TV", "the TV's factory home screen");
                    boolean inUse = (row == 0) == freeActive();
                    return name + (inUse ? Ui.tr(". Em uso", ". In use") : "") + ", " + (row + 1) + Ui.tr(" de 2", " of 2");
                }
                case HOME_SETTINGS:
                case MORE:
                case REMOTE_KEYS: {
                    String[] labels = listLabels(), values = listValues();
                    return row < labels.length ? labels[row] + ", " + values[row] : "";
                }
                case PICKER: {
                    int at = pickScroll + row;
                    if (at >= pickLabels.size()) return "";
                    return pickLabels.get(at) + (at == pickCurrent ? Ui.tr(", escolhido", ", selected") : "")
                            + ", " + (at + 1) + Ui.tr(" de ", " of ") + pickLabels.size();
                }
                case WATCH_APPS: {
                    int at = pickScroll + row;
                    if (at >= watchRows.size()) return "";
                    String[] app = watchRows.get(at);
                    return app[1] + ", " + onOff(!Prefs.watchNextOff(app[0]));
                }
                case SHELF_EDIT: {
                    int at = pickScroll + row;
                    if (at >= shelfRows.size()) return "";
                    String[] app = shelfRows.get(at);
                    int place = shelfOrder.indexOf(app[0]);
                    return app[1] + ", " + (place >= 0 ? Ui.tr("na prateleira, posição ", "on the shelf, place ") + (place + 1)
                            : Ui.tr("fora da prateleira", "not on the shelf"));
                }
                default:
                    return "";
            }
        }

        private RectF rowBox(int row) {
            float top, height;
            if (screen == MAIN) {
                top = LIST_TOP + row * (ROW_H + ROW_GAP);
                height = ROW_H;
            } else if (screen == HOME_CHOICE) {
                top = LIST_TOP + 20 + row * (CHOICE_H + ROW_GAP);
                height = CHOICE_H;
            } else {
                top = LIST_TOP + 20 + row * (LIST_ROW_H + LIST_ROW_GAP);
                height = LIST_ROW_H;
            }
            focusBox.set(LIST_X * k, top * k, LIST_R * k, (top + height) * k);
            return focusBox;
        }

        void resume() {
            if (running) return;
            running = true;
            startedAt = SystemClock.uptimeMillis();
            lastNanos = 0;
            retheme();
            handler.post(poll);
            Choreographer.getInstance().postFrameCallback(this);
            spoken.readAgain();
        }

        void pause() {
            running = false;
            handler.removeCallbacks(poll);
            Choreographer.getInstance().removeFrameCallback(this);
        }

        @Override public void doFrame(long frameTimeNanos) {
            if (!running) return;
            boolean moving = busy();
            for (Spring s : pop) moving |= !s.settled();
            if (moving) invalidate();   // still: nothing to redraw (the background no longer moves)
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

        /** The background and the text follow the theme (it can change here, or turn to night while open). */
        private TextPaint fitted(Paint p) {
            fit.set(p);
            return fit;
        }

        private void retheme() {
            if (w > 0 && lightTheme() != lightTheme) onSizeChanged(w, h, w, h);
        }

        /** Light like the home screen by day (theme: auto, 6:00 to 18:00), or as Home Settings > Theme says. */
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
            return !state.optString("busy").isEmpty() || (pendingAt != 0 && SystemClock.uptimeMillis() - pendingAt < 10_000);
        }

        private boolean freeActive() {
            return HomeMode.FREE.equals(state.optString("mode")) && !state.optBoolean("homeReverted");
        }

        private int rowCount() {
            switch (screen) {
                case MAIN: return 7;
                case HOME_CHOICE: return 2;
                case HOME_SETTINGS: return settingLabels().length;
                case MORE: return moreLabels().length;
                case PICKER: return Math.min(PICK_VISIBLE, pickLabels.size());
                case SHELF_EDIT: return Math.min(PICK_VISIBLE, shelfRows.size());
                case WATCH_APPS: return Math.min(PICK_VISIBLE, watchRows.size());
                default: return Prefs.KEYS.length;
            }
        }

        @Override protected void onDraw(Canvas c) {
            if (w == 0) return;
            long now = System.nanoTime();
            float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.05f, (now - lastNanos) / 1e9f);
            lastNanos = now;
            for (int i = 0; i < pop.length; i++) {
                pop[i].target = i == focus && i < rowCount() ? 1 : 0;
                pop[i].step(dt);
            }
            long up = SystemClock.uptimeMillis();
            float seconds = (up - startedAt) / 1000f;
            c.drawRect(0, 0, w, h, backdrop);
            c.drawRect(0, 0, w, h, shade);

            // On the left: big symbol, title, description.
            float cx = 500 * k;
            Symbols.draw(getContext(), c, "sparkles", cx, 400 * k, 150 * k, fg(0xEBFFFFFF));
            c.drawText("Free My TV", cx, 612 * k, title);
            c.drawText(Ui.tr("Início e tela de descanso da TV", "Your TV's home screen and screensaver"), cx, 660 * k, subtitle);
            c.drawText(versionName(getContext()), cx, 704 * k, version);

            if (screen == MAIN) drawMain(c, seconds); else if (screen == HOME_CHOICE) drawChoice(c); else if (screen == PICKER) drawPicker(c); else if (screen == SHELF_EDIT) drawShelfEditor(c); else if (screen == WATCH_APPS) drawWatchApps(c); else drawList(c);
        }

        private void drawMain(Canvas c, float seconds) {
            boolean working = busy();
            String busyText = state.optString("busy");
            String mode = freeActive() ? "Free My TV" : Ui.tr("Original", "Original");
            String[] labels = {Ui.tr("Início", "Home Screen"), Ui.tr("Tela de Descanso", "Screensaver"),
                    Ui.tr("Ajustes do Início", "Home Settings"), Ui.tr("Mais Ajustes", "More Settings"), Ui.tr("Prateleira", "Shelf"),
                    Ui.tr("Botões do Controle", "Remote Buttons"), Ui.tr("Ajustes da TV", "TV Settings")};
            String[] values = {working ? "" : mode, Ui.tr("Ver agora", "Preview"), "", "", "", "", ""};
            for (int i = 0; i < labels.length; i++) {
                float y = (LIST_TOP + i * (ROW_H + ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + ROW_H * k);
                float f = drawRowBackground(c, i);
                int ink = blend(fg(0xF2FFFFFF), INK, f);
                rowLabel.setColor(ink);
                float base = rowRect.centerY() + Ui.halfCap(rowLabel);
                c.drawText(labels[i], rowRect.left + 30 * k, base, rowLabel);
                float right = rowRect.right - 30 * k;
                if (i >= 2) {
                    float cw = Symbols.width(getContext(), "chevron_right", 22 * k);
                    Symbols.draw(getContext(), c, "chevron_right", right - cw / 2, rowRect.centerY(), 22 * k, blend(fg(0x66FFFFFF), 0x991C1C1E, f));
                } else if (i == 0 && working) {
                    // Switching: the current step in place of the value, with the spinning arc.
                    String text = busyText.isEmpty() ? Ui.tr("Começando…", "Starting…") : busyText;
                    rowValue.setColor(blend(fg(0xB3FFFFFF), 0xB31C1C1E, f));
                    String shown = TextUtils.ellipsize(text, fitted(rowValue), 470 * k, TextUtils.TruncateAt.END).toString();
                    c.drawText(shown, right, base, rowValue);
                    float sx = right - rowValue.measureText(shown) - 26 * k;
                    spinner.setColor(blend(fg(0xFFFFFFFF), INK, f));
                    Ui.spinner(c, sx, rowRect.centerY(), 12 * k, seconds * 1.3f, spinner);
                } else {
                    rowValue.setColor(blend(fg(0x8CFFFFFF), 0x8C1C1C1E, f));
                    c.drawText(values[i], right, base, rowValue);
                }
            }

            float y = (LIST_TOP + labels.length * (ROW_H + ROW_GAP) + 20) * k;
            String error = state.optString("error");
            if (working && (busyText.contains("Permitir") || busyText.contains("Allow"))) {
                c.drawText(Ui.tr("Na TV: marque “Sempre permitir” e aperte OK.", "On the TV: tick “Always allow” and press OK."),
                        LIST_X * k + 30 * k, y, warn);
                y += 46 * k;
            } else if (!working && !error.isEmpty()) {
                // The whole message, on up to five lines: some say what to do (the Wi-Fi one, #14).
                int width = Math.round(760 * k);
                if (errorLayout == null || !error.equals(errorShown) || errorLayout.getWidth() != width) {
                    errorShown = error;
                    errorLayout = StaticLayout.Builder.obtain(error, 0, error.length(), new TextPaint(warn), width)
                            .setMaxLines(5).setEllipsize(TextUtils.TruncateAt.END).build();
                }
                c.save();
                c.translate(LIST_X * k + 30 * k, y - errorLayout.getLineBaseline(0));
                errorLayout.draw(c);
                c.restore();
                y += errorLayout.getLineBaseline(errorLayout.getLineCount() - 1) - errorLayout.getLineBaseline(0) + 46 * k;
            }
            y = drawComparison(c, y);
            y = drawRecs(c, y + 26 * k);
            drawNotices(c, y + 40 * k);
        }

        /** Home screen picks: status, and the credit TMDB asks for. */
        private float drawRecs(Canvas c, float y) {
            float x = LIST_X * k + 30 * k;
            c.drawText(Ui.tr("RECOMENDAÇÕES", "RECOMMENDATIONS"), x, y, header);
            String line;
            String error = recs.optString("error");
            int count = recs.optInt("count");
            if (!hasKey) line = Ui.tr("Sem chave do TMDB: adicione em Mais Ajustes → Chave do TMDB.", "No TMDB key: add one in More Settings → TMDB key.");
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

        /** List row: no background; focused, white with a shadow (the Apple TV focus). Returns how focused it is. */
        private float drawRowBackground(Canvas c, int i) {
            float f = Ui.clamp(pop[i].value);
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
            for (int i = 0; notices != null && i < notices.length() && shown < 2; i++) {
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

        boolean onKey(KeyEvent e) {
            boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (down && focus > 0) focus--;
                    else if (down && scrolling() && pickScroll > 0) pickScroll--;
                    invalidate();
                    spoken.focusMoved();
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (down && focus < rowCount() - 1) focus++;
                    else if (down && scrolling() && pickScroll + rowCount() < listSize()) pickScroll++;
                    invalidate();
                    spoken.focusMoved();
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (down && screen == SHELF_EDIT) {
                        moveShelfApp(e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                        invalidate();
                    } else if (down && (screen == HOME_SETTINGS || screen == MORE)) {
                        adjust(e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                        invalidate();
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
                    if (screen == MAIN) return false;   // leaves the settings
                    if (down) {
                        if (e.getRepeatCount() == 0) backDown = true;
                    } else {
                        if (backDown && !e.isCanceled()) {
                            if (screen == PICKER) {
                                focus = pickReturnRow;
                                screen = pickReturnScreen;
                            } else if (screen == WATCH_APPS) {
                                focus = MORE_WATCH;
                                screen = MORE;
                            } else {
                                focus = screen == HOME_SETTINGS ? 2 : screen == MORE ? 3 : screen == SHELF_EDIT ? 4 : screen == REMOTE_KEYS ? 5 : 0;
                                screen = MAIN;
                            }
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

        // MARK: - Home Settings and Remote Buttons

        private static final String[] UNITS = {"auto", "C", "F"}, THEMES = {"auto", "light", "dark"};

        /** "auto", then English and every language with a catalogue (assets/i18n). */
        private static String[] languageChoices() {
            String[] all = Ui.languages();
            String[] v = new String[all.length + 1];
            v[0] = "auto";
            System.arraycopy(all, 0, v, 1, all.length);
            return v;
        }
        /** Home Settings: language, temperature, theme, weather, quotes, then one row per coin. */
        private static final int COIN_ROWS = 5;

        private boolean dollar() {
            return "BRL".equalsIgnoreCase(Config.CURRENCY.trim());
        }

        private String[] settingLabels() {
            List<String> l = new ArrayList<>();
            l.add(Ui.tr("Idioma", "Language"));
            l.add(Ui.tr("Temperatura", "Temperature"));
            l.add(Ui.tr("Tema", "Theme"));
            l.add(Ui.tr("Widget do clima", "Weather widget"));
            l.add(Ui.tr("Widget de cotações", "Quotes widget"));
            l.add("Bitcoin");
            l.add("Ethereum");
            l.add("Solana");
            if (dollar()) {
                l.add(Ui.tr("Dólar", "Dollar"));
                l.add("Euro");
            }
            return l.toArray(new String[0]);
        }

        private String[] settingValues() {
            String[] labels = settingLabels();
            String[] v = new String[labels.length];
            String lang = Prefs.language();
            v[0] = lang.equals("auto") ? Ui.tr("Automático", "Automatic") : Ui.languageName(lang);
            String unit = Prefs.temperature();
            v[1] = unit.equals("C") ? "°C" : unit.equals("F") ? "°F" : Ui.tr("Automática", "Automatic");
            String theme = Prefs.theme();
            v[2] = theme.equals("light") ? Ui.tr("Claro", "Light") : theme.equals("dark") ? Ui.tr("Escuro", "Dark") : Ui.tr("Automático", "Automatic");
            v[3] = onOff(Prefs.weather());
            v[4] = onOff(Prefs.quotes());
            // No quotes widget, nowhere to show prices: the coins read as off (and stay locked) until it is back on.
            for (int i = COIN_ROWS; i < v.length; i++) v[i] = onOff(Prefs.quotes() && Prefs.coin(i - COIN_ROWS));
            return v;
        }

        /** The rows of the list screen showing now (Home Settings, More Settings or Remote Buttons). */
        private String[] listLabels() {
            return screen == REMOTE_KEYS ? keyLabels() : screen == MORE ? moreLabels() : settingLabels();
        }

        private String[] listValues() {
            return screen == REMOTE_KEYS ? keyValues() : screen == MORE ? moreValues() : settingValues();
        }

        /** More Settings: the picks (where they open, which list, how many), prices, the weather's city, the shelf, the top. */
        private static final int MORE_TOP_COUNT = 2, MORE_TMDB_KEY = 9, MORE_WATCH = 10, MORE_SOUNDS = 11;

        /** Whether there is a key and where it comes from; never the key itself. */
        private String tmdbKeyState() {
            if (Prefs.typedTmdbKey()) return Ui.tr("Configurada na TV", "Set on the TV");
            return Prefs.tmdbKey().isEmpty() ? Ui.tr("Nenhuma: OK para inserir", "None: OK to add one")
                    : Ui.tr("Do config.properties", "From config.properties");
        }

        private String[] moreLabels() {
            return new String[] {Ui.tr("Abrir recomendações em", "Open picks in"),
                    Ui.tr("Recomendações", "Picks"), Ui.tr("Títulos no Top", "Titles in the top"),
                    Ui.tr("Atualizar cotações", "Refresh prices"), Ui.tr("Cidade do clima", "Weather city"),
                    Ui.tr("Capa dos apps na prateleira", "Shelf app art"), Ui.tr("Topo do Início", "Top of Home"),
                    Ui.tr("Tela de descanso", "Screensaver"), Ui.tr("Modo desempenho", "Performance mode"),
                    Ui.tr("Chave do TMDB", "TMDB key"), Ui.tr("Continuar assistindo", "Continue Watching", "Seguir viendo"),
                    Ui.tr("Sons do Início", "Home screen sounds", "Sonidos del inicio")};
        }

        private String[] moreValues() {
            String[] every = {Ui.tr("Tempo real", "Live"), "1 min", "5 min"};
            return new String[] {targetNames().get(targetIndex()),
                    Prefs.topRecs() ? Ui.tr("Top do dia", "Today's top") : Ui.tr("Original (5 por hora)", "Original (5 an hour)"),
                    String.valueOf(Prefs.topCount()),
                    every[Prefs.pricesEveryIndex()],
                    cityStatus.isEmpty() ? Prefs.city() : cityStatus,
                    Prefs.originalArt() ? Ui.tr("Original do app", "The app's own") : Ui.tr("Logo", "Logo"),
                    Prefs.topClock() ? Ui.tr("Relógio e clima", "Clock and weather") : Ui.tr("Cotações", "Prices"),
                    Prefs.ownScreensaver() ? "Free My TV" : Ui.tr("Da TV (fotos do Google)", "The TV's own (Google photos)"),
                    Prefs.performance() ? Ui.tr("Ligado (sem vidro)", "On (no glass)") : Ui.tr("Desligado", "Off"),
                    tmdbKeyState(), watchAppsState(), onOff(Prefs.sounds())};
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

        /** OK on a Home Settings or More Settings row: the list of its choices. False: nothing to choose now. */
        private boolean openChoices(int row) {
            String title = listLabels()[row];
            List<String> l = new ArrayList<>();
            if (screen == HOME_SETTINGS) {
                switch (row) {
                    case 0: {
                        String[] choices = languageChoices();
                        for (String code : choices) l.add(code.equals("auto") ? Ui.tr("Automático", "Automatic") : Ui.languageName(code));
                        openPicker(title, l, java.util.Arrays.asList(choices).indexOf(Prefs.language()), row,
                                at -> Prefs.setLanguage(choices[at]));
                        return true;
                    }
                    case 1:
                        java.util.Collections.addAll(l, Ui.tr("Automática", "Automatic"), "°C", "°F");
                        openPicker(title, l, java.util.Arrays.asList(UNITS).indexOf(Prefs.temperature()), row,
                                at -> Prefs.setTemperature(UNITS[at]));
                        return true;
                    case 2:
                        java.util.Collections.addAll(l, Ui.tr("Automático", "Automatic"), Ui.tr("Claro", "Light"), Ui.tr("Escuro", "Dark"));
                        openPicker(title, l, java.util.Arrays.asList(THEMES).indexOf(Prefs.theme()), row,
                                at -> Prefs.setTheme(THEMES[at]));
                        return true;
                    case 3: return openOnOff(title, row, Prefs.weather(), Prefs::setWeather);
                    case 4: return openOnOff(title, row, Prefs.quotes(), Prefs::setQuotes);
                    default: {
                        if (!Prefs.quotes()) return false;
                        int coin = row - COIN_ROWS;
                        return openOnOff(title, row, Prefs.coin(coin), on -> Prefs.setCoin(coin, on));
                    }
                }
            }
            switch (row) {
                case 0:
                    openPicker(title, targetNames(), targetIndex(), row, at -> Prefs.setRecsTarget(targetKeys()[at]));
                    return true;
                case 1:
                    java.util.Collections.addAll(l, Ui.tr("Original (5 por hora)", "Original (5 an hour)"), Ui.tr("Top do dia", "Today's top"));
                    openPicker(title, l, Prefs.topRecs() ? 1 : 0, row, at -> Prefs.setTopRecs(at == 1));
                    return true;
                case MORE_TOP_COUNT:
                    if (!Prefs.topRecs()) return false;   // only the top has a size
                    for (int n = Prefs.TOP_MIN; n <= Prefs.TOP_MAX; n++) l.add(String.valueOf(n));
                    openPicker(title, l, Prefs.topCount() - Prefs.TOP_MIN, row, at -> Prefs.setTopCount(Prefs.TOP_MIN + at));
                    return true;
                case 3:
                    java.util.Collections.addAll(l, Ui.tr("Tempo real", "Live"), "1 min", "5 min");
                    openPicker(title, l, Prefs.pricesEveryIndex(), row, Prefs::setPricesEveryIndex);
                    return true;
                case 4:
                    askCity();
                    return false;   // the search opens its own list
                case 5:
                    java.util.Collections.addAll(l, Ui.tr("Original do app", "The app's own"), Ui.tr("Logo", "Logo"));
                    openPicker(title, l, Prefs.originalArt() ? 0 : 1, row, at -> Prefs.setOriginalArt(at == 0));
                    return true;
                case 6:
                    java.util.Collections.addAll(l, Ui.tr("Relógio e clima", "Clock and weather"), Ui.tr("Cotações", "Prices"));
                    openPicker(title, l, Prefs.topClock() ? 0 : 1, row, at -> Prefs.setTopClock(at == 0));
                    return true;
                case 7:
                    java.util.Collections.addAll(l, Ui.tr("Da TV (fotos do Google)", "The TV's own (Google photos)"), "Free My TV");
                    openPicker(title, l, Prefs.ownScreensaver() ? 1 : 0, row, at -> {
                        boolean own = at == 1;
                        if (own == Prefs.ownScreensaver()) return;
                        Prefs.setOwnScreensaver(own);
                        HomeMode.requestScreensaver(getContext(), own);   // the TV's setting changes in the main process
                    });
                    return true;
                case MORE_SOUNDS: return openOnOff(title, row, Prefs.sounds(), Prefs::setSounds);
                default:
                    java.util.Collections.addAll(l, Ui.tr("Desligado", "Off"), Ui.tr("Ligado (sem vidro)", "On (no glass)"));
                    openPicker(title, l, Prefs.performance() ? 1 : 0, row, at -> Prefs.setPerformance(at == 1));
                    return true;
            }
        }

        private boolean openOnOff(String title, int row, boolean on, java.util.function.Consumer<Boolean> set) {
            List<String> l = new ArrayList<>();
            java.util.Collections.addAll(l, Ui.tr("Ligado", "On"), Ui.tr("Desligado", "Off"));
            openPicker(title, l, on ? 0 : 1, row, at -> set.accept(at == 0));
            return true;
        }

        /** Changes the focused More Settings row one step (the city goes through the list, then back to the default). */
        private void adjustMore(int dir) {
            switch (focus) {
                case 0: {
                    String[] keys = targetKeys();
                    Prefs.setRecsTarget(keys[(targetIndex() + dir + keys.length) % keys.length]);
                    break;
                }
                case 1: Prefs.setTopRecs(!Prefs.topRecs()); break;
                case MORE_TOP_COUNT: {
                    if (!Prefs.topRecs()) return;   // only the top has a size
                    int n = Prefs.TOP_MAX - Prefs.TOP_MIN + 1;
                    Prefs.setTopCount(Prefs.TOP_MIN + (Prefs.topCount() - Prefs.TOP_MIN + dir + n) % n);
                    break;
                }
                case 3: {
                    int n = Prefs.PRICES_EVERY.length;
                    Prefs.setPricesEveryIndex((Prefs.pricesEveryIndex() + dir + n) % n);
                    break;
                }
                case 4: return;   // the city is searched by name (OK)
                case 5: Prefs.setOriginalArt(!Prefs.originalArt()); break;
                case 6: Prefs.setTopClock(!Prefs.topClock()); break;
                case 7: {
                    boolean own = !Prefs.ownScreensaver();
                    Prefs.setOwnScreensaver(own);
                    HomeMode.requestScreensaver(getContext(), own);   // the TV's setting changes in the main process
                    break;
                }
                case 8: Prefs.setPerformance(!Prefs.performance()); break;
                case MORE_SOUNDS: Prefs.setSounds(!Prefs.sounds()); break;
                default: return;   // the TMDB key: OK opens the text field (activate)
            }
            HomeData.get(getContext()).settingsChanged();
        }

        private static String onOff(boolean on) {
            return on ? Ui.tr("Ligado", "On") : Ui.tr("Desligado", "Off");
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

        private String[] keyValues() {
            String[] v = new String[Prefs.KEYS.length];
            for (int i = 0; i < v.length; i++) {
                int at = appPkgs.indexOf(Prefs.keyApp(Prefs.KEYS[i]));
                v[i] = at >= 0 ? appLabels.get(at) : Ui.tr("Não instalado", "Not installed");
            }
            return v;
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

        /** Changes the focused row's value one step (OK and → go forward, ← goes back). */
        private void adjust(int dir) {
            if (screen == MORE) {
                adjustMore(dir);
                return;
            }
            if (screen == REMOTE_KEYS) {
                int n = appPkgs.size();
                int at = appPkgs.indexOf(Prefs.keyApp(Prefs.KEYS[focus]));
                int next = at < 0 ? (dir > 0 ? 0 : n - 1) : (at + dir + n) % n;
                Prefs.setKeyApp(Prefs.KEYS[focus], appPkgs.get(next));
                return;
            }
            switch (focus) {
                case 0: {
                    String[] choices = languageChoices();
                    int at = java.util.Arrays.asList(choices).indexOf(Prefs.language());
                    Prefs.setLanguage(choices[(at + dir + choices.length) % choices.length]);
                    break;
                }
                case 1: {
                    int at = java.util.Arrays.asList(UNITS).indexOf(Prefs.temperature());
                    Prefs.setTemperature(UNITS[(at + dir + UNITS.length) % UNITS.length]);
                    break;
                }
                case 2: {
                    int at = java.util.Arrays.asList(THEMES).indexOf(Prefs.theme());
                    Prefs.setTheme(THEMES[(at + dir + THEMES.length) % THEMES.length]);
                    break;
                }
                case 3: Prefs.setWeather(!Prefs.weather()); break;
                case 4: Prefs.setQuotes(!Prefs.quotes()); break;
                default:
                    if (!Prefs.quotes()) return;
                    Prefs.setCoin(focus - COIN_ROWS, !Prefs.coin(focus - COIN_ROWS));
                    break;
            }
            HomeData.get(getContext()).settingsChanged();
        }

        private String cityStatus = "";

        /** More Settings > Weather city: type the name (the Google TV app on a phone types too); the matches follow. */
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
                    if (!result.isEmpty() && screen == MORE) {
                        List<String> labels = new ArrayList<>();
                        for (String[] city : result) labels.add(city[0]);
                        // {label, name, latitude, longitude}
                        openPicker(Ui.tr("Cidade do clima", "Weather city"), labels, -1, 4, at -> {
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

        private boolean scrolling() {
            return screen == PICKER || screen == SHELF_EDIT || screen == WATCH_APPS;
        }

        private int listSize() {
            return screen == SHELF_EDIT ? shelfRows.size() : screen == WATCH_APPS ? watchRows.size() : pickLabels.size();
        }

        // MARK: - Shelf editor

        private void openShelfEditor() {
            shelfOrder.clear();
            shelfOrder.addAll(HomeApps.shelfPackages(getContext()));
            rebuildShelfRows(HomeApps.shelfCandidates(getContext()));
            pickScroll = 0;
            focus = 0;
            screen = SHELF_EDIT;
        }

        /** Selected apps first, in shelf order, then the rest in the order they came. */
        private void rebuildShelfRows(List<String[]> candidates) {
            shelfRows.clear();
            for (String pkg : shelfOrder) {
                for (String[] cand : candidates) if (cand[0].equals(pkg)) { shelfRows.add(cand); break; }
            }
            for (String[] cand : candidates) if (!shelfOrder.contains(cand[0])) shelfRows.add(cand);
        }

        /** Same rows after a change: keeps the focus on the app that was focused. */
        private void refreshShelfRows(String focusedPkg) {
            List<String[]> all = new ArrayList<>(shelfRows);
            rebuildShelfRows(all);
            for (int i = 0; i < shelfRows.size(); i++) {
                if (!shelfRows.get(i)[0].equals(focusedPkg)) continue;
                int visible = rowCount();
                if (i < pickScroll) pickScroll = i;
                else if (i >= pickScroll + visible) pickScroll = i - visible + 1;
                focus = i - pickScroll;
                break;
            }
            Prefs.setShelf(shelfOrder);
        }

        /** OK: puts the app on the shelf (at the end) or takes it off. */
        private void toggleShelfApp() {
            String pkg = shelfRows.get(pickScroll + focus)[0];
            if (shelfOrder.contains(pkg)) shelfOrder.remove(pkg);
            else if (shelfOrder.size() < HomeApps.MAX_SHELF) shelfOrder.add(pkg);
            else return;
            refreshShelfRows(pkg);
        }

        /** ← → : moves a shelf app one place earlier or later. */
        private void moveShelfApp(int dir) {
            String pkg = shelfRows.get(pickScroll + focus)[0];
            int at = shelfOrder.indexOf(pkg), to = at + dir;
            if (at < 0 || to < 0 || to >= shelfOrder.size()) return;
            shelfOrder.remove(at);
            shelfOrder.add(to, pkg);
            refreshShelfRows(pkg);
        }

        private void drawShelfEditor(Canvas c) {
            c.drawText(Ui.tr("Prateleira", "Shelf"), LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading);
            int rows = rowCount();
            for (int i = 0; i < rows; i++) {
                String[] app = shelfRows.get(pickScroll + i);
                int place = shelfOrder.indexOf(app[0]);
                float y = (LIST_TOP + 20 + i * (LIST_ROW_H + LIST_ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + LIST_ROW_H * k);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(blend(place >= 0 ? fg(0xF2FFFFFF) : fg(0x80FFFFFF), place >= 0 ? INK : 0x801C1C1E, f));
                rowValue.setColor(blend(fg(0xB3FFFFFF), 0xB31C1C1E, f));
                float base = rowRect.centerY() + Ui.halfCap(rowLabel);
                fit.set(rowLabel);
                c.drawText(TextUtils.ellipsize(app[1], fit, 520 * k, TextUtils.TruncateAt.END).toString(), rowRect.left + 30 * k, base, rowLabel);
                c.drawText(place >= 0 ? "#" + (place + 1) : Ui.tr("Fora", "Off"), rowRect.right - 30 * k, base, rowValue);
            }
            float hy = (LIST_TOP + 20 + rows * (LIST_ROW_H + LIST_ROW_GAP) + 36) * k;
            c.drawText(Ui.tr("OK põe ou tira da prateleira (até " + HomeApps.MAX_SHELF + "). ← → mudam a ordem.",
                    "OK adds or removes (up to " + HomeApps.MAX_SHELF + "). ← → change the order.",
                    "OK pone o quita del estante (hasta " + HomeApps.MAX_SHELF + "). ← → cambian el orden."), LIST_X * k + 30 * k, hy, hint);
        }

        // MARK: - Continue watching: which apps may show

        /** The apps with something to continue now (each app writes its own list), on or off for the home screen. */
        private void openWatchApps() {
            watchRows.clear();
            watchRows.addAll(WatchNext.apps(getContext()));
            pickScroll = 0;
            focus = 0;
            screen = WATCH_APPS;
        }

        private void toggleWatchApp() {
            if (watchRows.isEmpty()) return;
            String pkg = watchRows.get(pickScroll + focus)[0];
            Prefs.setWatchNextOff(pkg, !Prefs.watchNextOff(pkg));
        }

        private void drawWatchApps(Canvas c) {
            c.drawText(Ui.tr("Continuar assistindo", "Continue Watching", "Seguir viendo"), LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading);
            int rows = rowCount();
            for (int i = 0; i < rows; i++) {
                String[] app = watchRows.get(pickScroll + i);
                boolean on = !Prefs.watchNextOff(app[0]);
                float y = (LIST_TOP + 20 + i * (LIST_ROW_H + LIST_ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + LIST_ROW_H * k);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(blend(on ? fg(0xF2FFFFFF) : fg(0x80FFFFFF), on ? INK : 0x801C1C1E, f));
                rowValue.setColor(blend(fg(0xB3FFFFFF), 0xB31C1C1E, f));
                float base = rowRect.centerY() + Ui.halfCap(rowLabel);
                fit.set(rowLabel);
                c.drawText(TextUtils.ellipsize(app[1], fit, 520 * k, TextUtils.TruncateAt.END).toString(), rowRect.left + 30 * k, base, rowLabel);
                c.drawText(onOff(on), rowRect.right - 30 * k, base, rowValue);
            }
            float hy = (LIST_TOP + 20 + rows * (LIST_ROW_H + LIST_ROW_GAP) + 36) * k;
            String text = !WatchNext.allowed(getContext())
                    ? Ui.tr("Sem acesso à lista dos apps: troque o Início para Free My TV de novo.",
                            "No access to the apps' lists: switch the home screen to Free My TV again.",
                            "Sin acceso a la lista de las apps: cambia el inicio a Free My TV otra vez.")
                    : watchRows.isEmpty()
                    ? Ui.tr("Nenhum app de vídeo instalado.", "No video apps installed.", "Ninguna app de video instalada.")
                    : Ui.tr("OK liga ou desliga. Os apps de vídeo da TV e os que têm algo para continuar.",
                            "OK turns it on or off. The TV's video apps and any with something to continue.",
                            "OK activa o desactiva. Las apps de video del TV y las que tienen algo para seguir.");
            c.drawText(text, LIST_X * k + 30 * k, hy, hint);
        }

        /** The app a remote button opens (row `focus` of Remote Buttons). */
        private void openKeyPicker(int row) {
            int key = Prefs.KEYS[row];
            openPicker(keyLabels()[row], appLabels, appPkgs.indexOf(Prefs.keyApp(key)), row,
                    at -> Prefs.setKeyApp(key, appPkgs.get(at)));
        }

        /**
         * The list of a setting's choices (the dropdown): opens on the one in use; OK picks and goes back to the row,
         * Back leaves it as it was.
         */
        private void openPicker(String title, List<String> labels, int current, int returnRow, java.util.function.IntConsumer apply) {
            pickTitle = title;
            pickLabels.clear();
            pickLabels.addAll(labels);
            pickCurrent = current;
            pickApply = apply;
            pickReturnScreen = screen;
            pickReturnRow = returnRow;
            int visible = Math.min(PICK_VISIBLE, pickLabels.size());
            int at = Math.max(0, pickCurrent);
            pickScroll = Math.max(0, Math.min(at - 3, pickLabels.size() - visible));
            focus = at - pickScroll;
            screen = PICKER;
        }

        /** A list of choices: nine rows at a time, the one in use checked. */
        private void drawPicker(Canvas c) {
            String title = pickTitle;
            c.drawText(title, LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading);
            int rows = rowCount();
            for (int i = 0; i < rows; i++) {
                int at = pickScroll + i;
                float y = (LIST_TOP + 20 + i * (LIST_ROW_H + LIST_ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + LIST_ROW_H * k);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(blend(fg(0xF2FFFFFF), INK, f));
                c.drawText(pickLabels.get(at), rowRect.left + 30 * k, rowRect.centerY() + Ui.halfCap(rowLabel), rowLabel);
                if (at == pickCurrent) {
                    float cw = Symbols.width(getContext(), "check", 26 * k);
                    Symbols.draw(getContext(), c, "check", rowRect.right - 30 * k - cw / 2, rowRect.centerY(), 26 * k, blend(fg(0xFFFFFFFF), INK, f));
                }
            }
            float hy = (LIST_TOP + 20 + rows * (LIST_ROW_H + LIST_ROW_GAP) + 36) * k;
            c.drawText((pickScroll + focus + 1) + " / " + pickLabels.size() + "  ·  " + Ui.tr("OK escolhe, Voltar cancela.", "OK picks, Back cancels."),
                    LIST_X * k + 30 * k, hy, hint);
        }

        private void drawList(Canvas c) {
            boolean keys = screen == REMOTE_KEYS;
            String[] labels = listLabels();
            String[] values = listValues();
            c.drawText(keys ? Ui.tr("Botões do Controle", "Remote Buttons") : screen == MORE ? Ui.tr("Mais Ajustes", "More Settings")
                    : Ui.tr("Ajustes do Início", "Home Settings"), LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading);
            for (int i = 0; i < labels.length; i++) {
                float y = (LIST_TOP + 20 + i * (LIST_ROW_H + LIST_ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + LIST_ROW_H * k);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(blend(fg(0xF2FFFFFF), INK, f));
                boolean locked = screen == HOME_SETTINGS && i >= COIN_ROWS && !Prefs.quotes()
                        || screen == MORE && i == MORE_TOP_COUNT && !Prefs.topRecs();
                rowValue.setColor(blend(locked ? fg(0x59FFFFFF) : fg(0xB3FFFFFF), locked ? 0x591C1C1E : 0xB31C1C1E, f));
                if (locked) rowLabel.setColor(blend(fg(0x73FFFFFF), 0x731C1C1E, f));
                float base = rowRect.centerY() + Ui.halfCap(rowLabel);
                c.drawText(labels[i], rowRect.left + 30 * k, base, rowLabel);
                c.drawText(values[i], rowRect.right - 30 * k, base, rowValue);
            }
            float hy = (LIST_TOP + 20 + labels.length * (LIST_ROW_H + LIST_ROW_GAP) + 36) * k;
            c.drawText(keys ? Ui.tr("OK abre a lista de apps.", "OK opens the list of apps.")
                    : Ui.tr("OK abre as opções; ← e → mudam direto.", "OK opens the choices; ← and → change it right away."),
                    LIST_X * k + 30 * k, hy, hint);
            if (keys) {
                c.drawText(RemoteKeys.supported() ? Ui.tr("Disney e Globoplay: a própria TV controla esses botões.",
                        "Disney and Globoplay: the TV itself controls those buttons.")
                        : Ui.tr("Esses são os botões do controle da TCL: nesta TV, os botões funcionam como sempre.",
                        "These are the TCL remote's buttons: on this TV, the buttons work as they always did."), LIST_X * k + 30 * k, hy + 38 * k, hint);
            }
        }

        private void activate() {
            Context c = getContext();
            if (screen == HOME_CHOICE) {
                boolean wantFree = focus == 0;
                if (wantFree != freeActive() && !busy()) {
                    HomeMode.request(c, wantFree ? HomeMode.FREE : HomeMode.ORIGINAL);
                    pendingAt = SystemClock.uptimeMillis();
                }
                screen = MAIN;
                focus = 0;
            } else if (screen == PICKER) {
                pickApply.accept(pickScroll + focus);
                retheme();   // a Theme picked here: the background and text follow
                HomeData.get(c).settingsChanged();
                focus = pickReturnRow;
                screen = pickReturnScreen;
            } else if (screen == SHELF_EDIT) {
                toggleShelfApp();
                invalidate();
                return;
            } else if (screen == REMOTE_KEYS) {
                openKeyPicker(focus);
            } else if (screen == WATCH_APPS) {
                toggleWatchApp();
                invalidate();
                spoken.focusMoved();
                return;
            } else if (screen == MORE && focus == MORE_WATCH) {
                openWatchApps();
            } else if (screen == MORE && focus == MORE_TMDB_KEY) {
                activity.askTmdbKey();
                return;
            } else if (screen == HOME_SETTINGS || screen == MORE) {
                if (!openChoices(focus)) return;   // a row that can't change now (a coin with the widget off)
            } else if (focus == 0) {
                if (busy()) return;
                screen = HOME_CHOICE;
                focus = freeActive() ? 0 : 1;
            } else if (focus == 1) {
                pause();
                activity.showPreview();
            } else if (focus == 2) {
                screen = HOME_SETTINGS;
                focus = 0;
            } else if (focus == 3) {
                screen = MORE;
                focus = 0;
            } else if (focus == 4) {
                openShelfEditor();
            } else if (focus == 5) {
                HomeMode.ensureRemoteService(c, 0);   // the buttons work from inside other apps through this service
                loadApps();
                screen = REMOTE_KEYS;
                focus = 0;
            } else {
                activity.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
            for (Spring s : pop) s.snap(0);
            invalidate();
            spoken.focusMoved();
        }
    }
}
