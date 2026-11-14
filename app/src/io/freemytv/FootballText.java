package io.freemytv;

/** Football in words, shared by the home card, the screensaver and the football screen. */
final class FootballText {
    private FootballText() {}

    /** "Hoje · 20:30", "Amanhã · 16:00", "qua. 18/11 · 21:30" (in the app's clock). */
    static String when(long at) {
        if (at == 0) return "";
        long day = day(at), today = day(System.currentTimeMillis());
        String date = day == today ? Ui.tr("Hoje", "Today", "Hoy") : day == today + 1 ? Ui.tr("Amanhã", "Tomorrow", "Mañana")
                : day == today - 1 ? Ui.tr("Ontem", "Yesterday", "Ayer") : Ui.datePattern("format|EEE d/M").format(at);
        return date + "  ·  " + Ui.time(at);
    }

    /** The day number in the app's clock (for today / tomorrow). */
    private static long day(long at) {
        java.util.TimeZone zone = Clock.zone();
        return Math.floorDiv(at + zone.getOffset(at), 86_400_000L);
    }

    /** "AO VIVO · 67'", "Intervalo", "Encerrado" or "Próximo jogo". */
    static String state(Football.Match m) {
        if (m.live()) return m.clock.isEmpty() ? Ui.tr("Ao vivo", "Live", "En vivo")
                : m.clock.endsWith("'") ? Ui.tr("Ao vivo", "Live", "En vivo") + "  " + m.clock : m.clock;
        if (m.over()) return Ui.tr("Encerrado", "Full time", "Finalizado");
        return Ui.tr("Próximo jogo", "Next match", "Próximo partido");
    }

    /** "2 × 1"; before kickoff, the time instead. */
    static String score(Football.Match m) {
        if (m.homeScore < 0 || m.awayScore < 0) return Ui.time(m.at);
        return m.homeScore + " × " + m.awayScore;
    }

    /** "V", "E" or "D" in Portuguese (W, D, L in English). */
    static String outcome(String code) {
        switch (code) {
            case "W": return Ui.tr("V", "W", "G");
            case "D": return Ui.tr("E", "D", "E");
            case "L": return Ui.tr("D", "L", "P");
            default: return "";
        }
    }
}
