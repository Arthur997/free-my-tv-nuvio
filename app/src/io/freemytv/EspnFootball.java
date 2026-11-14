package io.freemytv;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * ESPN's public football API (site.api.espn.com): no key, every competition a Brazilian or European club plays
 * (Brasileirão A and B, Copa do Brasil, the state leagues, Libertadores, Sudamericana, the European leagues and cups).
 * It isn't an official API and may change: only this class knows its format (FootballSource).
 * Asked in Portuguese for Brazil when the app is in Portuguese (stage names, "1º no Campeonato Brasileiro").
 */
final class EspnFootball implements FootballSource {
    static final EspnFootball INSTANCE = new EspnFootball();
    private static final String SITE = "https://site.api.espn.com/apis/site/v2/sports/soccer/";

    private EspnFootball() {}

    private static String lang() {
        return Ui.language.equals("pt") ? "lang=pt&region=br" : Ui.es ? "lang=es&region=es" : "lang=en&region=us";
    }

    private static JSONObject json(String url) throws Exception {
        return new JSONObject(HomeData.get(url + (url.contains("?") ? "&" : "?") + lang(), null));
    }

    // MARK: - FootballSource

    @Override public Football.Overview overview(String teamId) throws Exception {
        JSONObject t = json(SITE + "all/teams/" + teamId).getJSONObject("team");
        Football.Overview o = new Football.Overview();
        team(o.team, t);
        Object league = t.opt("defaultLeague");
        if (league instanceof JSONObject) {
            o.team.league = ((JSONObject) league).optString("slug");
            o.team.leagueName = ((JSONObject) league).optString("name");
        }
        o.standing = t.optString("standingSummary");
        JSONArray next = t.optJSONArray("nextEvent");
        if (next != null && next.length() > 0) o.match = match(next.getJSONObject(0));
        return o;
    }

    @Override public List<Football.Match> schedule(String teamId) throws Exception {
        // Played and to come are two lists; the same match can be in both while it's being played.
        Map<String, Football.Match> byId = new LinkedHashMap<>();
        for (String query : new String[] {"", "?fixture=true"}) {
            JSONArray events = json(SITE + "all/teams/" + teamId + "/schedule" + query).optJSONArray("events");
            for (int i = 0; events != null && i < events.length(); i++) {
                Football.Match m = match(events.getJSONObject(i));
                byId.put(m.id, m);
            }
        }
        List<Football.Match> all = new ArrayList<>(byId.values());
        Collections.sort(all, (a, b) -> Long.compare(a.at, b.at));
        return all;
    }

    @Override public Football.Table table(String league) throws Exception {
        JSONObject o = json("https://site.api.espn.com/apis/v2/sports/soccer/" + league + "/standings");
        Football.Table table = new Football.Table();
        table.league = league;
        table.name = o.optString("name");
        JSONArray children = o.optJSONArray("children");
        if (children == null || children.length() == 0) {
            group(table, "", o.optJSONObject("standings"));
        } else {
            for (int i = 0; i < children.length(); i++) {
                JSONObject g = children.getJSONObject(i);
                // A league's only group is the season ("2026"): no group name then.
                group(table, children.length() > 1 ? g.optString("name") : "", g.optJSONObject("standings"));
            }
        }
        return table;
    }

