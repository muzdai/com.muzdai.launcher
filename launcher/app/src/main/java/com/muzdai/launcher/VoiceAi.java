package com.muzdai.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Google Gemini reads a spoken request: it fixes what the recognizer misheard (app, artist and film names), works out
 * what is wanted and where, and answers in JSON. The key is the free one from Google AI Studio, as in com.tcl.tv; it
 * is kept in the launcher's settings or read from a gemini-key.txt file. Blocking calls: off the main thread only.
 */
final class VoiceAi {

    static final String PREFS = "voice_ai";
    static final String KEY_KEY = "key", KEY_MODEL = "model", KEY_ON = "on";
    /** Used until a check picks one from the models the key can reach (models come and go). */
    static final String DEFAULT_MODEL = "gemini-2.5-flash-lite";
    static final String FILE = "gemini-key.txt";
    private static final String BASE = "https://generativelanguage.googleapis.com/v1beta/";
    /** A voice request waits this long for the model, then falls back to the plain rules. */
    static final int TIMEOUT_MS = 8000;

    private VoiceAi() { }

    /** What the model made of a request. */
    static final class Answer {
        /** open, play, search or tv. */
        final String action;
        /** An installed package, or "" when none was named or fits. */
        final String app;
        /** What to find or play, corrected; "" for open. */
        final String query;
        /** For open of an app that is not installed: its name and the package it most likely has on Google Play. */
        final String name, guess;
        /** music, video, movie, series or other: where to look when no app is named. */
        final String kind;

        Answer(String action, String app, String query, String name, String guess, String kind) {
            this.action = action;
            this.app = app;
            this.query = query;
            this.name = name;
            this.guess = guess;
            this.kind = kind;
        }
    }

