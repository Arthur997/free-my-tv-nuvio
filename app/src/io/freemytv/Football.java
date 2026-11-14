package io.freemytv;

import java.util.ArrayList;
import java.util.List;

/**
 * Football as the app sees it, whatever the source (FootballSource; ESPN today): teams, matches, tables and a match's
 * events. Plain data: made by a source on a background thread, then only read.
 */
final class Football {
    private Football() {}

    static final String PRE = "pre", LIVE = "in", POST = "post";

    static final class Team {
        String id = "", name = "", shortName = "", abbreviation = "";
        /** Its main league (the source's code: "bra.1", "eng.1"…) and that league's name. */
        String league = "", leagueName = "";

        String label() {
            return shortName.isEmpty() ? name : shortName;
        }
    }

    static final class Match {
        String id = "", league = "", leagueName = "";
        long at;
        /** PRE (not started), LIVE (playing, or at half time) or POST (over). */
        String state = PRE;
        /** While live: the minute ("67'") or the stage ("Intervalo"). */
        String clock = "";
        final Team home = new Team(), away = new Team();
        /** -1 before it starts. */
        int homeScore = -1, awayScore = -1;
        /** Where it's on (TV channels), when the source says. */
        String broadcast = "";

        boolean live() {
            return LIVE.equals(state);
        }

        boolean over() {
            return POST.equals(state);
        }

        /** Live, or past its kickoff and not over yet (the source can take a few minutes to say it started). */
        boolean underway(long now) {
            return live() || !over() && at <= now && now - at < 3 * 3600_000L;
        }

        boolean has(String teamId) {
            return home.id.equals(teamId) || away.id.equals(teamId);
        }

        /** "V", "E" or "D" for the team, in a match that's over ("" otherwise). */
        String outcome(String teamId) {
            if (!over() || homeScore < 0 || awayScore < 0 || !has(teamId)) return "";
            int mine = home.id.equals(teamId) ? homeScore : awayScore, theirs = home.id.equals(teamId) ? awayScore : homeScore;
            return mine > theirs ? "W" : mine < theirs ? "L" : "D";
        }

        Team opponent(String teamId) {
            return home.id.equals(teamId) ? away : home;
        }
    }

    /** The team followed, as the home screen's card needs it: one light request. */
    static final class Overview {
        final Team team = new Team();
        /** The match being played now, or else the next one (null: none scheduled). */
        Match match;
        /** "1º no Campeonato Brasileiro", in the source's words. */
        String standing = "";
    }

    static final class TableRow {
        int rank, played, wins, draws, losses, goalDiff, points;
        final Team team = new Team();
    }

    /** A league's table: one group for a league, several for a cup's group stage. */
    static final class Table {
        String league = "", name = "";
        final List<String> groups = new ArrayList<>();
        final List<List<TableRow>> rows = new ArrayList<>();
    }

    static final class Event {
        static final int GOAL = 0, YELLOW = 1, RED = 2;
        int kind;
        String minute = "", player = "", teamId = "";
    }

    /** A match in detail: its goals and cards, the line-ups, the numbers, and the two teams' record. */
    static final class Details {
        Match match;
        /** "Estádio do Maracanã, Rio de Janeiro". */
        String venue = "";
        /** The last meetings between the two teams (newest first) and their tally ("FLA lidera 2-1-2"). */
        final List<Match> meetings = new ArrayList<>();
        String meetingsSummary = "";
        /** Each team's last five matches, newest first. */
        final List<Match> homeForm = new ArrayList<>(), awayForm = new ArrayList<>();
        final List<Event> events = new ArrayList<>();
        String homeFormation = "", awayFormation = "";
        final List<String> homeLineup = new ArrayList<>(), awayLineup = new ArrayList<>();
        /** {label, home, away}, as the source names them. */
        final List<String[]> stats = new ArrayList<>();
    }

    /**
     * Betting odds for a match, as decimal odds averaged over the bookmakers the source lists (NaN: none), and the
     * chance each implies with the bookmakers' margin taken out.
     */
    static final class Odds {
        final List<String> bookmakers = new ArrayList<>();
        double home = Double.NaN, draw = Double.NaN, away = Double.NaN;

        boolean any() {
            return !Double.isNaN(home) && !Double.isNaN(away);
        }

        /** The implied chance of `odd` (one of home, draw, away), in %, the three adding up to 100. */
        int chance(double odd) {
            double sum = 1 / home + 1 / away + (Double.isNaN(draw) ? 0 : 1 / draw);
            return (int) Math.round(100 / odd / sum);
        }
    }
}
