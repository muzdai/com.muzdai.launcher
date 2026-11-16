package com.muzdai.launcher;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

/**
 * Service keys for voice search, from voice-keys.txt in the launcher's folder on the TV
 * (Android/data/com.muzdai.launcher/files): one "name=value" per line, # for comments. Kept out of the code and the
 * repository. Names: emby_url, emby_key, tmdb_key, spotify_id, spotify_secret.
 */
final class VoiceKeys {
    static final String FILE = "voice-keys.txt";
    static final String EMBY_URL = "emby_url", EMBY_KEY = "emby_key", TMDB_KEY = "tmdb_key",
            SPOTIFY_ID = "spotify_id", SPOTIFY_SECRET = "spotify_secret";

    private final Map<String, String> values;

    private VoiceKeys(Map<String, String> values) {
        this.values = values;
    }

    static VoiceKeys load(Context ctx) {
        File dir = Config.dir(ctx);
        String text = "";
        try {
            if (dir != null) text = new String(Files.readAllBytes(new File(dir, FILE).toPath()), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException ignored) {
            // no file: no keys
        }
        return new VoiceKeys(parse(text));
    }

    static Map<String, String> parse(String text) {
        Map<String, String> out = new HashMap<>();
        for (String line : text.split("\n")) {
            String l = line.trim();
            int eq = l.indexOf('=');
            if (l.isEmpty() || l.startsWith("#") || eq <= 0) continue;
            String v = l.substring(eq + 1).trim();
            if (!v.isEmpty()) out.put(l.substring(0, eq).trim().toLowerCase(java.util.Locale.ROOT), v);
        }
        return out;
    }

    /** The value, or null. */
    String get(String name) {
        return values.get(name);
    }

    boolean has(String... names) {
        for (String n : names) if (values.get(n) == null) return false;
        return true;
    }
}
