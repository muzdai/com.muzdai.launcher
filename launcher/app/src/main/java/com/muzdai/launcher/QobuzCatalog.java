package com.muzdai.launcher;

import android.content.Context;
import android.content.res.Resources;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The Qobuz catalogue without signing in (www.qobuz.com/api.json/0.2): the track a request means, or the tracks of the
 * album it means, as the JSON the Qobuz Bridge module turns into Qobuz's own queue items. The app id is Qobuz's own,
 * read from the installed Qobuz (never stored here). album/get needs a signed-in user, so an album's tracks come
 * from a track search for "artist album", filtered by the album's id and put in disc and track order.
 */
final class QobuzCatalog {
    private QobuzCatalog() { }

    private static final String API = "https://www.qobuz.com/api.json/0.2/";

    /** Qobuz's app id from its resources, or null when Qobuz is missing or changed. */
    static String appId(Context ctx) {
        try {
            Resources r = ctx.getPackageManager().getResourcesForApplication(VoiceActions.QOBUZ);
            int id = r.getIdentifier("app_id", "string", VoiceActions.QOBUZ);
            if (id == 0) android.util.Log.w("QobuzBridge", "Qobuz has no app_id resource");
            return id == 0 ? null : r.getString(id);
        } catch (Exception e) {
            android.util.Log.w("QobuzBridge", "Qobuz's app id: " + e);
            return null;
        }
    }

    /** The one track the words mean, as a one-item list; empty when none. Blocking. */
    static List<JSONObject> track(String appId, String query) throws IOException {
        JSONArray items = items(ContentSearch.get(API + "track/search?limit=20&query=" + ContentSearch.enc(query)
                + "&app_id=" + ContentSearch.enc(appId), null), "tracks");
        JSONObject best = bestTrack(items, query);
        List<JSONObject> out = new ArrayList<>();
        if (best != null) out.add(bridgeTrack(best, null));
        return out;
    }

    /** The tracks of the album the words mean, in order; empty when none. Blocking. */
    static List<JSONObject> album(String appId, String query) throws IOException {
        JSONArray albums = items(ContentSearch.get(API + "album/search?limit=20&query=" + ContentSearch.enc(query)
                + "&app_id=" + ContentSearch.enc(appId), null), "albums");
        JSONObject album = bestAlbum(albums, query);
        if (album == null) return new ArrayList<>();
        String words = artist(album) + " " + album.optString("title");
        JSONArray tracks = items(ContentSearch.get(API + "track/search?limit=500&query=" + ContentSearch.enc(words)
                + "&app_id=" + ContentSearch.enc(appId), null), "tracks");
        return albumTracks(tracks, album);
    }

    // ---- choosing (pure, tested) -----------------------------------------------------------------

