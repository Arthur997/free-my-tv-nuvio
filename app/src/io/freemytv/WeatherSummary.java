package io.freemytv;

/**
 * The weather screen's sentence about the day, made from the forecast by rules (no AI): rain first (when it starts or
 * stops), then the one other thing worth knowing (strong wind, a very high UV, today's high still ahead, or
 * tomorrow in the evening). At most two short sentences.
 */
final class WeatherSummary {
    private WeatherSummary() {}

    /** Rain counts from this chance (%) or this much water (mm in the hour). */
    private static final int LIKELY = 50;
    private static final double WET_MM = 0.3;
    /** How far ahead the rain sentence looks, in hours. */
    private static final int AHEAD = 12;
    private static final double STRONG_GUSTS = 50, HIGH_UV = 8;

    static String build(Forecast f, long now) {
        if (!f.hasNow() || f.fetchedAt == 0) return "";
        String rain = rain(f);
        String other = wind(f);
        if (other.isEmpty()) other = sun(f, now);
        if (other.isEmpty()) other = high(f, now);
        if (other.isEmpty()) other = tomorrow(f, now);
        return other.isEmpty() ? rain : rain.isEmpty() ? other : rain + " " + other;
    }

    private static boolean wet(Forecast f, int i) {
        return f.hourChance[i] >= LIKELY || f.hourRain[i] >= WET_MM;
    }

    private static boolean storm(int code) {
        return code >= 95;
    }

    /** Raining now: until when. Dry now: from when (in the next 12 hours), or that none is coming. */
    private static String rain(Forecast f) {
        int n = Math.min(AHEAD, f.hourCount);
        if (n == 0) return "";
        boolean raining = f.precipitation >= 0.1 || wet(f, 0);
        if (raining) {
            for (int i = 1; i < n; i++) {
                if (!wet(f, i)) return Ui.trf(Ui.tr("Chuva até por volta das {time}.", "Rain until about {time}.",
                        "Lluvia hasta las {time} aproximadamente."), "time", HomeData.hour(f.hourAt[i]));
            }
            return Ui.tr("Chuva pelas próximas horas.", "Rain for the next few hours.", "Lluvia durante las próximas horas.");
        }
        for (int i = 1; i < n; i++) {
            if (!wet(f, i)) continue;
            String time = HomeData.hour(f.hourAt[i]);
            if (storm(f.hourCode[i])) return Ui.trf(Ui.tr("Tempestade possível a partir das {time}.",
                    "Thunderstorms possible from {time}.", "Tormentas posibles desde las {time}."), "time", time);
            return Ui.trf(Ui.tr("Chuva provável a partir das {time}.", "Rain likely from {time}.",
                    "Lluvia probable desde las {time}."), "time", time);
        }
        int most = 0;
        for (int i = 0; i < n; i++) most = Math.max(most, f.hourChance[i]);
        if (most < 20) return Ui.tr("Sem chuva nas próximas 12 horas.", "No rain in the next 12 hours.",
                "Sin lluvia en las próximas 12 horas.");
        return Ui.trf(Ui.tr("Pouca chance de chuva ({p}).", "Low chance of rain ({p}).", "Poca probabilidad de lluvia ({p})."),
                "p", most + "%");
    }

    private static String wind(Forecast f) {
        double gusts = f.dayCount > 0 && !Double.isNaN(f.dayGusts[0]) ? Math.max(f.dayGusts[0], f.gusts) : f.gusts;
        if (Double.isNaN(gusts) || gusts < STRONG_GUSTS) return "";
        return Ui.trf(Ui.tr("Rajadas de vento de até {v}.", "Wind gusts up to {v}.", "Rachas de viento de hasta {v}."),
                "v", Forecast.speed(gusts));
    }

    /** A very high UV, while the sun is still up for a few hours. */
    private static String sun(Forecast f, long now) {
        if (f.dayCount == 0 || Double.isNaN(f.dayUv[0]) || f.dayUv[0] < HIGH_UV) return "";
        if (f.sunset[0] != 0 && now > f.sunset[0] - 3 * 3600_000L) return "";
        return Ui.tr("UV muito alto: use protetor solar.", "Very high UV: wear sunscreen.", "UV muy alto: usa protector solar.");
    }

    /** Today's high still a few degrees above now: when it comes. */
    private static String high(Forecast f, long now) {
        if (f.dayCount == 0 || Double.isNaN(f.dayMax[0]) || f.dayMax[0] - f.temp < 3) return "";
        long end = Forecast.startOfDay(now, f.zone) + 86_400_000L;
        int peak = -1;
        for (int i = 0; i < f.hourCount && f.hourAt[i] < end; i++) if (peak < 0 || f.hourTemp[i] > f.hourTemp[peak]) peak = i;
        if (peak <= 0) return "";
        return Ui.trf(Ui.tr("Máxima de {max} por volta das {time}.", "High of {max} around {time}.",
                "Máxima de {max} hacia las {time}."), "max", HomeData.degrees(f.dayMax[0]), "time", HomeData.hour(f.hourAt[peak]));
    }

    /** In the evening (after sunset): tomorrow in a few words. */
    private static String tomorrow(Forecast f, long now) {
        if (f.dayCount < 2 || f.sunset[0] == 0 || now < f.sunset[0]) return "";
        return Ui.trf(Ui.tr("Amanhã: {condition}, {max}/{min}.", "Tomorrow: {condition}, {max}/{min}.",
                "Mañana: {condition}, {max}/{min}."), "condition", midSentence(HomeData.condition(f.dayCode[1], true)),
                "max", HomeData.degrees(f.dayMax[1]), "min", HomeData.degrees(f.dayMin[1]));
    }

    /** A condition inside a sentence: lowercase where the language writes it so (German keeps its capital nouns). */
    private static String midSentence(String condition) {
        boolean lower = Ui.en || Ui.es || Ui.language.equals("pt");
        return lower ? condition.toLowerCase(Ui.locale()) : condition;
    }
}
