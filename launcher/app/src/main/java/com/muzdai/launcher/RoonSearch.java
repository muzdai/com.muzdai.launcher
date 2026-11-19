package com.muzdai.launcher;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds what a request means in Roon's search (the owner's library and every service linked in Roon: Qobuz, TIDAL…)
 * and plays it, the way the Roon app does: the search lists a top hit and the categories Artists, Albums, Tracks,
 * Playlists…; an item opens to its page ("Play Album", "Play Artist", "Play Playlist" first, or a track's own row),
 * which opens to its actions ("Play Now", "Shuffle", "Add Next", "Queue", "Start Radio"). Pure over {@link Browser},
 * tested on the JVM with a fake one.
 */
final class RoonSearch {
    private RoonSearch() { }

    /** Roon's browse service in the "search" hierarchy for one zone (RoonApi.browser). */
    interface Browser {
        /** browse with these arguments (input, item_key, pop_all…). */
        JSONObject browse(JSONObject args) throws IOException;

        /** The items of the list the last browse opened, from the first. */
        JSONArray load(int count) throws IOException;
    }

    /** What was started. */
    static final class Result {
        final String title, subtitle;
        /** What was played (TRACK, ALBUM, ARTIST, PLAYLIST), or null for Roon's top hit. */
        String kind;

        Result(String title, String subtitle) {
            this.title = title;
            this.subtitle = subtitle;
        }

        /**
         * Whether a zone's now playing lines (title, artist, album) are what was started: the track's title, the
         * album's, the artist's name; true for a playlist or a top hit, which say nothing checkable.
         */
        boolean shows(String line1, String line2, String line3) {
            String t = title.replaceAll("\\s*[(\\[].*$", "").trim().toLowerCase(Locale.ROOT);
            if (t.isEmpty() || kind == null || PLAYLIST.equals(kind)) return true;
            String line = TRACK.equals(kind) ? line1 : ALBUM.equals(kind) ? line3 : line2;
            return line != null && line.toLowerCase(Locale.ROOT).contains(t);
        }
    }

    static final String TRACK = "track", ALBUM = "album", ARTIST = "artist", PLAYLIST = "playlist";

    private static final Pattern KIND_WORDS = Pattern.compile("^(альбомы?|пластинку|диск|album|"
            + "плейлист|плей лист|playlist|подборку|"
            + "песню|трек|композицию|song|track|"
            + "исполнителя|артиста|группу|песни группы|песни|музыку группы|artist)\\s+(.+)$");

    /** {kind, the words without it}: "альбом abbey road" → {album, "abbey road"}; {"", words} with no kind word. */
    static String[] kindOf(String words) {
        String t = words.trim();
        Matcher m = KIND_WORDS.matcher(t.toLowerCase(Locale.ROOT));
        if (!m.matches()) return new String[]{"", t};
        String w = m.group(1);
        String rest = t.substring(t.length() - m.group(2).length()).trim();
        if (w.startsWith("альбом") || w.equals("пластинку") || w.equals("диск") || w.equals("album")) {
            return new String[]{ALBUM, rest};
        }
        if (w.startsWith("пле") || w.equals("playlist") || w.equals("подборку")) return new String[]{PLAYLIST, rest};
        if (w.equals("песню") || w.equals("трек") || w.equals("композицию") || w.equals("song") || w.equals("track")) {
            return new String[]{TRACK, rest};
        }
        return new String[]{ARTIST, rest};
    }

    /**
     * Plays what {@code query} means. {@code kind}: track, album, artist, playlist, or anything else for "whatever
     * fits": an artist named exactly so (shuffled), else a track whose title is in the words, an album, a playlist,
     * else Roon's top hit. Null when the search finds nothing to play.
     */
    static Result play(Browser b, String query, String kind) throws IOException {
        return play(b, query, kind, null);
    }

    /**
     * As play, running {@code action} ("Add Next", "Queue", "Start Radio") in place of playing now; null when what was
     * found has no such action.
     */
    static Result play(Browser b, String query, String kind, String action) throws IOException {
        String[] k = kindOf(query);
        if (!k[0].isEmpty()) {
            kind = k[0];
            query = k[1];
        }
        switch (kind == null ? "" : kind) {
            case TRACK: return fromCategory(b, query, "Tracks", TRACK, true, action);
            case ALBUM: return fromCategory(b, query, "Albums", ALBUM, true, action);
            case ARTIST: return fromCategory(b, query, "Artists", ARTIST, true, action);
            case PLAYLIST: return fromCategory(b, query, "Playlists", PLAYLIST, true, action);
            default: {
                for (String[] c : new String[][]{{"Artists", ARTIST}, {"Tracks", TRACK}, {"Albums", ALBUM},
                        {"Playlists", PLAYLIST}}) {
                    Result r = fromCategory(b, query, c[0], c[1], false, action);
                    if (r != null) return r;
                }
                return topHit(b, query, action);
            }
        }
    }

    /** The search's top level, freshly searched (item keys hold only until the next browse elsewhere). */
    private static JSONArray search(Browser b, String query) throws IOException {
        JSONObject r = b.browse(args("input", query, "pop_all", true));
        if (!"list".equals(r.optString("action"))) return new JSONArray();
        return b.load(20);
    }

    /**
     * The best item of one category that matches the words, played; with {@code orFirst} the category's first item when
     * none matches. Null when the category is missing or nothing fits.
     */
    private static Result fromCategory(Browser b, String query, String category, String kind, boolean orFirst,
            String action) throws IOException {
        JSONObject cat = byTitle(search(b, query), category);
        if (cat == null) return null;
        b.browse(args("item_key", cat.optString("item_key")));
        JSONArray items = b.load(100);
        JSONObject best = best(items, query, kind);
        if (best == null && orFirst) best = items.optJSONObject(0);
        if (best == null) return null;
        Result r = playFrom(b, best, action != null ? action : ARTIST.equals(kind) ? "Shuffle" : "Play Now");
        if (r != null) r.kind = kind;
        return r;
    }

    /** Roon's top hit (the first row that is not a category), else nothing. */
    private static Result topHit(Browser b, String query, String action) throws IOException {
        JSONArray top = search(b, query);
        for (int i = 0; i < top.length(); i++) {
            JSONObject it = top.optJSONObject(i);
            if (it == null || isCategory(it)) continue;
            return playFrom(b, it, action != null ? action : "Play Now");
        }
        return null;
    }

    private static boolean isCategory(JSONObject it) {
        String s = it.optString("subtitle", "");
        return "list".equals(it.optString("hint")) && s.matches("\\d+ Results?");
    }

    /** The item that matches, by kind; null when none does. */
    static JSONObject best(JSONArray items, String query, String kind) {
        JSONObject best = null;
        int bestScore = -1;
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it == null) continue;
            String title = it.optString("title");
            String artists = plain(it.optString("subtitle", ""));
            int score;
            if (ARTIST.equals(kind)) {
                // Roon lists names with nothing to play ("Wish You Were Here — 0 Albums"): their Shuffle does nothing.
                if (!sameName(title, query) || artists.startsWith("0 ")) continue;
                score = 1;
            } else {
                if (!InputKeyService.titleInQuery(title, query)) continue;
                // A longer title first (the song over a word of it), then its artist named, then a plain version
                // ("Live", "2019 mix" only when asked for).
                String version = title.matches(".*[(\\[].*")
                        ? title.replaceAll("^[^(\\[]*", "").replaceAll("[()\\[\\]]", " ").trim() : "";
                boolean plainOrAsked = version.isEmpty() || InputKeyService.titleInQuery(version, query);
                score = title.replaceAll("\\s*[(\\[].*$", "").trim().length() * 4
                        + (artistNamed(artists, query) ? 2 : 0) + (plainOrAsked ? 1 : 0);
            }
            if (score > bestScore) {
                best = it;
                bestScore = score;
            }
        }
        return best;
    }

    /** "Pink Floyd" for "pink floyd", "The Beatles" for "beatles". */
    static boolean sameName(String name, String query) {
        String n = squash(name).replaceFirst("^the ", ""), q = squash(query).replaceFirst("^the ", "");
        return !n.isEmpty() && n.equals(q);
    }

    private static boolean artistNamed(String artists, String query) {
        for (String a : artists.split(",|&| ft | and ")) {
            if (InputKeyService.titleInQuery(a.trim(), query)) return true;
        }
        return false;
    }

    /** Roon's link markup in subtitles: "[[46970|Sam Smith]]" → "Sam Smith". */
    static String plain(String s) {
        return s.replaceAll("\\[\\[\\d+\\|([^\\]]*)\\]\\]", "$1");
    }

    /**
     * Opens the item down to its actions and runs one: the page's "Play …" row (Play Album, Play Artist, Play
     * Playlist) or its single row (a track, an album's first version), then {@code prefer}, else "Play Now", else the
     * first action. Null when it never reaches an action.
     */
    static Result playFrom(Browser b, JSONObject item, String prefer) throws IOException {
        Result picked = new Result(item.optString("title"), plain(item.optString("subtitle", "")));
        JSONObject r = b.browse(args("item_key", item.optString("item_key")));
        for (int depth = 0; depth < 8; depth++) {
            String action = r.optString("action");
            if ("message".equals(action)) {
                if (r.optBoolean("is_error")) throw new IOException("Roon: " + r.optString("message"));
                return picked;
            }
            if (!"list".equals(action)) return "none".equals(action) ? picked : null;
            JSONObject list = r.optJSONObject("list");
            JSONArray items = b.load(100);
            JSONObject next = null;
            if (list != null && "action_list".equals(list.optString("hint"))) {
                // Playing may fall back to another way of playing; queueing or radio must be what was asked.
                boolean exact = !"Play Now".equals(prefer) && !"Shuffle".equals(prefer);
                for (String want : exact ? new String[]{prefer} : new String[]{prefer, "Play Now", "Shuffle"}) {
                    next = byTitle(items, want);
                    if (next != null) break;
                }
                if (next == null && exact) return null;
                if (next == null) next = items.optJSONObject(0);
            } else {
                for (int i = 0; i < items.length() && next == null; i++) {
                    JSONObject it = items.optJSONObject(i);
                    if (it != null && "action_list".equals(it.optString("hint"))
                            && it.optString("title").startsWith("Play ")) next = it;
                }
                if (next == null) next = items.optJSONObject(0);
            }
            if (next == null) return null;
            r = b.browse(args("item_key", next.optString("item_key")));
            // An action ran: Roon answers with a message, or (Play Now) with the list one level up.
            if ("action".equals(next.optString("hint"))) {
                if ("message".equals(r.optString("action")) && r.optBoolean("is_error")) {
                    throw new IOException("Roon: " + r.optString("message"));
                }
                return picked;
            }
        }
        return null;
    }

    private static JSONObject byTitle(JSONArray items, String title) {
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it != null && title.equalsIgnoreCase(it.optString("title"))) return it;
        }
        return null;
    }

    private static String squash(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    private static JSONObject args(Object... kv) {
        JSONObject o = new JSONObject();
        try {
            for (int i = 0; i + 1 < kv.length; i += 2) o.put((String) kv[i], kv[i + 1]);
        } catch (JSONException ignored) {
            // plain keys and values
        }
        return o;
    }
}
