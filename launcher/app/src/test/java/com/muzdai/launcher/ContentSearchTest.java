package com.muzdai.launcher;

import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ContentSearchTest {

    @Test public void youtubeReadsVideosFromThePagesData() {
        String html = "<script>var ytInitialData = {\"contents\":{\"list\":[{\"channelRenderer\":{\"title\":{\"simpleText\":\"x\"}}},"
                + "{\"videoRenderer\":{\"videoId\":\"LpwabPQW4p4\",\"title\":{\"runs\":[{\"text\":\"Joe Cocker - With A Little Help\"}]},"
                + "\"ownerText\":{\"runs\":[{\"text\":\"Joe Cocker\"}]},\"lengthText\":{\"simpleText\":\"8:48\"}}},"
                + "{\"videoRenderer\":{\"videoId\":\"rUVEFkjqiEE\",\"title\":{\"runs\":[{\"text\":\"Woodstock\"}]}}}]}};</script>";
        List<ContentSearch.Card> cards = ContentSearch.parseYoutube(html, 5);
        assertEquals(2, cards.size());
        ContentSearch.Card c = cards.get(0);
        assertEquals("https://www.youtube.com/watch?v=LpwabPQW4p4", c.uri);
        assertEquals("Joe Cocker - With A Little Help", c.title);
        assertEquals("Joe Cocker · 8:48", c.subtitle);
        assertEquals("https://i.ytimg.com/vi/LpwabPQW4p4/hqdefault.jpg", c.image);
        assertNull(c.click);
        assertEquals(1, ContentSearch.parseYoutube(html, 1).size());
        assertTrue(ContentSearch.parseYoutube("<html>changed</html>", 5).isEmpty());
    }

    @Test public void spotifyGivesTrackLinks() {
        String json = "{\"tracks\":{\"items\":[{\"id\":\"0YZ3J8xzGwLOg4yEgST1YK\",\"name\":\"With A Little Help From My Friends\","
                + "\"artists\":[{\"name\":\"Joe Cocker\"}],\"album\":{\"images\":[{\"url\":\"big\"},{\"url\":\"mid\"},{\"url\":\"small\"}]}}]}}";
        ContentSearch.Card c = ContentSearch.parseSpotify(json).get(0);
        assertEquals("spotify:track:0YZ3J8xzGwLOg4yEgST1YK", c.uri);
        assertEquals("Joe Cocker", c.subtitle);
        assertEquals("mid", c.image);
        assertFalse(c.launchFirst);
    }

    @Test public void deezerOpensTheAlbumAndClicksTheTrack() {
        String json = "{\"data\":[{\"id\":548862472,\"title\":\"With A Little Help From My Friends\",\"artist\":{\"name\":\"Joe Cocker\"},"
                + "\"album\":{\"id\":72090382,\"cover_medium\":\"cover\"}}]}";
        ContentSearch.Card c = ContentSearch.parseDeezer(json).get(0);
        assertEquals("https://www.deezer.com/album/72090382", c.uri);
        assertEquals("With A Little Help From My Friends", c.click);
        assertTrue(c.launchFirst);
    }

    @Test public void embyItemsOpenTheirCardAndPressPlay() {
        String json = "{\"Items\":[{\"Id\":\"43111\",\"Type\":\"Series\",\"Name\":\"Джентльмены\",\"ProductionYear\":2024},"
                + "{\"Id\":\"43113\",\"Type\":\"Episode\",\"Name\":\"Путь к престолу\",\"SeriesName\":\"Джентльмены\","
                + "\"ParentIndexNumber\":2,\"IndexNumber\":1}]}";
        List<ContentSearch.Card> cards = ContentSearch.parseEmby(json, "http://emby:8096", "k");
        assertEquals("embyatv://tv.emby.embyatv/displayitem/43111", cards.get(0).uri);
        assertEquals("2024", cards.get(0).subtitle);
        assertEquals("http://emby:8096/Items/43111/Images/Primary?maxHeight=360&api_key=k", cards.get(0).image);
        assertEquals("", cards.get(0).click);
        assertEquals("Джентльмены · S2:E1", cards.get(1).subtitle);
    }

    @Test public void tmdbGivesAlpacTitlesForFilmsAndSeriesOnly() {
        String json = "{\"results\":[{\"media_type\":\"person\",\"id\":1,\"name\":\"Keanu\"},"
                + "{\"media_type\":\"movie\",\"id\":603,\"title\":\"Матрица\",\"release_date\":\"1999-03-30\",\"poster_path\":\"/p.jpg\"},"
                + "{\"media_type\":\"tv\",\"id\":236235,\"name\":\"Джентльмены\",\"first_air_date\":\"2024-03-07\",\"poster_path\":null}]}";
        List<ContentSearch.Card> cards = ContentSearch.parseTmdb(json, 5);
        assertEquals(2, cards.size());
        assertEquals("alpac://title/movie/603", cards.get(0).uri);
        assertEquals("1999", cards.get(0).subtitle);
        assertEquals("https://image.tmdb.org/t/p/w342/p.jpg", cards.get(0).image);
        assertEquals("alpac://title/tv/236235", cards.get(1).uri);
        assertNull(cards.get(1).image);
        assertEquals("236235", ContentSearch.tmdbId(cards.get(1)));
    }

    @Test public void keysFileIgnoresCommentsAndBlanks() {
        Map<String, String> k = VoiceKeys.parse("# keys\nEMBY_URL = http://e:8096\n\ntmdb_key=abc\nbroken\nspotify_id=\n");
        assertEquals("http://e:8096", k.get("emby_url"));
        assertEquals("abc", k.get("tmdb_key"));
        assertFalse(k.containsKey("spotify_id"));
        assertEquals(2, k.size());
    }
}
