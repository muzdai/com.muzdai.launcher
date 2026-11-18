package com.muzdai.launcher;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

public class QobuzCatalogTest {
    private static JSONObject track(long id, String title, String artist, String albumId, int disc, int no) throws Exception {
        return new JSONObject().put("id", id).put("title", title).put("streamable", true)
                .put("media_number", disc).put("track_number", no).put("duration", 200)
                .put("performer", new JSONObject().put("name", artist).put("id", 1))
                .put("album", new JSONObject().put("id", albumId).put("title", "Album " + albumId)
                        .put("image", new JSONObject().put("large", "L"))
                        .put("genre", new JSONObject().put("id", 119).put("name", "Rock")));
    }

    @Test public void theTrackNamedInTheRequestWins() throws Exception {
        JSONArray items = new JSONArray()
                .put(track(1, "Shine On You Crazy Diamond (Pts. 1-5)", "Pink Floyd", "a", 1, 1))
                .put(track(2, "Wish You Were Here", "Pink Floyd", "a", 1, 4))
                .put(track(3, "Wish You Were Here", "Some Cover Band", "b", 1, 1));
        assertEquals(2, QobuzCatalog.bestTrack(items, "Pink Floyd Wish You Were Here").getLong("id"));
        assertEquals(1, QobuzCatalog.bestTrack(items, "nothing like it").getLong("id"));
        JSONArray versions = new JSONArray()
                .put(track(5, "Time ", "Pink Floyd", "c", 1, 1).put("version", "Live"))
                .put(track(6, "Time", "Pink Floyd", "d", 1, 4).put("version", JSONObject.NULL));
        assertEquals(6, QobuzCatalog.bestTrack(versions, "Pink Floyd Time").getLong("id"));
        assertEquals(5, QobuzCatalog.bestTrack(versions, "Pink Floyd Time live").getLong("id"));
    }

    @Test public void playingTitleMatchesTheCatalogueOne() {
        org.junit.Assert.assertTrue(QobuzCatalog.sameTitle("Come Together (2019 Mix)", "Come Together"));
        org.junit.Assert.assertTrue(QobuzCatalog.sameTitle("Time", "Time "));
        org.junit.Assert.assertFalse(QobuzCatalog.sameTitle("Shine On You Crazy Diamond (Pts. 1-5)", "Time"));
    }

    @Test public void albumTracksInDiscAndTrackOrder() throws Exception {
        JSONArray tracks = new JSONArray()
                .put(track(12, "B", "X", "w", 2, 1)).put(track(99, "Other", "X", "z", 1, 1))
                .put(track(11, "A2", "X", "w", 1, 2)).put(track(10, "A1", "X", "w", 1, 1));
        List<JSONObject> out = QobuzCatalog.albumTracks(tracks, new JSONObject().put("id", "w"));
        assertEquals(3, out.size());
        assertEquals("10", out.get(0).getString("id"));
        assertEquals("11", out.get(1).getString("id"));
        assertEquals("12", out.get(2).getString("id"));
        assertEquals("Album w", out.get(0).getString("album"));
        assertEquals("L", out.get(0).getString("albumArt"));
        assertEquals("119", out.get(0).getString("genreId"));
        assertEquals(200, out.get(0).getLong("duration"));
    }
}