    @Override public Football.Details details(String league, String matchId) throws Exception {
        JSONObject o = json(SITE + league + "/summary?event=" + matchId);
        Football.Details d = new Football.Details();
        JSONObject header = o.optJSONObject("header");
        JSONArray comps = header == null ? null : header.optJSONArray("competitions");
        if (comps != null && comps.length() > 0) {
            JSONObject event = new JSONObject().put("id", matchId).put("competitions", new JSONArray().put(comps.getJSONObject(0)));
            event.put("date", comps.getJSONObject(0).optString("date"));
            JSONObject l = header.optJSONObject("league");
            if (l != null) event.put("league", l);
            d.match = match(event);
            if (d.match.league.isEmpty()) d.match.league = league;
        }
        JSONArray events = o.optJSONArray("keyEvents");
        for (int i = 0; events != null && i < events.length(); i++) {
            Football.Event e = event(events.getJSONObject(i));
            if (e != null) d.events.add(e);
        }
        JSONArray rosters = o.optJSONArray("rosters");
        for (int i = 0; rosters != null && i < rosters.length(); i++) {
            JSONObject r = rosters.getJSONObject(i);
            boolean home = "home".equals(r.optString("homeAway")) || (d.match != null && d.match.home.id.equals(r.optJSONObject("team").optString("id")));
            List<String> lineup = home ? d.homeLineup : d.awayLineup;
            if (home) d.homeFormation = r.optString("formation");
            else d.awayFormation = r.optString("formation");
            JSONArray players = r.optJSONArray("roster");
            for (int j = 0; players != null && j < players.length(); j++) {
                JSONObject p = players.getJSONObject(j);
                if (!p.optBoolean("starter")) continue;
                String jersey = p.optString("jersey");
                String name = p.optJSONObject("athlete") == null ? "" : p.optJSONObject("athlete").optString("displayName");
                lineup.add(jersey.isEmpty() ? name : jersey + "  " + name);
            }
        }
        stats(d, o.optJSONObject("boxscore"));
        venue(d, o.optJSONObject("gameInfo"));
        meetings(d, o.optJSONArray("seasonseries"));
        JSONArray forms = o.optJSONArray("lastFiveGames");
        for (int i = 0; forms != null && d.match != null && i < forms.length(); i++) {
            JSONObject f = forms.optJSONObject(i);
            JSONObject team = f == null ? null : f.optJSONObject("team");
            if (team == null) continue;
            boolean home = d.match.home.id.equals(team.optString("id"));
            form(home ? d.homeForm : d.awayForm, home ? d.match.home : d.match.away, f.optJSONArray("events"));
        }
        return d;
    }