    static JSONArray items(String json, String kind) {
        try {
            JSONArray a = new JSONObject(json).getJSONObject(kind).getJSONArray("items");
            return a;
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    /**
     * The track whose title (without "(Live …)") is in the words, best also its artist; the longer title on a tie of
     * matches ("Wish You Were Here" over a track of that album), else Qobuz's order. The first track when
     * no title matches.
     */
    static JSONObject bestTrack(JSONArray items, String query) {
        JSONObject best = null, first = null;
        int bestScore = -1;
        for (int i = 0; i < items.length(); i++) {
            JSONObject t = items.optJSONObject(i);
            if (t == null) continue;   // "streamable" is false for everything without a signed-in user: not a sign
            if (first == null) first = t;
            String title = t.optString("title");
            if (!InputKeyService.titleInQuery(title, query)) continue;
            // Title length first, then the artist, then a plain version ("Live", "Remix" only when asked for).
            String version = t.isNull("version") ? "" : t.optString("version");
            boolean plainOrAsked = version.isEmpty() || InputKeyService.titleInQuery(version, query);
            int score = title.replaceAll("\\s*[(\\[].*$", "").trim().length() * 4
                    + (InputKeyService.titleInQuery(artist(t), query) ? 2 : 0) + (plainOrAsked ? 1 : 0);
            if (score > bestScore) {
                best = t;
                bestScore = score;
            }
        }
        return best != null ? best : first;
    }

    /** The album whose title is in the words, best also its artist; else the first. */
    static JSONObject bestAlbum(JSONArray items, String query) {
        JSONObject titleOnly = null, first = null;
        for (int i = 0; i < items.length(); i++) {
            JSONObject a = items.optJSONObject(i);
            if (a == null) continue;
            if (first == null) first = a;
            if (!InputKeyService.titleInQuery(a.optString("title"), query)) continue;
            if (InputKeyService.titleInQuery(artist(a), query)) return a;
            if (titleOnly == null) titleOnly = a;
        }
        return titleOnly != null ? titleOnly : first;
    }

    /** The search's tracks that belong to the album, by disc and track number. */
    static List<JSONObject> albumTracks(JSONArray tracks, JSONObject album) {
        String id = album.optString("id");
        List<JSONObject> mine = new ArrayList<>();
        for (int i = 0; i < tracks.length(); i++) {
            JSONObject t = tracks.optJSONObject(i);
            JSONObject a = t == null ? null : t.optJSONObject("album");
            if (a != null && id.equals(a.optString("id"))) mine.add(t);
        }
        Collections.sort(mine, (x, y) -> {
            int d = Integer.compare(x.optInt("media_number", 1), y.optInt("media_number", 1));
            return d != 0 ? d : Integer.compare(x.optInt("track_number"), y.optInt("track_number"));
        });
        List<JSONObject> out = new ArrayList<>();
        for (JSONObject t : mine) out.add(bridgeTrack(t, album));
        return out;
    }

    private static String artist(JSONObject trackOrAlbum) {
        JSONObject p = trackOrAlbum.optJSONObject("performer");
        if (p == null) p = trackOrAlbum.optJSONObject("artist");
        if (p == null && trackOrAlbum.optJSONObject("album") != null) p = trackOrAlbum.optJSONObject("album").optJSONObject("artist");
        return p == null ? "" : p.optString("name");
    }

    /** A catalogue track as the module's JSON (QueueItems in Qobuz Bridge). */
    static JSONObject bridgeTrack(JSONObject t, JSONObject albumFallback) {
        JSONObject album = t.optJSONObject("album") != null ? t.optJSONObject("album") : albumFallback;
        JSONObject out = new JSONObject();
        try {
            out.put("id", String.valueOf(t.opt("id")));
            put(out, "title", t.optString("title", null));
            put(out, "version", t.isNull("version") ? null : t.optString("version", null));
            put(out, "isrc", t.optString("isrc", null));
            out.put("duration", t.optLong("duration"));
            out.put("explicit", t.optBoolean("parental_warning"));
            JSONObject performer = t.optJSONObject("performer");
            if (performer == null && album != null) performer = album.optJSONObject("artist");
            if (performer != null) {
                put(out, "artist", performer.optString("name", null));
                put(out, "artistId", performer.has("id") ? String.valueOf(performer.opt("id")) : null);
            }
            if (album != null) {
                put(out, "album", album.optString("title", null));
                put(out, "albumVersion", album.isNull("version") ? null : album.optString("version", null));
                put(out, "albumId", album.optString("id", null));
                JSONObject image = album.optJSONObject("image");
                if (image != null) put(out, "albumArt", image.optString("large", image.optString("small", null)));
                JSONObject genre = album.optJSONObject("genre");
                if (genre != null) {
                    put(out, "genreId", genre.has("id") ? String.valueOf(genre.opt("id")) : null);
                    put(out, "genreName", genre.optString("name", null));
                }
                JSONObject label = album.optJSONObject("label");
                if (label != null && label.has("id")) out.put("labelId", String.valueOf(label.opt("id")));
                put(out, "releaseDate", album.optString("release_date_original", null));
            }
        } catch (JSONException ignored) {
            // a field less
        }
        return out;
    }

    /** "Come Together (2019 Mix)" playing for the catalogue's "Come Together"; any title when none was given. */
    static boolean sameTitle(String playing, String wanted) {
        if (wanted == null || wanted.trim().isEmpty()) return true;
        return playing.trim().toLowerCase(java.util.Locale.ROOT)
                .startsWith(wanted.trim().toLowerCase(java.util.Locale.ROOT));
    }

    private static void put(JSONObject o, String k, String v) throws JSONException {
        if (v != null && !v.isEmpty() && !"null".equals(v)) o.put(k, v);
    }
}
