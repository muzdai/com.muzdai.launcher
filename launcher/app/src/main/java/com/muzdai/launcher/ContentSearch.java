package com.muzdai.launcher;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds content for voice search in the background and says how to open it on the TV, as tested on it: YouTube (its
 * results page; a watch link plays), Spotify (Web API; a spotify:track link plays), Deezer (open API; an album link,
 * then a click on the track's row), the owner's Emby server (displayitem link, focus on Play) and TMDB for ALPAC
 * (alpac://title link, focus on Watch). Blocking network calls: off the main thread only. The parsers are pure and
 * tested on the JVM.
 */
final class ContentSearch {

    /** One result: plain data, so it is tested on the JVM and passed between activities as a Bundle. */
    static final class Card {
        final String pkg, uri, title, subtitle, image;
        /**
         * After the link opens: null, nothing to press; "", press the focused button (Play / Watch); otherwise press the
         * row whose text starts with this (Deezer's track row, "Title, Artist").
         */
        final String click;
        /** The app must be running before its link works (Deezer shows an error otherwise). */
        final boolean launchFirst;

        Card(String pkg, String uri, String title, String subtitle, String image, String click, boolean launchFirst) {
            this.pkg = pkg;
            this.uri = uri;
            this.title = title;
            this.subtitle = subtitle;
            this.image = image;
            this.click = click;
            this.launchFirst = launchFirst;
        }
    }

    private ContentSearch() { }

    private static final int TIMEOUT_MS = 8000;

    // ---- YouTube ---------------------------------------------------------------------------------

    static List<Card> youtube(String q, int max) throws IOException {
        return parseYoutube(get("https://www.youtube.com/results?hl=ru&search_query=" + enc(q), null), max);
    }

    private static final Pattern YT_DATA = Pattern.compile("var ytInitialData = (\\{.*?\\});</script>", Pattern.DOTALL);

    static List<Card> parseYoutube(String html, int max) {
        List<Card> out = new ArrayList<>();
        Matcher m = YT_DATA.matcher(html);
        if (!m.find()) return out;
        try {
            walkYoutube(new JSONObject(m.group(1)), out, max);
        } catch (JSONException ignored) {
            // the page changed: no results
        }
        return out;
    }

    private static void walkYoutube(Object node, List<Card> out, int max) {
        if (out.size() >= max) return;
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            JSONObject v = o.optJSONObject("videoRenderer");
            if (v != null && v.has("videoId")) {
                String id = v.optString("videoId");
                String title = runs(v.optJSONObject("title"));
                String channel = runs(v.optJSONObject("ownerText"));
                JSONObject length = v.optJSONObject("lengthText");
                String sub = channel + (length != null ? " · " + length.optString("simpleText", "") : "");
                out.add(new Card(VoiceActions.YOUTUBE, "https://www.youtube.com/watch?v=" + id, title, sub,
                        "https://i.ytimg.com/vi/" + id + "/hqdefault.jpg", null, false));
                return;
            }
            for (java.util.Iterator<String> k = o.keys(); k.hasNext(); ) walkYoutube(o.opt(k.next()), out, max);
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length() && out.size() < max; i++) walkYoutube(a.opt(i), out, max);
        }
    }

    private static String runs(JSONObject text) {
        if (text == null) return "";
        if (text.has("simpleText")) return text.optString("simpleText");
        JSONArray runs = text.optJSONArray("runs");
        StringBuilder b = new StringBuilder();
        for (int i = 0; runs != null && i < runs.length(); i++) b.append(runs.optJSONObject(i).optString("text"));
        return b.toString();
    }

    // ---- Spotify ---------------------------------------------------------------------------------

    private static String spotifyToken;
    private static long spotifyTokenUntil;

    static List<Card> spotify(String id, String secret, String q, int max) throws IOException {
        String token = spotifyToken(id, secret);
        return parseSpotify(get("https://api.spotify.com/v1/search?type=track&limit=" + max + "&q=" + enc(q),
                "Bearer " + token));
    }

    private static synchronized String spotifyToken(String id, String secret) throws IOException {
        if (spotifyToken != null && System.currentTimeMillis() < spotifyTokenUntil) return spotifyToken;
        String basic = Base64.getEncoder().encodeToString((id + ":" + secret).getBytes(StandardCharsets.UTF_8));
        String r = post("https://accounts.spotify.com/api/token", "grant_type=client_credentials", "Basic " + basic);
        try {
            JSONObject o = new JSONObject(r);
            spotifyToken = o.getString("access_token");
            spotifyTokenUntil = System.currentTimeMillis() + (o.optLong("expires_in", 3600) - 60) * 1000L;
            return spotifyToken;
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    /** Albums: the album page opens with focus on Play, which the remote's OK starts (PRESS_OK). */
    static List<Card> spotifyAlbums(String id, String secret, String q, int max) throws IOException {
        String token = spotifyToken(id, secret);
        return parseSpotifyAlbums(get("https://api.spotify.com/v1/search?type=album&limit=" + max + "&q=" + enc(q),
                "Bearer " + token));
    }

    static List<Card> parseSpotifyAlbums(String json) {
        List<Card> out = new ArrayList<>();
        try {
            JSONArray items = new JSONObject(json).getJSONObject("albums").getJSONArray("items");
            for (int i = 0; i < items.length(); i++) {
                JSONObject a = items.optJSONObject(i);
                if (a == null) continue;
                JSONArray artists = a.optJSONArray("artists");
                String artist = artists != null && artists.length() > 0 ? artists.getJSONObject(0).optString("name") : "";
                JSONArray images = a.optJSONArray("images");
                String image = images != null && images.length() > 0
                        ? images.getJSONObject(Math.min(1, images.length() - 1)).optString("url") : null;
                out.add(new Card(VoiceActions.SPOTIFY, "spotify:album:" + a.getString("id"), a.optString("name"),
                        artist, image, InputKeyService.PRESS_OK, false));
            }
        } catch (JSONException ignored) {
            // no albums
        }
        return out;
    }

    static List<Card> parseSpotify(String json) {
        List<Card> out = new ArrayList<>();
        try {
            JSONArray items = new JSONObject(json).getJSONObject("tracks").getJSONArray("items");
            for (int i = 0; i < items.length(); i++) {
                JSONObject t = items.getJSONObject(i);
                JSONArray artists = t.optJSONArray("artists");
                String artist = artists != null && artists.length() > 0 ? artists.getJSONObject(0).optString("name") : "";
                JSONArray images = t.optJSONObject("album") == null ? null : t.getJSONObject("album").optJSONArray("images");
                String image = images != null && images.length() > 0
                        ? images.getJSONObject(Math.min(1, images.length() - 1)).optString("url") : null;
                out.add(new Card(VoiceActions.SPOTIFY, "spotify:track:" + t.getString("id"), t.optString("name"),
                        artist, image, null, false));
            }
        } catch (JSONException ignored) {
            // no tracks
        }
        return out;
    }

    // ---- Deezer ----------------------------------------------------------------------------------

    /**
     * Qobuz has no links or open search API: the card carries the words, and VoiceRun has InputKeyService type them
     * into the app's search and press the first track, or the album (QOBUZ_ALBUM) and its Play. The uri is no link,
     * only "qobuz:track" or "qobuz:album".
     */
    static final String QOBUZ_TRACK = "qobuz:track", QOBUZ_ALBUM = "qobuz:album";

    static Card qobuz(String q, boolean album) {
        return new Card(VoiceActions.QOBUZ, album ? QOBUZ_ALBUM : QOBUZ_TRACK, q, null, null, q, true);
    }

    /** Albums: Deezer's album page opens with focus on Listen, which is pressed. */
    static List<Card> deezerAlbums(String q, int max) throws IOException {
        return parseDeezerAlbums(get("https://api.deezer.com/search/album?limit=" + max + "&q=" + enc(q), null));
    }

    static List<Card> parseDeezerAlbums(String json) {
        List<Card> out = new ArrayList<>();
        try {
            JSONArray data = new JSONObject(json).getJSONArray("data");
            for (int i = 0; i < data.length(); i++) {
                JSONObject a = data.getJSONObject(i);
                String artist = a.optJSONObject("artist") == null ? "" : a.getJSONObject("artist").optString("name");
                out.add(new Card(VoiceActions.DEEZER, "https://www.deezer.com/album/" + a.optLong("id"),
                        a.optString("title"), artist, a.optString("cover_medium", null), "", true));
            }
        } catch (JSONException ignored) {
            // no albums
        }
        return out;
    }

    static List<Card> deezer(String q, int max) throws IOException {
        return parseDeezer(get("https://api.deezer.com/search?limit=" + max + "&q=" + enc(q), null));
    }

    static List<Card> parseDeezer(String json) {
        List<Card> out = new ArrayList<>();
        try {
            JSONArray data = new JSONObject(json).getJSONArray("data");
            for (int i = 0; i < data.length(); i++) {
                JSONObject t = data.getJSONObject(i);
                JSONObject album = t.optJSONObject("album");
                if (album == null) continue;
                String title = t.optString("title");
                String artist = t.optJSONObject("artist") == null ? "" : t.getJSONObject("artist").optString("name");
                out.add(new Card(VoiceActions.DEEZER, "https://www.deezer.com/album/" + album.optLong("id"), title,
                        artist, album.optString("cover_medium", null), title, true));
            }
        } catch (JSONException ignored) {
            // no tracks
        }
        return out;
    }

    // ---- Emby ------------------------------------------------------------------------------------

    static List<Card> emby(String base, String key, String q, int max) throws IOException {
        String b = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        String json = get(b + "/Items?Recursive=true&IncludeItemTypes=Movie,Series,Episode,MusicVideo&Fields=ProductionYear"
                + "&Limit=" + max + "&SearchTerm=" + enc(q) + "&api_key=" + enc(key), null);
        return parseEmby(json, b, key);
    }

    /** The owner's Emby item for a TMDB title ("movie" or "tv" and its id), whatever name the library gives it. */
    static List<Card> embyByTmdb(String base, String key, String tmdbId) throws IOException {
        String b = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return parseEmby(get(b + "/Items?Recursive=true&IncludeItemTypes=Movie,Series&AnyProviderIdEquals=Tmdb."
                + enc(tmdbId) + "&api_key=" + enc(key), null), b, key);
    }

    /** The TMDB id at the end of an ALPAC card's link (alpac://title/movie/603), or null. */
    static String tmdbId(Card alpac) {
        int slash = alpac.uri.lastIndexOf('/');
        return alpac.uri.startsWith("alpac://title/") && slash > 0 ? alpac.uri.substring(slash + 1) : null;
    }

    static List<Card> parseEmby(String json, String base, String key) {
        List<Card> out = new ArrayList<>();
        try {
            JSONArray items = new JSONObject(json).getJSONArray("Items");
            for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.getJSONObject(i);
                String id = it.getString("Id");
                String type = it.optString("Type");
                String sub;
                if ("Episode".equals(type)) {
                    sub = it.optString("SeriesName", "") + (it.has("ParentIndexNumber") ? " · S" + it.optInt("ParentIndexNumber")
                            + ":E" + it.optInt("IndexNumber") : "");
                } else {
                    sub = it.has("ProductionYear") ? String.valueOf(it.optInt("ProductionYear")) : "";
                }
                out.add(new Card(VoiceActions.EMBY, "embyatv://tv.emby.embyatv/displayitem/" + id, it.optString("Name"),
                        sub, base + "/Items/" + id + "/Images/Primary?maxHeight=360&api_key=" + key, "", false));
            }
        } catch (JSONException ignored) {
            // nothing found
        }
        return out;
    }

    // ---- TMDB, for ALPAC ------------------------------------------------------------------------

    static List<Card> tmdb(String key, String q, int max) throws IOException {
        return parseTmdb(get("https://api.themoviedb.org/3/search/multi?language=ru-RU&api_key=" + enc(key)
                + "&query=" + enc(q), null), max);
    }

    static List<Card> parseTmdb(String json, int max) {
        List<Card> out = new ArrayList<>();
        try {
            JSONArray results = new JSONObject(json).getJSONArray("results");
            for (int i = 0; i < results.length() && out.size() < max; i++) {
                JSONObject r = results.getJSONObject(i);
                String type = r.optString("media_type");
                if (!"movie".equals(type) && !"tv".equals(type)) continue;
                String title = r.optString("movie".equals(type) ? "title" : "name");
                String date = r.optString("movie".equals(type) ? "release_date" : "first_air_date", "");
                String poster = r.optString("poster_path", "");
                out.add(new Card(VoiceActions.ALPAC, "alpac://title/" + type + "/" + r.getLong("id"), title,
                        date.length() >= 4 ? date.substring(0, 4) : "",
                        poster.isEmpty() || "null".equals(poster) ? null : "https://image.tmdb.org/t/p/w342" + poster,
                        "", false));
            }
        } catch (JSONException ignored) {
            // nothing found
        }
        return out;
    }

    // ---- HTTP ------------------------------------------------------------------------------------

    static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            return s;
        }
    }

    static String get(String url, String auth) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setRequestProperty("User-Agent", "Mozilla/5.0");
            c.setRequestProperty("Accept-Language", "ru");
            if (auth != null) c.setRequestProperty("Authorization", auth);
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            return read(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    private static String post(String url, String form, String auth) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            if (auth != null) c.setRequestProperty("Authorization", auth);
            try (OutputStream out = c.getOutputStream()) {
                out.write(form.getBytes(StandardCharsets.UTF_8));
            }
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            return read(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        try (InputStream s = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[16384];
            for (int n; (n = s.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
}
