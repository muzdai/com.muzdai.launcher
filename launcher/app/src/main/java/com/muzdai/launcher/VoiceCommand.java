package com.muzdai.launcher;

import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A spoken request, split into what to do: open an app ("запусти ютуб"), look for something in a named app ("включи
 * Джо Кокера на ютубе"), a TV programme question ("что идёт на первом", "включи канал Россия") or a plain search.
 * Pure text rules, no Android types, so it is tested on the JVM; which phrases are apps is the caller's to say.
 */
final class VoiceCommand {
    enum Kind { OPEN, IN_APP, TV, SEARCH }

    final Kind kind;
    /** The app as it was said ("ютубе", "спотифай"), for OPEN and IN_APP; null otherwise. */
    final String app;
    /** What to look for: without the verb and the app for IN_APP and SEARCH, the whole phrase for TV, null for OPEN. */
    final String query;
    /** A play verb (включи, поставь, сыграй): the content should start, not only be shown. */
    final boolean play;
    /** SEARCH after an open verb with a short rest ("запусти лампу"): it may be an app that is not installed. */
    final String maybeApp;

    private VoiceCommand(Kind kind, String app, String query, boolean play, String maybeApp) {
        this.kind = kind;
        this.app = app;
        this.query = query;
        this.play = play;
        this.maybeApp = maybeApp;
    }

    private static final Pattern VERB = Pattern.compile("^(?:пожалуйста\\s+)?"
            + "(включи|включить|запусти|запустить|открой|открыть|поставь|сыграй|играй|проиграй|покажи|найди|найти|ищи|"
            + "поищи|хочу посмотреть|хочу послушать)\\s+(.+)$");
    /** The last "на / в / во / через / по <app>" of the rest: "песни битлз на ютубе" → ("песни битлз", "ютубе"). */
    private static final Pattern IN = Pattern.compile("^(.+)\\s+(?:на|в|во|через|по)\\s+(.+)$");
    /**
     * Words of a TV programme request, on the normalized text. Plain letter classes, no \\b or \\w: Android's ICU regex
     * rejects the (?U) flag the JVM needs for Cyrillic word boundaries, and VoiceCommand loads on Home.
     */
    private static final Pattern TV = Pattern.compile("(?:^| )(?:канал|телеканал|телепрограмм|телегид|матч|трансляци)"
            + "[а-я]*|по телевизору|по тв(?: |$)|что (?:сейчас )?идет|программ[а-я]* передач|в эфире");

    /**
     * @param isApp whether a phrase names an app (installed or a known one): "ютуб", "ютубе", "спотифай"
     */
    static VoiceCommand parse(String spoken, Predicate<String> isApp) {
        String text = normalize(spoken);
        if (text.isEmpty()) return new VoiceCommand(Kind.SEARCH, null, "", false, null);
        String verb = null, rest = text;
        Matcher m = VERB.matcher(text);
        if (m.matches()) {
            verb = m.group(1);
            rest = m.group(2);
        }
        boolean play = verb != null && (verb.startsWith("включ") || verb.equals("поставь") || verb.endsWith("играй"));
        if (isApp.test(rest)) return new VoiceCommand(Kind.OPEN, rest, null, play, null);
        if (TV.matcher(text).find()) return new VoiceCommand(Kind.TV, null, spoken.trim(), play, null);
        Matcher in = IN.matcher(rest);
        if (in.matches() && isApp.test(in.group(2))) {
            return new VoiceCommand(Kind.IN_APP, in.group(2), in.group(1), play, null);
        }
        boolean openVerb = verb != null && (verb.startsWith("включ") || verb.startsWith("запуст")
                || verb.startsWith("откр"));
        String maybeApp = openVerb && rest.split(" ").length <= 3 ? rest : null;
        return new VoiceCommand(Kind.SEARCH, null, rest, play, maybeApp);
    }

    /** Lower case, ё as е, no punctuation, single spaces. */
    static String normalize(String s) {
        if (s == null) return "";
        String t = s.toLowerCase(Locale.ROOT).replace('ё', 'е');
        t = t.replaceAll("[\\p{Punct}«»„“”—–…]+", " ");
        return t.replaceAll("\\s+", " ").trim();
    }
}