    @Override public Football.Odds odds(String league, String matchId) throws Exception {
        JSONObject o = new JSONObject(HomeData.get("https://sports.core.api.espn.com/v2/sports/soccer/leagues/" + league + "/events/"
                + matchId + "/competitions/" + matchId + "/odds", null));
        Football.Odds odds = new Football.Odds();
        JSONArray items = o.optJSONArray("items");
        double home = 0, draw = 0, away = 0;
        int n = 0, draws = 0;
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it == null) continue;
            double h = decimal(it.optJSONObject("homeTeamOdds")), a = decimal(it.optJSONObject("awayTeamOdds")), d = decimal(it.optJSONObject("drawOdds"));
            if (Double.isNaN(h) || Double.isNaN(a)) continue;
            home += h;
            away += a;
            n++;
            if (!Double.isNaN(d)) {
                draw += d;
                draws++;
            }
            JSONObject provider = it.optJSONObject("provider");
            if (provider != null) odds.bookmakers.add(provider.optString("name"));
        }
        if (n > 0) {
            odds.home = home / n;
            odds.away = away / n;
            if (draws > 0) odds.draw = draw / draws;
        }
        return odds;
    }

    /** A bookmaker's price as a decimal odd (ESPN gives American "moneyline": -450 is 1.22, +900 is 10.0). */
    private static double decimal(JSONObject side) {
        if (side == null || !side.has("moneyLine")) return Double.NaN;
        double ml = side.optDouble("moneyLine", Double.NaN);
        if (Double.isNaN(ml) || ml == 0) return Double.NaN;
        return ml > 0 ? 1 + ml / 100 : 1 + 100 / -ml;
    }

    private static void venue(Football.Details d, JSONObject info) {
        JSONObject v = info == null ? null : info.optJSONObject("venue");
        if (v == null) return;
        String name = v.optString("fullName");
        JSONObject address = v.optJSONObject("address");
        String city = address == null ? "" : address.optString("city");
        d.venue = city.isEmpty() || name.contains(city) ? name : name + ", " + city;
    }

    /** The head-to-head series: the last meetings (newest first) and the source's tally of them. */
    private static void meetings(Football.Details d, JSONArray series) {
        for (int i = 0; series != null && i < series.length(); i++) {
            JSONObject s = series.optJSONObject(i);
            if (s == null || !"head-to-head".equals(s.optString("type"))) continue;
            d.meetingsSummary = s.optString("summary");
            JSONArray events = s.optJSONArray("events");
            for (int j = 0; events != null && j < events.length(); j++) {
                JSONObject e = events.optJSONObject(j);
                if (e == null) continue;
                Football.Match m = new Football.Match();
                m.id = e.optString("id");
                m.at = time(e.optString("date"));
                JSONObject type = e.optJSONObject("statusType");
                m.state = type == null ? e.optString("status", Football.POST) : type.optString("state", Football.POST);
                m.leagueName = e.optString("competitionName").trim();
                JSONArray teams = e.optJSONArray("competitors");
                for (int t = 0; teams != null && t < teams.length(); t++) {
                    JSONObject c = teams.optJSONObject(t);
                    if (c == null) continue;
                    boolean home = "home".equals(c.optString("homeAway"));
                    team(home ? m.home : m.away, c.optJSONObject("team"));
                    if (home) m.homeScore = score(c.opt("score"));
                    else m.awayScore = score(c.opt("score"));
                }
                d.meetings.add(m);
            }
            Collections.sort(d.meetings, (a, b) -> Long.compare(b.at, a.at));
            return;
        }
    }

    /** A team's last five (the source's "lastFiveGames": each has the opponent and both scores). */
    private static void form(List<Football.Match> out, Football.Team team, JSONArray events) {
        for (int i = 0; events != null && i < events.length(); i++) {
            JSONObject e = events.optJSONObject(i);
            if (e == null) continue;
            Football.Match m = new Football.Match();
            m.id = e.optString("id");
            m.at = time(e.optString("gameDate"));
            m.state = Football.POST;
            m.leagueName = e.optString("leagueName");
            boolean home = team.id.equals(e.optString("homeTeamId"));
            Football.Team us = home ? m.home : m.away, them = home ? m.away : m.home;
            us.id = team.id;
            us.name = team.name;
            us.shortName = team.shortName;
            us.abbreviation = team.abbreviation;
            team(them, e.optJSONObject("opponent"));
            m.homeScore = score(e.opt("homeTeamScore"));
            m.awayScore = score(e.opt("awayTeamScore"));
            out.add(m);
        }
        Collections.sort(out, (a, b) -> Long.compare(b.at, a.at));
    }

    @Override public List<Football.Team> teams(String league) throws Exception {
        JSONObject l = json(SITE + league + "/teams").getJSONArray("sports").getJSONObject(0).getJSONArray("leagues").getJSONObject(0);
        JSONArray list = l.getJSONArray("teams");
        List<Football.Team> out = new ArrayList<>();
        for (int i = 0; i < list.length(); i++) {
            Football.Team t = new Football.Team();
            team(t, list.getJSONObject(i).getJSONObject("team"));
            t.league = league;
            t.leagueName = l.optString("name");
            out.add(t);
        }
        Collections.sort(out, (a, b) -> a.name.compareToIgnoreCase(b.name));
        return out;
    }

    @Override public List<Football.Team> search(String name) throws Exception {
        JSONObject o = json("https://site.web.api.espn.com/apis/search/v2?type=team&limit=25&query="
                + URLEncoder.encode(name.trim(), "UTF-8"));
        List<Football.Team> out = new ArrayList<>();
        JSONArray results = o.optJSONArray("results");
        for (int i = 0; results != null && i < results.length(); i++) {
            JSONArray contents = results.getJSONObject(i).optJSONArray("contents");
            for (int j = 0; contents != null && j < contents.length(); j++) {
                JSONObject c = contents.getJSONObject(j);
                if (!"soccer".equals(c.optString("sport"))) continue;
                String uid = c.optString("uid");   // "s:600~t:6273"
                int at = uid.indexOf("t:");
                if (at < 0) continue;
                Football.Team t = new Football.Team();
                t.id = uid.substring(at + 2);
                t.name = c.optString("displayName");
                t.leagueName = c.optString("subtitle");
                out.add(t);
            }
        }
        return out;
    }

    @Override public String logoUrl(String teamId, int px) {
        return "https://a.espncdn.com/combiner/i?img=/i/teamlogos/soccer/500/" + teamId + ".png&w=" + px + "&h=" + px;
    }

    // MARK: - Reading ESPN's objects

    private static void team(Football.Team t, JSONObject o) {
        if (o == null) return;
        t.id = o.optString("id");
        t.name = o.optString("displayName");
        t.shortName = o.optString("shortDisplayName");
        t.abbreviation = o.optString("abbreviation");
    }

    /** An event (a match) as ESPN lists it: in a schedule, as a team's next event, or a summary's header. */
    private static Football.Match match(JSONObject e) throws Exception {
        Football.Match m = new Football.Match();
        m.id = e.optString("id");
        JSONObject league = e.optJSONObject("league");
        if (league != null) {
            m.league = league.optString("slug");
            m.leagueName = league.optString("name");
        }
        JSONArray comps = e.optJSONArray("competitions");
        JSONObject c = comps == null || comps.length() == 0 ? new JSONObject() : comps.getJSONObject(0);
        m.at = time(c.optString("date", e.optString("date")));
        JSONObject status = c.optJSONObject("status");
        if (status == null) status = e.optJSONObject("status");
        if (status != null) {
            JSONObject type = status.optJSONObject("type");
            m.state = type == null ? Football.PRE : type.optString("state", Football.PRE);
            String name = type == null ? "" : type.optString("name");
            // At half time (and other breaks) the stage's name; while the ball rolls, the minute.
            m.clock = name.contains("HALFTIME") || name.contains("END_PERIOD") || name.contains("PENALTIES") || name.contains("EXTRA")
                    ? type.optString("shortDetail", type.optString("description")) : status.optString("displayClock");
        }
        JSONArray teams = c.optJSONArray("competitors");
        for (int i = 0; teams != null && i < teams.length(); i++) {
            JSONObject t = teams.getJSONObject(i);
            boolean home = "home".equals(t.optString("homeAway"));
            team(home ? m.home : m.away, t.optJSONObject("team"));
            int score = score(t.opt("score"));
            if (home) m.homeScore = score;
            else m.awayScore = score;
        }
        if (Football.PRE.equals(m.state)) m.homeScore = m.awayScore = -1;
        m.broadcast = broadcast(c);
        return m;
    }

    /** A score as ESPN writes it: a number, a text, or an object with a value. */
    private static int score(Object s) {
        if (s instanceof JSONObject) return (int) Math.round(((JSONObject) s).optDouble("value", -1));
        if (s instanceof Number) return ((Number) s).intValue();
        if (s instanceof String && !((String) s).isEmpty()) {
            try {
                return Integer.parseInt((String) s);
            } catch (NumberFormatException ignored) {
            }
        }
        return -1;
    }

    private static String broadcast(JSONObject c) {
        StringBuilder b = new StringBuilder();
        JSONArray list = c.optJSONArray("broadcasts");
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject item = list.optJSONObject(i);
            JSONArray names = item == null ? null : item.optJSONArray("names");
            for (int j = 0; names != null && j < names.length(); j++) {
                String n = names.optString(j);
                if (!n.isEmpty() && b.indexOf(n) < 0) b.append(b.length() == 0 ? "" : ", ").append(n);
            }
        }
        JSONArray geo = c.optJSONArray("geoBroadcasts");
        for (int i = 0; geo != null && i < geo.length(); i++) {
            JSONObject g = geo.optJSONObject(i);
            JSONObject media = g == null ? null : g.optJSONObject("media");
            String n = media == null ? "" : media.optString("shortName");
            if (!n.isEmpty() && b.indexOf(n) < 0) b.append(b.length() == 0 ? "" : ", ").append(n);
        }
        return b.toString();
    }

    private static long time(String iso) {
        if (iso == null || iso.length() < 16) return 0;
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            return f.parse(iso.substring(0, 16)).getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    private static void group(Football.Table table, String name, JSONObject standings) {
        if (standings == null) return;
        JSONArray entries = standings.optJSONArray("entries");
        List<Football.TableRow> rows = new ArrayList<>();
        for (int i = 0; entries != null && i < entries.length(); i++) {
            JSONObject e = entries.optJSONObject(i);
            Football.TableRow r = new Football.TableRow();
            team(r.team, e.optJSONObject("team"));
            JSONArray stats = e.optJSONArray("stats");
            for (int j = 0; stats != null && j < stats.length(); j++) {
                JSONObject s = stats.optJSONObject(j);
                int v = (int) Math.round(s.optDouble("value", 0));
                switch (s.optString("name")) {
                    case "rank": r.rank = v; break;
                    case "gamesPlayed": r.played = v; break;
                    case "wins": r.wins = v; break;
                    case "ties": r.draws = v; break;
                    case "losses": r.losses = v; break;
                    case "pointDifferential": r.goalDiff = v; break;
                    case "points": r.points = v; break;
                    default: break;
                }
            }
            rows.add(r);
        }
        Collections.sort(rows, (a, b) -> Integer.compare(a.rank == 0 ? 999 : a.rank, b.rank == 0 ? 999 : b.rank));
        table.groups.add(name);
        table.rows.add(rows);
    }

    private static Football.Event event(JSONObject k) {
        JSONObject type = k.optJSONObject("type");
        String kind = type == null ? "" : type.optString("type");
        Football.Event e = new Football.Event();
        if (k.optBoolean("scoringPlay") || kind.startsWith("goal") || kind.contains("penalty---scored")) e.kind = Football.Event.GOAL;
        else if (kind.contains("yellow")) e.kind = Football.Event.YELLOW;
        else if (kind.contains("red")) e.kind = Football.Event.RED;
        else return null;
        JSONObject clock = k.optJSONObject("clock");
        e.minute = clock == null ? "" : clock.optString("displayValue");
        JSONObject team = k.optJSONObject("team");
        e.teamId = team == null ? "" : team.optString("id");
        JSONArray who = k.optJSONArray("participants");
        if (who != null && who.length() > 0) {
            JSONObject a = who.optJSONObject(0).optJSONObject("athlete");
            e.player = a == null ? "" : a.optString("displayName");
        }
        return e;
    }

    /** The two teams' numbers side by side (the home team's order). */
    private static void stats(Football.Details d, JSONObject box) {
        JSONArray teams = box == null ? null : box.optJSONArray("teams");
        if (teams == null || teams.length() < 2 || d.match == null) return;
        JSONObject a = teams.optJSONObject(0), b = teams.optJSONObject(1);
        boolean aHome = d.match.home.id.equals(a.optJSONObject("team") == null ? "" : a.optJSONObject("team").optString("id"));
        JSONArray home = (aHome ? a : b).optJSONArray("statistics"), away = (aHome ? b : a).optJSONArray("statistics");
        for (int i = 0; home != null && away != null && i < home.length() && i < away.length(); i++) {
            JSONObject h = home.optJSONObject(i), w = away.optJSONObject(i);
            String label = h.optString("label");
            if (label.isEmpty()) continue;
            d.stats.add(new String[] {label.substring(0, 1).toUpperCase(Locale.ROOT) + label.substring(1),
                    h.optString("displayValue"), w.optString("displayValue")});
        }
    }
}
