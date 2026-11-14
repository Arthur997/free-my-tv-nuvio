package io.freemytv;

import java.util.List;

/**
 * Where the football comes from. The widget and the football screen only see this contract, so a source that stops
 * working (ESPN's is free but unofficial) can be swapped for another (API-Football, with a key) without touching them.
 * Every call does network work: never on the UI thread.
 */
interface FootballSource {
    /** The team's card: the live or next match and where it stands. Light (~20 KB): the home screen's only request. */
    Football.Overview overview(String teamId) throws Exception;

    /** The season's matches in every competition, played and to come, by date. Heavy: the football screen only. */
    List<Football.Match> schedule(String teamId) throws Exception;

    Football.Table table(String league) throws Exception;

    /** The match in detail, with the two teams' meetings and recent form (one request). */
    Football.Details details(String league, String matchId) throws Exception;

    /** The bookmakers' odds for the match (small; none for many matches). */
    Football.Odds odds(String league, String matchId) throws Exception;

    /** The teams of a league, by name (the team picker's lists). */
    List<Football.Team> teams(String league) throws Exception;

    List<Football.Team> search(String name) throws Exception;

    /** A small square logo for the team, ready to draw at `px` pixels (null: none). */
    String logoUrl(String teamId, int px);

    /** The source in use. */
    static FootballSource get() {
        return EspnFootball.INSTANCE;
    }
}
