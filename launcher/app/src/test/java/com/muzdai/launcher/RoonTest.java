package com.muzdai.launcher;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Roon's protocol and search, against a fake browse tree shaped like the owner's Roon Core 2.75 answers. */
public class RoonTest {

    /** A row of a browse list; rows with children open to a list, rows without are actions. */
    private static final class Node {
        final String title, subtitle, hint;
        final List<Node> children = new ArrayList<>();
        /** The list a row opens is an action list ("Play Now", "Queue"…). */
        boolean actionList;

        Node(String title, String subtitle, String hint) {
            this.title = title;
            this.subtitle = subtitle;
            this.hint = hint;
        }

        Node add(Node n) {
            children.add(n);
            return this;
        }
    }

    private static Node actions(String title, String... names) {
        Node n = new Node(title, null, "action_list");
        n.actionList = true;
        for (String a : names) n.add(new Node(a, null, "action"));
        return n;
    }

    private static Node track(String title, String artists) {
        // A track row opens to a one-row list of itself, which opens to its actions.
        Node inner = actions(title, "Play Now", "Add Next", "Queue", "Start Radio");
        Node outer = new Node(title, artists, "action_list");
        outer.add(inner);
        return outer;
    }

    private static Node album(String title, String artist) {
        Node page = new Node(title, artist, "list");
        page.add(actions("Play Album", "Play Now", "Add Next", "Queue", "Start Radio"));
        page.add(track("1-1 " + title + " opener", artist));
        return page;
    }

    private static final class FakeRoon implements RoonSearch.Browser {
        final Node root;
        final Map<String, Node> keys = new HashMap<>();
        Node current;
        int session;
        final List<String> ran = new ArrayList<>();

        FakeRoon(Node root) {
            this.root = root;
        }

        @Override public JSONObject browse(JSONObject args) {
            try {
                return browseOrThrow(args);
            } catch (org.json.JSONException e) {
                throw new IllegalStateException(e);
            }
        }

        private JSONObject browseOrThrow(JSONObject args) throws org.json.JSONException {
            Node n;
            if (args.has("input")) {
                session++;   // a new search: keys of the last one are gone
                keys.clear();
                n = root;
            } else {
                n = keys.get(args.optString("item_key"));
                if (n == null) throw new IllegalStateException("stale key " + args.optString("item_key"));
            }
            JSONObject r = new JSONObject();
            if (n.children.isEmpty()) {
                // Roon runs the action and answers with the list one level up, as Play Now does.
                ran.add(path(n));
                Node up = parents.get(parents.get(n));
                if (up == null) return r.put("action", "message").put("message", "ok");
                current = up;
                return r.put("action", "list").put("list", new JSONObject().put("title", up.title)
                        .put("count", up.children.size()));
            }
            current = n;
            JSONObject list = new JSONObject().put("title", n.title).put("count", n.children.size());
            if (n.actionList) list.put("hint", "action_list");
            return r.put("action", "list").put("list", list);
        }

        private final Map<Node, Node> parents = new HashMap<>();

        private String path(Node n) {
            Node p = parents.get(n);
            return (p == null ? "" : p.title + " > ") + n.title;
        }

        @Override public JSONArray load(int count) {
            try {
                return loadOrThrow(count);
            } catch (org.json.JSONException e) {
                throw new IllegalStateException(e);
            }
        }

        private JSONArray loadOrThrow(int count) throws org.json.JSONException {
            JSONArray a = new JSONArray();
            for (int i = 0; i < current.children.size() && i < count; i++) {
                Node c = current.children.get(i);
                String key = session + ":" + keys.size();
                keys.put(key, c);
                parents.put(c, current);
                JSONObject o = new JSONObject().put("title", c.title).put("item_key", key).put("hint", c.hint);
                if (c.subtitle != null) o.put("subtitle", c.subtitle);
                a.put(o);
            }
            return a;
        }
    }

    private static Node category(String title, Node... items) {
        Node c = new Node(title, items.length + (items.length == 1 ? " Result" : " Results"), "list");
        for (Node i : items) c.add(i);
        return c;
    }

    private static Node pinkFloyd() {
        Node artist = new Node("Pink Floyd", "45 Albums", "list");
        artist.add(actions("Play Artist", "Shuffle", "Start Radio"));
        artist.add(album("Animals", "Pink Floyd"));
        return artist;
    }

