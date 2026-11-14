package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * The followed team for the home screen's card and the screensaver (FootballSource.overview: one ~20 KB request).
 * Only fetched while the home screen or the screensaver is on (they ask) and only with the football widget on:
 * every 30 min, every 2 min near kickoff, every minute during the match. Cached, so the card opens with it.
 * It also says when the team's match has a goal, and when it's about to start (Prefs.footballAlerts).
 */
final class FootballData {
    interface Listener {
        void onFootball();

        /** A notice for the screen: a goal, or the match about to start. */
        void onFootballNotice(String text);
    }

    private static final long EVERY = 30 * 60_000L, NEAR_KICKOFF = 2 * 60_000L, LIVE = 60_000L;
    /** A match that just ended stays on the card this long (the team's next match only comes after). */
    private static final long FINAL_FOR = 6 * 3600_000L;
    private static final long REMIND_BEFORE = 30 * 60_000L;

    private static FootballData instance;

    static synchronized FootballData get(Context c) {
        if (instance == null) instance = new FootballData(c.getApplicationContext());
        return instance;
    }

    volatile Football.Overview overview;
    /** The team's last match, kept from when it was seen live: shown for a while after the final whistle. */
    volatile Football.Match lastFinal;
    volatile long at;

    private final SharedPreferences cache;
    private final ExecutorService io = Ui.background("football");
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private volatile boolean fetching;
    private volatile long failedAt;
    private String remindedFor = "";

