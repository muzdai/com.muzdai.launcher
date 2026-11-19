package com.muzdai.launcher;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Spoken commands for Roon's playback: pause, next, louder, "звук на 30", "что играет", radio, queue, moving the music
 * to another zone, playing everywhere. Volume is spoken on a 0–100 scale whatever the device uses; {@link #toDevice}
 * and {@link #toScale} convert. Pure, tested on the JVM.
 */
final class RoonCommands {
    private RoonCommands() { }

    /** What to do; {@code arg}: the number for volume, the words for queue and next, the zone for transfer. */
    enum Kind {
        PAUSE, PLAY, STOP, NEXT, PREVIOUS, LOUDER, QUIETER, VOLUME, MUTE, UNMUTE, WHAT_PLAYS, WHAT_VOLUME, RADIO,
        PLAY_NEXT, QUEUE, TRANSFER, EVERYWHERE
    }

    static final class Cmd {
        final Kind kind;
        final String arg;
        /** Roon was named ("пауза на руне"): the command is Roon's whatever else plays. */
        final boolean named;

        Cmd(Kind kind, String arg, boolean named) {
            this.kind = kind;
            this.arg = arg;
            this.named = named;
        }

        /** Commands that only make sense for Roon (zones, queue, "everywhere"): no need for Roon to be playing. */
        boolean roonOnly() {
            return kind == Kind.TRANSFER || kind == Kind.EVERYWHERE;
        }
    }

    private static final Pattern ROON = Pattern.compile("\\s+(?:на|в|во|через|по)\\s+(?:руне|руну|рун|роне|рооне|роону|"
            + "роон|roon)(?:\\s+(?:аудио|audio))?$|\\s+(?:рун|роон|roon)$");
    /** A number, "2,5" kept whole: normalizing turns punctuation into spaces, so the comma becomes "x" first. */
    private static final String NUM = "(\\d{1,3}(?:x\\d)?)";
    private static final String PCT = "(?:\\s*(?:%|процент[а-я]*))?";

    private static final Object[][] RULES = {
            {Kind.PAUSE, "(?:поставь )?(?:на )?паузу?|пауза|приостанови(?: музыку)?"},
            {Kind.STOP, "стоп|останови(?: музыку)?|выключи музыку|хватит"},
            {Kind.PLAY, "продолжи(?:ть)?|продолжай|играй|сними (?:с )?паузу?|сними с паузы|воспроизведи|сыграй дальше"},
            {Kind.NEXT, "дальше|следующ(?:ий|ую|ая)(?: трек| песн[юя]| композици[юя])?|вперед|пропусти|переключи(?: трек| песню)?"
                    + "|другую песню"},
            {Kind.PREVIOUS, "назад|предыдущ(?:ий|ую|ая)(?: трек| песн[юя]| композици[юя])?|верни(?: назад| прошлую)?"
                    + "|прошл(?:ую|ый)(?: трек| песню)?"},
            {Kind.LOUDER, "(?:сделай |чуть |еще |немного )*(?:громче|погромче|прибавь(?: звук| громкость)?)(?: на " + NUM + PCT + ")?"},
            {Kind.QUIETER, "(?:сделай |чуть |еще |немного )*(?:тише|потише|убавь(?: звук| громкость)?)(?: на " + NUM + PCT + ")?"},
            {Kind.VOLUME, "(?:сделай |поставь |установи |выставь )?(?:звук|громкость)(?: на)? " + NUM + PCT},
            {Kind.MUTE, "выключи звук|убери звук|без звука|отключи звук|mute"},
            {Kind.UNMUTE, "включи звук|верни звук"},
            {Kind.WHAT_PLAYS, "что (?:сейчас )?(?:играет|звучит)|что за (?:песня|трек|музыка)(?: играет| звучит)?"
                    + "|какая (?:это |сейчас )?(?:песня|музыка)(?: играет)?|кто (?:это )?(?:поет|играет|исполняет)"},
            {Kind.WHAT_VOLUME, "какая (?:сейчас )?громкость|какой (?:сейчас )?(?:уровень )?звук|на сколько громкость"
                    + "|сколько громкость"},
            {Kind.RADIO, "(?:включи |запусти )?радио(?: по| на основе| как)? (?:этой|этому|эту|этого)(?: песне| песню| трек[уа]?"
                    + "| музыке| исполнител[юя])?|(?:включи |поставь )?похож(?:ую|ее|ие)(?: музыку| песни)?"},
            {Kind.PLAY_NEXT, "(?:включи|поставь|сыграй) следующ(?:им|ей) (.+)|(?:включи|поставь) (.+) следующ(?:им|ей)"},
            {Kind.QUEUE, "(?:добавь|поставь) в очередь (.+)|(?:добавь|поставь) (.+) в очередь|в очередь (.+)"},
            {Kind.TRANSFER, "(?:перенеси|переключи|переведи|перекинь)(?: музыку| звук| воспроизведение)? (?:на|в|во) (.+)"},
            {Kind.EVERYWHERE, "(?:включи |играй )?(?:музыку )?(?:везде|во всех комнатах|на всех(?: колонках| зонах)?)"
                    + "|объедини (?:все )?(?:зоны|колонки)"},
    };
    private static final Pattern[] PATTERNS = new Pattern[RULES.length];

    static {
        for (int i = 0; i < RULES.length; i++) PATTERNS[i] = Pattern.compile("^(?:" + RULES[i][1] + ")$");
    }

    /** The command the words are, or null when they are something else. */
    static Cmd parse(String spoken) {
        if (spoken == null) return null;
        String t = VoiceCommand.normalize(spoken.replaceAll("(\\d)[.,](\\d)", "$1x$2"));
        t = t.replaceFirst("^(?:пожалуйста|ну|давай|эй) ", "");
        Matcher r = ROON.matcher(t);
        boolean named = r.find();
        if (named) t = t.substring(0, r.start()).trim();
        for (int i = 0; i < RULES.length; i++) {
            Matcher m = PATTERNS[i].matcher(t);
            if (!m.matches()) continue;
            String arg = null;
            for (int g = 1; g <= m.groupCount(); g++) if (m.group(g) != null) arg = m.group(g).trim().replaceAll("(\\d)x(\\d)", "$1,$2");
            return new Cmd((Kind) RULES[i][0], arg, named);
        }
        return null;
    }

    // ---- volume on the 0–100 scale ------------------------------------------------------------------

    /**
     * The device value for {@code percent} of a volume from {@code min} to {@code max} in steps of {@code step}: in
     * proportion, rounded down to a step the device has.
     */
    static double toDevice(double percent, double min, double max, double step) {
        double p = Math.max(0, Math.min(100, percent));
        double s = step > 0 ? step : 1;
        long steps = (long) Math.floor((max - min) * p / 100 / s + 1e-9);
        return Math.min(max, min + steps * s);
    }

    /** The 0–100 value of a device volume. */
    static double toScale(double value, double min, double max) {
        if (max <= min) return 0;
        return Math.max(0, Math.min(100, (value - min) * 100 / (max - min)));
    }

    /** "67", "67,5": a 0–100 value as spoken back. */
    static String say(double percent) {
        double r = Math.round(percent * 10) / 10.0;
        return r == Math.floor(r) ? String.valueOf((long) r) : String.format(Locale.ROOT, "%.1f", r).replace('.', ',');
    }

    /** "2,5" or "2.5" in a command. */
    static double number(String s, double fallback) {
        if (s == null) return fallback;
        try {
            return Double.parseDouble(s.replace(',', '.').replace('x', '.'));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