    private static Node root() {
        Node root = new Node("Search", null, "list");
        root.add(track("Come Together (2019 mix)", "The Beatles, John Lennon, Paul McCartney"));
        root.add(category("Artists", pinkFloyd(), new Node("Atom Pink Floyd Tribute", "0 Albums", "list"),
                new Node("Come Together", "0 Albums", "list")));   // a name with nothing to play
        root.add(category("Albums", album("Abbey Road", "The Beatles"),
                album("The Other Side Of Abbey Road", "[[226682|George Benson]]")));
        root.add(category("Playlists", new Node("REDISCOVER Metallica", "31 Tracks", "list")
                .add(actions("Play Playlist", "Play Now", "Shuffle", "Add Next", "Queue", "Start Radio"))));
        root.add(category("Tracks",
                track("Come Together (2019 mix)", "The Beatles, John Lennon, Paul McCartney"),
                track("Come Together", "The Beatles, John Lennon, Paul McCartney"),
                track("Come Together", "Aerosmith, John Lennon, Paul McCartney"),
                track("Time", "Pink Floyd")));
        return root;
    }

    @Test public void trackPlainVersionFirstAndArtistNamed() throws Exception {
        FakeRoon r = new FakeRoon(root());
        RoonSearch.Result res = RoonSearch.play(r, "come together", "");
        assertEquals("Come Together", res.title);
        assertEquals("The Beatles, John Lennon, Paul McCartney", res.subtitle);
        assertEquals(List.of("Come Together > Play Now"), r.ran);

        r = new FakeRoon(root());
        assertEquals("Aerosmith, John Lennon, Paul McCartney", RoonSearch.play(r, "aerosmith come together", "").subtitle);
        r = new FakeRoon(root());
        assertEquals("Come Together (2019 mix)", RoonSearch.play(r, "come together 2019 mix", "").title);
    }

    @Test public void artistNamedExactlyIsShuffled() throws Exception {
        FakeRoon r = new FakeRoon(root());
        assertEquals("Pink Floyd", RoonSearch.play(r, "Pink Floyd", "").title);
        assertEquals(List.of("Play Artist > Shuffle"), r.ran);
        r = new FakeRoon(root());
        assertEquals("Time", RoonSearch.play(r, "pink floyd time", "").title);   // not the artist: a track of theirs
    }

    @Test public void albumAndPlaylistFromTheFirstTrack() throws Exception {
        FakeRoon r = new FakeRoon(root());
        RoonSearch.Result res = RoonSearch.play(r, "альбом abbey road", "");
        assertEquals("Abbey Road", res.title);
        assertEquals(List.of("Play Album > Play Now"), r.ran);

        r = new FakeRoon(root());
        res = RoonSearch.play(r, "the other side of abbey road", "album");
        assertEquals("The Other Side Of Abbey Road", res.title);
        assertEquals("George Benson", res.subtitle);   // without Roon's link markup

        r = new FakeRoon(root());
        assertEquals("REDISCOVER Metallica", RoonSearch.play(r, "плейлист rediscover metallica", "").title);
        assertEquals(List.of("Play Playlist > Play Now"), r.ran);
    }

    @Test public void nothingMatchingFallsToTheTopHit() throws Exception {
        FakeRoon r = new FakeRoon(root());
        assertEquals("Come Together (2019 mix)", RoonSearch.play(r, "битлз что-нибудь", "").title);
        FakeRoon empty = new FakeRoon(new Node("Search", null, "list").add(new Node("x", null, "list")));
        empty.root.children.clear();
        assertNull(RoonSearch.play(empty, "nothing", ""));
    }

    @Test public void kindWords() throws Exception {
        assertArrayEquals(new String[]{"album", "Abbey Road"}, RoonSearch.kindOf("альбом Abbey Road"));
        assertArrayEquals(new String[]{"playlist", "Утро"}, RoonSearch.kindOf("плейлист Утро"));
        assertArrayEquals(new String[]{"track", "Time"}, RoonSearch.kindOf("песню Time"));
        assertArrayEquals(new String[]{"artist", "Metallica"}, RoonSearch.kindOf("группу Metallica"));
        assertArrayEquals(new String[]{"", "Come Together"}, RoonSearch.kindOf("Come Together"));
        assertEquals("Sam Smith", RoonSearch.plain("[[46970|Sam Smith]]"));
        assertTrue(RoonSearch.sameName("The Beatles", "beatles"));
    }

