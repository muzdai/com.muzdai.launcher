package com.muzdai.launcher;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

/**
 * The owner's own model on the home server (the "assist" service next to llama-server): it reads a request as Gemini
 * does, and also answers questions (weather, rates, facts, news) in a short text to show and speak. Asked before
 * Gemini; when it is not set or does not answer, Gemini and then the plain rules decide. The address is kept in the
 * voice settings or read from a local-ai.txt in the launcher's folder. Blocking calls: off the main thread only.
 */
final class LocalAi {

    /** In {@link VoiceAi#PREFS}: the service's base address, e.g. http://192.168.1.247:8090. */
    static final String KEY_URL = "local_url";
    static final String FILE = "local-ai.txt";
    /** A server that is off or away must not hold the request up: Gemini is asked next. */
    static final int CONNECT_MS = 1500;
    /** A question looked up on the web takes the model two passes and a search. */
    static final int READ_MS = 25000;

    private LocalAi() { }

    static SharedPreferences prefs(Context ctx) {
        return VoiceAi.prefs(ctx);
    }

    /** On with the AI switch in settings, and only with an address. */
    static boolean enabled(Context ctx) {
        return prefs(ctx).getBoolean(VoiceAi.KEY_ON, true) && url(ctx) != null;
    }

    /** The saved address, else the first word of local-ai.txt in the launcher's folder; null when neither. */
    static String url(Context ctx) {
        String u = prefs(ctx).getString(KEY_URL, "");
        if (!u.isEmpty()) return u;
        File own = Config.dir(ctx);
        if (own == null) return null;
        try {
            File f = new File(own, FILE);
            if (!f.canRead()) return null;
            String word = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim().split("\\s+")[0];
            return normalize(word);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** "192.168.1.247:8090" or "http://…/" as a base address without the trailing slash; null for nothing. */
    static String normalize(String u) {
        if (u == null) return null;
        u = u.trim();
        if (u.isEmpty()) return null;
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://" + u;
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }

    /**
     * The model's reading of a request, with {@link VoiceAi.Answer#reply} set for a question. {@code place}: the home
     * city and its coordinates from the weather settings (null when not set). Throws when the server is away or answers
     * something unexpected.
     */
    static VoiceAi.Answer understand(String url, String spoken, Map<String, String> apps, String music, String onScreen,
            String city, Float lat, Float lon, List<String> currencies) throws IOException {
        String body = body(spoken, apps, music, onScreen, city, lat, lon, currencies);
        VoiceAi.Answer a = parse(read(url + "/voice", body, READ_MS));
        if (a == null) throw new IOException("unexpected answer");
        return a;
    }

    static String body(String spoken, Map<String, String> apps, String music, String onScreen, String city, Float lat,
            Float lon, List<String> currencies) {
        try {
            JSONObject list = new JSONObject();
            for (Map.Entry<String, String> e : apps.entrySet()) list.put(e.getKey(), e.getValue());
            JSONObject b = new JSONObject().put("text", spoken).put("apps", list).put("music", music);
            if (onScreen != null) b.put("on_screen", onScreen);
            if (lat != null && lon != null) {
                b.put("place", new JSONObject().put("city", city == null ? "" : city).put("lat", (double) lat)
                        .put("lon", (double) lon));
            }
            if (currencies != null) b.put("currencies", new JSONArray(currencies));
            return b.toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The service's JSON: as {@link VoiceAi#parse}, plus the spoken answer to a question. Null when unusable. */
    static VoiceAi.Answer parse(String text) {
        VoiceAi.Answer a = VoiceAi.parse(text);
        if (a == null) return null;
        try {
            String reply = new JSONObject(text).optString("answer", "").trim();
            if ("answer".equals(a.action) && reply.isEmpty()) return null;
            return a.withReply(reply);
        } catch (JSONException e) {
            return null;
        }
    }

    /** True when the service answers its health check. */
    static boolean check(String url) {
        try {
            return new JSONObject(read(url + "/health", null, 5000)).optBoolean("ok", false);
        } catch (IOException | JSONException e) {
            return false;
        }
    }

    private static String read(String url, String body, int readMs) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(CONNECT_MS);
            c.setReadTimeout(readMs);
            if (body != null) {
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (OutputStream out = c.getOutputStream()) {
                    out.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = c.getResponseCode();
            if (code >= 400) throw new IOException("HTTP " + code);
            try (InputStream s = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                for (int n; (n = s.read(buf)) > 0; ) out.write(buf, 0, n);
                return out.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            c.disconnect();
        }
    }
}