    private FootballData(Context c) {
        cache = c.getSharedPreferences("football", Context.MODE_PRIVATE);
        try {
            String o = cache.getString("overview", null);
            if (o != null) overview = overviewFrom(new JSONObject(o));
            String f = cache.getString("final", null);
            if (f != null) lastFinal = matchFrom(new JSONObject(f));
            at = cache.getLong("at", 0);
            remindedFor = cache.getString("remindedFor", "");
        } catch (Exception ignored) {
        }
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    /** What the card shows: the match being played, a match just over, or the next one. */
    Football.Match current() {
        Football.Overview o = overview;
        if (o == null || !o.team.id.equals(Prefs.footballTeam())) return null;
        Football.Match next = o.match, last = lastFinal;
        if (next != null && next.live()) return next;
        long now = System.currentTimeMillis();
        if (last != null && last.has(o.team.id) && now - last.at < FINAL_FOR + 2 * 3600_000L) return last;
        return next;
    }

    /** The match being played, or null (the screensaver's corner). */
    Football.Match live() {
        Football.Match m = current();
        return m != null && m.live() ? m : null;
    }

    /** Fetches when stale (the screen asks when it appears and on its tick). Nothing at all with the widget off. */
    void refreshIfStale() {
        if (!Prefs.football() || fetching) return;
        long now = System.currentTimeMillis();
        if (failedAt != 0 && now >= failedAt && now - failedAt < 2 * 60_000L) return;
        if (now - at < every(now) && now >= at && sameTeam()) return;
        fetching = true;
        String team = Prefs.footballTeam();
        io.execute(() -> {
            try {
                Football.Overview fresh = FootballSource.get().overview(team);
                main.post(() -> arrived(fresh));
            } catch (Exception e) {
                failedAt = System.currentTimeMillis();
                AppLog.w("football: " + e.getMessage());
            } finally {
                fetching = false;
            }
        });
    }

    private boolean sameTeam() {
        Football.Overview o = overview;
        return o != null && o.team.id.equals(Prefs.footballTeam());
    }

    /** How often to ask now: a minute during the match, two near kickoff, half an hour otherwise. */
    private long every(long now) {
        Football.Overview o = overview;
        Football.Match m = o == null ? null : o.match;
        if (m == null) return EVERY;
        if (m.live()) return LIVE;
        long toKickoff = m.at - now;
        // Near kickoff, and past it until the source says it started (or it's clearly over).
        if (!m.over() && toKickoff < 15 * 60_000L && toKickoff > -3 * 3600_000L) return NEAR_KICKOFF;
        return EVERY;
    }

    /** New data (UI thread): goals in the team's match become notices, a match just ended is kept. */
    private void arrived(Football.Overview fresh) {
        Football.Overview old = overview;
        Football.Match before = old == null ? null : old.match, now = fresh.match;
        if (before != null && before.live() && old.team.id.equals(fresh.team.id)) {
            if (now != null && now.id.equals(before.id)) {
                goals(before, now);
            } else {
                // The source moved on to the next match: the one that was live is over, with its last score.
                before.state = Football.POST;
                lastFinal = before;
            }
        }
        if (now != null && now.over()) lastFinal = now;
        overview = fresh;
        at = System.currentTimeMillis();
        failedAt = 0;
        save();
        for (Listener l : new ArrayList<>(listeners)) l.onFootball();
    }

    private void goals(Football.Match before, Football.Match now) {
        if (!Prefs.footballAlerts()) return;
        String score = scoreLine(now);
        if (now.homeScore > Math.max(0, before.homeScore) || (before.homeScore < 0 && now.homeScore > 0)) {
            notice(Ui.trf(Ui.tr("Gol do {team}! {score}", "{team} scores! {score}", "¡Gol de {team}! {score}"), "team", now.home.label(), "score", score));
        } else if (now.awayScore > Math.max(0, before.awayScore) || (before.awayScore < 0 && now.awayScore > 0)) {
            notice(Ui.trf(Ui.tr("Gol do {team}! {score}", "{team} scores! {score}", "¡Gol de {team}! {score}"), "team", now.away.label(), "score", score));
        }
    }

    /** The match about to start (within 30 min): one notice per match. Read from the cache: no request. */
    void checkReminder() {
        if (!Prefs.football() || !Prefs.footballAlerts()) return;
        Football.Overview o = overview;
        Football.Match m = o == null ? null : o.match;
        if (m == null || !Football.PRE.equals(m.state) || m.id.equals(remindedFor)) return;
        long left = m.at - System.currentTimeMillis();
        if (left <= 0 || left > REMIND_BEFORE) return;
        remindedFor = m.id;
        cache.edit().putString("remindedFor", remindedFor).apply();
        long minutes = Math.max(1, Math.round(left / 60_000.0));
        String text = Ui.trf(Ui.tr("{team} joga em {n} min", "{team} plays in {n} min", "{team} juega en {n} min"),
                "team", o.team.label(), "n", minutes);
        if (!m.broadcast.isEmpty()) text += "  ·  " + m.broadcast;
        notice(text);
    }

    private void notice(String text) {
        for (Listener l : new ArrayList<>(listeners)) l.onFootballNotice(text);
    }

    /** "Flamengo 2 × 1 Palmeiras". */
    static String scoreLine(Football.Match m) {
        return m.home.label() + " " + Math.max(0, m.homeScore) + " × " + Math.max(0, m.awayScore) + " " + m.away.label();
    }

    // MARK: - Cache (the source's objects, flattened)

    private void save() {
        try {
            SharedPreferences.Editor e = cache.edit().putLong("at", at);
            if (overview != null) e.putString("overview", toJson(overview).toString());
            if (lastFinal != null) e.putString("final", toJson(lastFinal).toString());
            e.apply();
        } catch (Exception ignored) {
        }
    }

    private static JSONObject toJson(Football.Overview o) throws Exception {
        JSONObject j = toJson(o.team).put("standing", o.standing);
        if (o.match != null) j.put("match", toJson(o.match));
        return j;
    }

    private static Football.Overview overviewFrom(JSONObject j) {
        Football.Overview o = new Football.Overview();
        teamFrom(o.team, j);
        o.standing = j.optString("standing");
        JSONObject m = j.optJSONObject("match");
        if (m != null) o.match = matchFrom(m);
        return o;
    }

    private static JSONObject toJson(Football.Team t) throws Exception {
        return new JSONObject().put("id", t.id).put("name", t.name).put("short", t.shortName).put("abbr", t.abbreviation)
                .put("league", t.league).put("leagueName", t.leagueName);
    }

    private static void teamFrom(Football.Team t, JSONObject j) {
        t.id = j.optString("id");
        t.name = j.optString("name");
        t.shortName = j.optString("short");
        t.abbreviation = j.optString("abbr");
        t.league = j.optString("league");
        t.leagueName = j.optString("leagueName");
    }

    private static JSONObject toJson(Football.Match m) throws Exception {
        return new JSONObject().put("id", m.id).put("league", m.league).put("leagueName", m.leagueName).put("at", m.at)
                .put("state", m.state).put("clock", m.clock).put("home", toJson(m.home)).put("away", toJson(m.away))
                .put("homeScore", m.homeScore).put("awayScore", m.awayScore).put("broadcast", m.broadcast);
    }

    private static Football.Match matchFrom(JSONObject j) {
        Football.Match m = new Football.Match();
        m.id = j.optString("id");
        m.league = j.optString("league");
        m.leagueName = j.optString("leagueName");
        m.at = j.optLong("at");
        m.state = j.optString("state", Football.PRE);
        m.clock = j.optString("clock");
        JSONObject h = j.optJSONObject("home"), a = j.optJSONObject("away");
        if (h != null) teamFrom(m.home, h);
        if (a != null) teamFrom(m.away, a);
        m.homeScore = j.optInt("homeScore", -1);
        m.awayScore = j.optInt("awayScore", -1);
        m.broadcast = j.optString("broadcast");
        return m;
    }
}