    @Test public void mooMessagesBothWays() throws Exception {
        byte[] req = Moo.request(7, "com.roonlabs.transport:2/control", new JSONObject().put("control", "play"));
        String s = new String(req, StandardCharsets.UTF_8);
        assertTrue(s.startsWith("MOO/1 REQUEST com.roonlabs.transport:2/control\nRequest-Id: 7\n"));
        assertTrue(s.contains("Content-Type: application/json\n"));
        assertTrue(s.endsWith("\n\n{\"control\":\"play\"}"));

        Moo m = Moo.parse(("MOO/1 COMPLETE Success\nContent-Type: application/json\nRequest-Id: 0\n"
                + "Content-Length: 22\n\n{\"display_name\":\"rock\"}").getBytes(StandardCharsets.UTF_8));
        assertEquals("COMPLETE", m.verb);
        assertEquals("Success", m.name);
        assertEquals(0, m.requestId);
        assertEquals("rock", m.body.getString("display_name"));

        Moo ping = Moo.parse("MOO/1 REQUEST com.roonlabs.ping:1/ping\nRequest-Id: 3\n\n".getBytes(StandardCharsets.UTF_8));
        assertEquals("REQUEST", ping.verb);
        assertEquals(3, ping.requestId);
        assertNull(ping.body);
        assertNull(Moo.parse("hello".getBytes(StandardCharsets.UTF_8)));
    }

    @Test public void zoneOfOutput() throws Exception {
        JSONArray zones = new JSONArray().put(new JSONObject().put("zone_id", "z1").put("outputs",
                new JSONArray().put(new JSONObject().put("output_id", "o1"))));
        assertEquals("z1", RoonApi.zoneOf(zones, "o1").getString("zone_id"));
        assertNull(RoonApi.zoneOf(zones, "o2"));
    }

    @Test public void eversoloInputs() throws Exception {
        Eversolo.Inputs in = Eversolo.parseInputs("{\"inputIndex\":7,\"inputData\":[{\"name\":\"Internal player\","
                + "\"tag\":\"XMOS\"},{\"name\":\"Bluetooth In\",\"tag\":\"BT\"},{},{},{},{},{},"
                + "{\"name\":\"ARC In\",\"tag\":\"EARC\"}]}");
        assertEquals("EARC", in.currentTag());
        assertEquals(0, in.indexOf(Eversolo.INTERNAL));
        assertEquals(7, in.indexOf(Eversolo.ARC));
        assertEquals(-1, in.indexOf("USB"));
    }

    @Test public void spokenNamesOfRoon() throws Exception {
        java.util.Map<String, String> none = new HashMap<>();
        for (String said : new String[]{"включи come together на руне", "включи come together в руне",
                "включи come together через рун", "включи come together по руну", "включи come together на роне",
                "включи come together через роон", "включи come together на рооне", "включи come together на roon"}) {
            VoiceCommand c = VoiceCommand.parse(said, w -> AppNames.find(w, none) != null);
            assertEquals(said, VoiceCommand.Kind.IN_APP, c.kind);
            assertEquals(said, "come together", c.query);
            assertEquals(said, VoiceActions.ROON, AppNames.find(c.app, none));
        }
        assertEquals(VoiceActions.ROON, AppNames.find("рун аудио", none));
        // A song with a preposition in it is not sent anywhere: "по" needs an app after it.
        assertEquals(VoiceCommand.Kind.SEARCH,
                VoiceCommand.parse("включи песни по заявкам", w -> AppNames.find(w, none) != null).kind);
    }

    @Test public void whatPlaysIsWhatWasStarted() throws Exception {
        RoonSearch.Result t = RoonSearch.play(new FakeRoon(root()), "come together", "");
        assertEquals("track", t.kind);
        assertTrue(t.shows("Come Together", "The Beatles", "Abbey Road"));
        assertTrue(!t.shows("Shine on You Crazy Diamond", "Pink Floyd", "Wish You Were Here"));
        RoonSearch.Result a = RoonSearch.play(new FakeRoon(root()), "альбом abbey road", "");
        assertTrue(a.shows("Come Together (2019 mix)", "The Beatles", "Abbey Road (2019 mix)"));
        RoonSearch.Result artist = RoonSearch.play(new FakeRoon(root()), "pink floyd", "");
        assertTrue(artist.shows("Time", "Pink Floyd", "The Dark Side of the Moon"));
        assertTrue(!artist.shows("Come Together", "The Beatles", "Abbey Road"));
    }
}
