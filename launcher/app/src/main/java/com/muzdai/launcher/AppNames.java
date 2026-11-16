package com.muzdai.launcher;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Finds the app a spoken name means: Russian spellings of the TV apps ("ютуб", "спотифай", "кобуз"), the labels of the
 * apps on the TV, and declined forms ("на ютубе", "запусти лампу"). Pure text, tested on the JVM.
 */
final class AppNames {
    private AppNames() { }

    /** A pseudo package for the TV's own settings screen ("открой настройки"). */
    static final String SETTINGS = "settings";

    /** Spoken name (as {@link #key}) to package; several names may share one. */
    private static final Map<String, String> ALIASES = new HashMap<>();
    /** Package to the name shown when it is not installed. */
    private static final Map<String, String> NAMES = new LinkedHashMap<>();
    /** Apps without a Play Store listing: the name the dialog shows, keyed like ALIASES. */
    private static final Map<String, String> NOT_ON_PLAY = new HashMap<>();

    private static void known(String pkg, String name, String... spoken) {
        NAMES.put(pkg, name);
        for (String s : spoken) ALIASES.put(key(s), pkg);
    }

    static {
        known("com.google.android.youtube.tvmusic", "YouTube Music", "youtube music", "ютуб музыка", "ютуб мьюзик",
                "ютюб музыка", "ютуб мьюзик", "музыка ютуб");
        known("com.google.android.youtube.tv", "YouTube", "youtube", "ютуб", "ютюб", "ютьюб", "ю туб");
        known("com.spotify.tv.android", "Spotify", "spotify", "спотифай", "спотифи", "спотифаи");
        known("deezer.android.app", "Deezer", "deezer", "дизер", "дизэр", "диззер");
        known("com.qobuz.music", "Qobuz", "qobuz", "кобуз", "кобус", "куобуз", "кьюбаз");
        known("tv.emby.embyatv", "Emby", "emby", "эмби", "эмбай", "эмбу");
        known("cc.alcopa.tv", "ALPAC", "alpac", "алпак", "альпак", "алпака");
        known("ar.tvplayer.tv", "TiviMate", "tivimate", "тивимейт", "тиви мейт", "тивимэйт");
        known("ru.yourok.torrserve", "TorrServe", "torrserve", "торсерв", "торрсерв", "тор серв");
        known("com.netflix.ninja", "Netflix", "netflix", "нетфликс");
        known("com.android.vending", "Google Play", "play market", "плей маркет", "плеймаркет", "гугл плей",
                "google play");
        known("com.tcl.tv", "TV", "тв", "телевизор", "эфир", "телеканалы", "антенна");
        known(SETTINGS, "Settings", "настройки", "настройки телевизора", "settings");
        NOT_ON_PLAY.put(key("лампа"), "Lampa");
        NOT_ON_PLAY.put(key("lampa"), "Lampa");
    }

    /**
     * The package a spoken name means, or null. {@code installed} maps app labels to packages (the TV's launchable
     * apps); a known alias wins, then an installed label, each also with a Russian case ending taken off.
     */
    static String find(String spoken, Map<String, String> installed) {
        for (String k : forms(spoken)) {
            if (k.isEmpty()) continue;
            String pkg = ALIASES.get(k);
            if (pkg != null) return pkg;
            for (Map.Entry<String, String> e : installed.entrySet()) {
                if (matches(k, key(e.getKey()))) return e.getValue();
            }
        }
        return null;
    }

    /** True for a name of an app the TV lacks that has no Play Store listing (Lampa ships as an APK). */
    static String notOnPlay(String spoken) {
        for (String k : forms(spoken)) {
            String name = NOT_ON_PLAY.get(k);
            if (name != null) return name;
        }
        return null;
    }

    /** The display name of a known package, or null. */
    static String name(String pkg) {
        return NAMES.get(pkg);
    }

    /** A label matches when equal as written or in Latin letters, or when the label starts with the name (4+ letters). */
    private static boolean matches(String spokenKey, String labelKey) {
        if (labelKey.isEmpty()) return false;
        if (spokenKey.equals(labelKey) || latin(spokenKey).equals(latin(labelKey))) return true;
        return spokenKey.length() >= 4 && (labelKey.startsWith(spokenKey) || latin(labelKey).startsWith(latin(spokenKey)));
    }

    /** The name as said, then without one Russian case ending: "ютубе" → "ютуб", "лампу" → "ламп"/"лампа". */
    static String[] forms(String spoken) {
        String k = key(spoken);
        String[] endings = {"ом", "ем", "ой", "ей", "е", "у", "ю", "а", "я", "ы", "и"};
        String[] out = new String[endings.length * 2 + 1];
        out[0] = k;
        int n = 1;
        for (String end : endings) {
            if (k.length() > end.length() + 2 && k.endsWith(end)) {
                String stem = k.substring(0, k.length() - end.length());
                out[n++] = stem;
                out[n++] = stem + "а";
            }
        }
        String[] trimmed = new String[n];
        System.arraycopy(out, 0, trimmed, 0, n);
        return trimmed;
    }

    /** Lower case, ё as е, letters and digits only. */
    static String key(String s) {
        return VoiceCommand.normalize(s).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private static final String CYR = "абвгдеёжзийклмнопрстуфхцчшщъыьэюя";
    private static final String[] LAT = {"a", "b", "v", "g", "d", "e", "e", "zh", "z", "i", "i", "k", "l", "m", "n",
            "o", "p", "r", "s", "t", "u", "f", "h", "ts", "ch", "sh", "sch", "", "y", "", "e", "yu", "ya"};

    /** Cyrillic written in Latin letters, so "эмби" meets "Emby" and "нетфликс" meets "Netflix". */
    static String latin(String s) {
        StringBuilder b = new StringBuilder(s.length() + 4);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int at = CYR.indexOf(c);
            b.append(at >= 0 ? LAT[at] : String.valueOf(c));
        }
        return b.toString();
    }
}