    static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** On unless switched off in settings, and only with a key. */
    static boolean enabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_ON, true) && key(ctx) != null;
    }

    /** The saved key, else the first word of a gemini-key.txt in the launcher's folder, Download or a volume root. */
    static String key(Context ctx) {
        String k = prefs(ctx).getString(KEY_KEY, "");
        if (!k.isEmpty()) return k;
        String fromFile = keyFromFile(ctx);
        if (fromFile != null) prefs(ctx).edit().putString(KEY_KEY, fromFile).apply();
        return fromFile;
    }

    static String keyFromFile(Context ctx) {
        List<File> places = new ArrayList<>();
        File own = Config.dir(ctx);
        if (own != null) places.add(new File(own, FILE));
        places.add(new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FILE));
        File[] volumes = new File("/storage").listFiles();
        if (volumes != null) for (File v : volumes) places.add(new File(v, FILE));
        for (File f : places) {
            try {
                if (!f.canRead()) continue;
                String word = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim().split("\\s+")[0];
                if (!word.isEmpty()) return word;
            } catch (IOException | RuntimeException ignored) {
                // the next place
            }
        }
        return null;
    }

    static String model(Context ctx) {
        return prefs(ctx).getString(KEY_MODEL, DEFAULT_MODEL);
    }

    /**
     * The model's reading of a request. {@code apps}: installed apps, label to package; {@code music}: the package music
     * goes to when no app is named. Throws on no network, a bad key or an answer that is not the expected JSON.
     */
    static Answer understand(String key, String model, String spoken, Map<String, String> apps, String music,
            String onScreen) throws IOException {
        String text = generate(key, model, prompt(spoken, apps, music, onScreen), TIMEOUT_MS);
        Answer a = parse(text);
        if (a == null) throw new IOException("unexpected answer");
        return a;
    }

    static String prompt(String spoken, Map<String, String> apps, String music) {
        return prompt(spoken, apps, music, null);
    }

    /** onScreen: the player open on the TV, where play and search go when the command names no app. */
    static String prompt(String spoken, Map<String, String> apps, String music, String onScreen) {
        StringBuilder list = new StringBuilder();
        for (Map.Entry<String, String> e : apps.entrySet()) {
            list.append("- ").append(e.getKey()).append(" (").append(e.getValue()).append(")\n");
        }
        return "Ты разбираешь голосовую команду пользователя телевизора Google TV. Текст получен распознаванием речи и "
                + "может содержать ошибки: исправь их по смыслу (названия приложений, исполнителей, песен, фильмов, "
                + "сериалов, телеканалов; русские написания иностранных имён переведи в оригинальные).\n"
                + "Установленные приложения (название и пакет):\n" + list
                + "Музыка по умолчанию: " + music + "\n"
                + (onScreen == null ? "" : "Сейчас на экране открыто приложение " + onScreen + ": если в команде play "
                        + "или search приложение не названо, app = " + onScreen + " (и для фильмов, и для музыки).\n")
                + "Ответь только JSON без пояснений:\n"
                + "{\"action\":\"open|play|search|tv\",\"app\":\"пакет из списка или пусто\","
                + "\"query\":\"что найти или включить, исправленное\",\"name\":\"название приложения\","
                + "\"guess\":\"пакет Google Play\",\"kind\":\"music|album|video|movie|series|other\"}\n"
                + "Правила:\n"
                + "- open: открыть приложение. Если оно установлено, app = его пакет. Если нет, app пусто, name = его "
                + "название, guess = наиболее вероятный пакет его версии для Android TV в Google Play (или пусто).\n"
                + "- play: включить музыку, видео, фильм или сериал. app = где включить: названное приложение, иначе для "
                + "музыки — музыка по умолчанию, для видеороликов — YouTube, для фильмов и сериалов — пусто (их ищут "
                + "в медиатеке и кинотеатре сами). query = исполнитель и песня или название.\n"
                + "- search: найти, не включая. app и query — как для play.\n"
                + "- tv: телепрограмма, каналы эфира, «что идёт», матчи и трансляции по телевизору; app пусто, query = "
                + "запрос как сказан, исправленный.\n"
                + "- kind: album — просят альбом целиком («включи альбом …») и не назван отдельный трек; тогда query = "
                + "исполнитель и название альбома; music — песня, исполнитель или трек из альбома; video — ролик, клип, блогер, обзор; movie — фильм; series — "
                + "сериал или его серия; other — остальное.\n"
                + "- Для фильма или сериала query — только его название (без слов «фильм», «сериал», «серия»).\n"
                + "Команда: " + spoken;
    }

    /** The JSON object in the model's text (it may wrap it in ``` fences), or null. */
    static Answer parse(String text) {
        if (text == null) return null;
        int from = text.indexOf('{'), to = text.lastIndexOf('}');
        if (from < 0 || to <= from) return null;
        try {
            JSONObject o = new JSONObject(text.substring(from, to + 1));
            String action = o.optString("action", "").trim().toLowerCase(java.util.Locale.ROOT);
            if (!action.equals("open") && !action.equals("play") && !action.equals("search") && !action.equals("tv")) {
                return null;
            }
            String kind = o.optString("kind", "other").trim().toLowerCase(java.util.Locale.ROOT);
            return new Answer(action, o.optString("app", "").trim(), o.optString("query", "").trim(),
                    o.optString("name", "").trim(), o.optString("guess", "").trim(), kind);
        } catch (JSONException e) {
            return null;
        }
    }

    /** The model's text answer; one retry when it is overloaded (503). */
    static String generate(String key, String model, String prompt, int timeoutMs) throws IOException {
        JSONObject body = new JSONObject();
        try {
            body.put("contents", new JSONArray().put(new JSONObject().put("role", "user")
                    .put("parts", new JSONArray().put(new JSONObject().put("text", prompt)))));
            body.put("generationConfig", new JSONObject().put("temperature", 0.1)
                    .put("responseMimeType", "application/json"));
        } catch (JSONException e) {
            throw new IOException(e);
        }
        JSONObject r;
        try {
            r = call("POST", "models/" + model + ":generateContent", key, body.toString(), timeoutMs);
        } catch (Busy e) {
            r = call("POST", "models/" + model + ":generateContent", key, body.toString(), timeoutMs);
        }
        StringBuilder text = new StringBuilder();
        JSONArray candidates = r.optJSONArray("candidates");
        JSONObject content = candidates == null || candidates.length() == 0 ? null
                : candidates.optJSONObject(0).optJSONObject("content");
        JSONArray parts = content == null ? null : content.optJSONArray("parts");
        for (int i = 0; parts != null && i < parts.length(); i++) text.append(parts.optJSONObject(i).optString("text", ""));
        return text.toString();
    }

    /**
     * Checks the key and picks a model: the Flash models the key can reach, lite (faster) and newer first, and the
     * first of them that answers a one-word request. As com.tcl.tv does.
     */
    static String check(String key) throws IOException {
        JSONObject r = call("GET", "models?pageSize=200", key, null, 15000);
        JSONArray models = r.optJSONArray("models");
        List<String> names = new ArrayList<>();
        for (int i = 0; models != null && i < models.length(); i++) {
            JSONObject m = models.optJSONObject(i);
            JSONArray methods = m.optJSONArray("supportedGenerationMethods");
            if (methods != null && methods.toString().contains("generateContent")) {
                names.add(m.optString("name", "").replaceFirst("^models/", ""));
            }
        }
        IOException last = null;
        List<String> order = order(names);
        for (int i = 0; i < order.size() && i < 5; i++) {
            try {
                generate(key, order.get(i), "Ответь одним словом: да", 15000);
                return order.get(i);
            } catch (IOException e) {
                last = e;
            }
        }
        throw last != null ? last : new IOException("no Gemini Flash model");
    }

    private static final java.util.regex.Pattern VERSION = java.util.regex.Pattern.compile("gemini-(\\d+(?:\\.\\d+)?)");

    /** Flash text models only, best first: lite before full, newer before older, stable before preview. */
    static List<String> order(List<String> names) {
        List<String> out = new ArrayList<>();
        for (String n : names) {
            String l = n.toLowerCase(java.util.Locale.ROOT);
            if (!l.startsWith("gemini-") || !l.contains("flash") || l.matches(
                    ".*(image|tts|audio|live|thinking|embedding|robotics|computer|native|omni|transcribe).*")) continue;
            out.add(n);
        }
        out.sort((a, b) -> Double.compare(score(b), score(a)));
        return out;
    }

    private static double score(String name) {
        String l = name.toLowerCase(java.util.Locale.ROOT);
        java.util.regex.Matcher m = VERSION.matcher(l);
        double s = (m.find() ? Double.parseDouble(m.group(1)) : 0) * 100;
        if (l.contains("lite")) s += 1000;
        if (l.contains("preview") || l.contains("exp")) s -= 2;
        if (l.contains("latest")) s -= 50;   // aliases are often overloaded
        return s;
    }

    private static final class Busy extends IOException {
        Busy() { super("busy"); }
    }

    private static JSONObject call(String method, String path, String key, String body, int timeoutMs)
            throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(BASE + path).openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setRequestProperty("x-goog-api-key", key);
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (OutputStream out = c.getOutputStream()) {
                    out.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = c.getResponseCode();
            String text = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
            if (code == 503 || code == 429) throw new Busy();
            if (code >= 400) throw new IOException("HTTP " + code);
            return new JSONObject(text);
        } catch (JSONException e) {
            throw new IOException(e);
        } finally {
            c.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) return "";
        try (InputStream s = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            for (int n; (n = s.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
}
