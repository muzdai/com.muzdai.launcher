package com.muzdai.launcher;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.IOException;

/**
 * Plays a request through Roon on the player chosen in settings: checks that Roon sees that player, puts the Eversolo
 * on its own player input first (with the volume the TV was at, so nothing blasts out at night), finds and starts
 * the music (RoonSearch), and makes sure it really plays: the streamer may switch inputs and then sit stopped, so
 * Play is pressed again a couple of times. Then RoonHandoff watches for the TV's own sound coming back.
 */
final class RoonPlay {
    private RoonPlay() { }

    /** "Resume" in place of a kind: no search, Play on the zone. */
    static final String RESUME = "resume";
    private static final long PLAYING_WITHIN_MS = 14000, RETRY_AFTER_MS = 3000, INPUT_WITHIN_MS = 6000, SETTLE_MS = 1500;

    static void play(Context ctx, String query, String kind) {
        Context app = ctx.getApplicationContext();
        new Thread(() -> run(app, query, kind), "roon-play").start();
    }

    /** A button on the now playing screen, a media key, the mini player: as the spoken command. */
    static void button(Context ctx, RoonCommands.Kind kind) {
        play(ctx, "", CMD + kind.name());
    }

    /**
     * The remote's media keys for Roon, while Roon plays from here (RoonHandoff armed) and no app on the TV plays: play,
     * pause, play/pause, next, previous, stop. True when the key was Roon's (both its down and up are taken).
     */
    static boolean mediaKey(Context ctx, android.view.KeyEvent e) {
        RoonCommands.Kind kind;
        switch (e.getKeyCode()) {
            case android.view.KeyEvent.KEYCODE_MEDIA_PLAY: kind = RoonCommands.Kind.PLAY; break;
            case android.view.KeyEvent.KEYCODE_MEDIA_PAUSE: kind = RoonCommands.Kind.PAUSE; break;
            case android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: kind = null; break;
            case android.view.KeyEvent.KEYCODE_MEDIA_NEXT:
            case android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD: kind = RoonCommands.Kind.NEXT; break;
            case android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case android.view.KeyEvent.KEYCODE_MEDIA_REWIND: kind = RoonCommands.Kind.PREVIOUS; break;
            case android.view.KeyEvent.KEYCODE_MEDIA_STOP: kind = RoonCommands.Kind.STOP; break;
            default: return false;
        }
        if (!RoonHandoff.armed() || MediaListener.playingApp(ctx) != null) return false;
        if (e.getAction() == android.view.KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
            if (kind == null) {
                RoonNow.Snapshot s = RoonNow.last();
                kind = s != null && !s.playing() ? RoonCommands.Kind.PLAY : RoonCommands.Kind.PAUSE;
            }
            android.util.Log.i("Roon", "media key: " + kind);
            button(ctx, kind);
            RoonNow.soon();
        }
        return true;
    }

    /** A spoken command (RoonCommands) in place of a kind: "cmd:" + its kind's name. */
    static final String CMD = "cmd:";
    /** Settings (RoonApi.prefs): the 0–100 step of "louder" and "quieter": 0.5, 1 or 2. */
    static final String KEY_STEP = "volume_step";
    static final String[] STEPS = {"0.5", "1", "2"};

    static double step(Context ctx) {
        return RoonCommands.number(RoonApi.prefs(ctx).getString(KEY_STEP, "1"), 1);
    }

    private static void run(Context app, String query, String kind) {
        if (kind != null && kind.startsWith(CMD)) {
            command(app, RoonCommands.Kind.valueOf(kind.substring(CMD.length())), query);
            return;
        }
        playRequest(app, query, kind);
    }

    /** True once: the connection to the core dropped mid-way (another client of the same extension, a restart). */
    private static boolean dropped(IOException e) {
        String m = String.valueOf(e.getMessage());
        return m.contains("not connected") || m.contains("connection closed");
    }

    private static void playRequest(Context app, String query, String kind) {
        for (int attempt = 0; ; attempt++) {
            try {
                playOnce(app, query, kind);
                return;
            } catch (IOException e) {
                if (attempt == 0 && dropped(e)) {
                    android.util.Log.i("Roon", "connection dropped, again: " + e.getMessage());
                    continue;
                }
                android.util.Log.w("Roon", "play: " + e);
                toast(app, app.getString(R.string.roon_failed));
                return;
            }
        }
    }

    /** One go at a request; IOException when the core fails mid-way (playRequest may try once more). */
    private static void playOnce(Context app, String query, String kind) throws IOException {
        String output = RoonApi.output(app);
        if (output == null) {
            toast(app, app.getString(R.string.roon_no_player_set));
            return;
        }
        RoonApi api;
        try {
            api = RoonApi.get(app);
        } catch (RoonApi.NotReady e) {
            toast(app, app.getString(e.notEnabled ? R.string.roon_not_enabled : R.string.roon_unreachable));
            return;
        }
        if (api.zoneOf(output) == null) {
            toast(app, app.getString(R.string.roon_no_player, RoonApi.prefs(app).getString(RoonApi.KEY_OUTPUT_NAME, "?")));
            return;
        }
        toInternalPlayer(app);
        RoonSearch.Result r = null;
        if (RESUME.equals(kind)) {
            api.control(output, "play");
        } else {
            r = RoonSearch.play(api.browser(output), query, kind);
            if (r == null) {
                toast(app, app.getString(R.string.roon_nothing, query));
                return;
            }
            android.util.Log.i("Roon", "started " + r.kind + ": " + r.title + " — " + r.subtitle);
            toast(app, app.getString(R.string.roon_playing, r.subtitle.isEmpty() ? r.title : r.title + " — " + r.subtitle));
        }
        RoonHandoff.arm(app);
        RoonSearch.Result started = r;
        boolean playing = r == null ? keepPlaying(api, output, null, null)
                : keepPlaying(api, output, started, () -> RoonSearch.play(api.browser(output), query, kind));
        if (!playing) toast(app, app.getString(R.string.roon_not_playing));
    }

    /**
     * The Eversolo on its own player (Roon's way in), with the volume of the input it leaves: each input keeps its
     * own volume, and the one the TV was at is what the room is used to. Nothing to do when it is already there, when
     * switching is off, or when the streamer does not answer (Roon still tries).
     */
    static void toInternalPlayer(Context app) {
        if (!Eversolo.enabled(app)) return;
        Eversolo e = new Eversolo(Eversolo.host(app));
        try {
            Eversolo.Inputs in = e.inputs();
            String from = in.currentTag();
            if (Eversolo.INTERNAL.equals(from)) return;
            int volume = e.volume();
            if (!e.setInput(Eversolo.INTERNAL)) return;
            long until = SystemClock.uptimeMillis() + INPUT_WITHIN_MS;
            while (SystemClock.uptimeMillis() < until && !Eversolo.INTERNAL.equals(e.inputs().currentTag())) {
                SystemClock.sleep(300);
            }
            // The streamer reports the old input's volume for a moment after switching: let it settle, then set the
            // volume always and read it back.
            SystemClock.sleep(SETTLE_MS);
            int own = e.volume();
            if (volume >= 0) {
                for (int i = 0; i < 3; i++) {
                    e.setVolume(volume);
                    SystemClock.sleep(400);
                    if (e.volume() == volume) break;
                }
            }
            android.util.Log.i("Roon", "eversolo: " + from + " → " + Eversolo.INTERNAL + ", volume " + own + " → "
                    + e.volume() + " (the TV was at " + volume + ")");
        } catch (IOException ex) {
            android.util.Log.w("Roon", "eversolo: " + ex);
        }
    }

    // ---- spoken commands -------------------------------------------------------------------------

    /** The zone commands go to: the chosen player's when it plays, else any playing zone, else the chosen one's. */
    static JSONObject target(RoonApi api, String output) throws IOException {
        org.json.JSONArray zones = api.zones();
        JSONObject mine = RoonApi.zoneOf(zones, output);
        if (mine != null && "playing".equals(mine.optString("state"))) return mine;
        for (int i = 0; zones != null && i < zones.length(); i++) {
            JSONObject z = zones.optJSONObject(i);
            if (z != null && "playing".equals(z.optString("state"))) return z;
        }
        return mine;
    }

    /**
     * Whether a command that names no Roon is Roon's: its zone plays, or (to resume, or set the volume) stands paused
     * with the Eversolo on Roon's input. Moving and grouping zones need a zone of that name; queueing needs Roon as the
     * music app or Roon playing. Blocking, short.
     */
    static boolean wants(Context ctx, RoonCommands.Cmd c, boolean roonIsMusicApp, boolean otherPlayer) {
        String output = RoonApi.output(ctx);
        if (output == null) return false;
        try {
            RoonApi api = RoonApi.get(ctx, 1500);
            if (c.kind == RoonCommands.Kind.TRANSFER) return zoneNamed(api.zones(), c.arg) != null;
            if (c.kind == RoonCommands.Kind.EVERYWHERE) return true;
            if (c.named) return true;
            JSONObject z = target(api, output);
            String state = z == null ? "" : z.optString("state");
            if ("playing".equals(state)) return true;
            if (otherPlayer) return false;
            if ((c.kind == RoonCommands.Kind.PLAY_NEXT || c.kind == RoonCommands.Kind.QUEUE) && roonIsMusicApp) return true;
            return "paused".equals(state) && (c.kind == RoonCommands.Kind.PLAY || onRoonInput(ctx));
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean onRoonInput(Context ctx) {
        if (!Eversolo.enabled(ctx)) return true;
        try {
            return Eversolo.INTERNAL.equals(new Eversolo(Eversolo.host(ctx)).inputs().currentTag());
        } catch (IOException e) {
            return false;
        }
    }

    /** The zone whose name (or an output's) is the spoken one: "кухня", "на кухне" → "Кухня". */
    static JSONObject zoneNamed(org.json.JSONArray zones, String spoken) {
        if (zones == null || spoken == null) return null;
        String want = squash(spoken);
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < zones.length(); i++) {
                JSONObject z = zones.optJSONObject(i);
                if (z == null) continue;
                if (sameZone(z.optString("display_name"), want, pass == 1)) return z;
                org.json.JSONArray outs = z.optJSONArray("outputs");
                for (int k = 0; outs != null && k < outs.length(); k++) {
                    JSONObject o = outs.optJSONObject(k);
                    if (o != null && sameZone(o.optString("display_name"), want, pass == 1)) return z;
                }
            }
        }
        return null;
    }

    /** Equal; on the loose pass, equal without a Russian case ending of 1–2 letters on either side. */
    private static boolean sameZone(String name, String want, boolean loose) {
        String n = squash(name);
        if (n.isEmpty()) return false;
        if (n.equals(want)) return true;
        if (!loose) return false;
        String a = n.length() > 3 ? n.replaceAll("[аеиоуыэюяйь]{1,2}$", "") : n;
        String b = want.length() > 3 ? want.replaceAll("[аеиоуыэюяйь]{1,2}$", "") : want;
        return a.length() >= 3 && a.equals(b);
    }

    private static String squash(String s) {
        return VoiceCommand.normalize(s).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    /** "Играет Pink Floyd — Time (альбом …)." or the volume, for a question; null when Roon does not answer. */
    static String answer(Context ctx, RoonCommands.Cmd c) {
        String output = RoonApi.output(ctx);
        try {
            RoonApi api = RoonApi.get(ctx, 1500);
            JSONObject z = target(api, output);
            if (z == null) return null;
            if (c.kind == RoonCommands.Kind.WHAT_VOLUME) {
                JSONObject v = volumeOf(z);
                if (v == null) return ctx.getString(R.string.roon_no_volume);
                return ctx.getString(R.string.roon_volume_is, RoonCommands.say(RoonCommands.toScale(
                        v.optDouble("value"), v.optDouble("min"), v.optDouble("max"))));
            }
            JSONObject np = z.optJSONObject("now_playing");
            JSONObject three = np == null ? null : np.optJSONObject("three_line");
            if (three == null || "stopped".equals(z.optString("state"))) return ctx.getString(R.string.roon_plays_nothing);
            String title = three.optString("line1"), artist = three.optString("line2"), album = three.optString("line3");
            String what = artist.isEmpty() ? title : artist + " — " + title;
            return ctx.getString("paused".equals(z.optString("state")) ? R.string.roon_paused_on : R.string.roon_plays,
                    what + (album.isEmpty() ? "" : " (" + ctx.getString(R.string.roon_album, album) + ")"));
        } catch (IOException e) {
            return null;
        }
    }

    /** The first output's volume of a zone, or null when it has none Roon controls. */
    private static JSONObject volumeOf(JSONObject zone) {
        org.json.JSONArray outs = zone.optJSONArray("outputs");
        JSONObject o = outs == null ? null : outs.optJSONObject(0);
        JSONObject v = o == null ? null : o.optJSONObject("volume");
        return v != null && v.has("value") ? v : null;
    }

    private static void command(Context app, RoonCommands.Kind kind, String arg) {
        for (int attempt = 0; attempt < 2; attempt++) {
            if (commandOnce(app, kind, arg, attempt == 0)) return;
            android.util.Log.i("Roon", "connection dropped, " + kind + " again");
        }
    }

    /** False when the connection dropped and the command may be tried once more. */
    private static boolean commandOnce(Context app, RoonCommands.Kind kind, String arg, boolean mayRetry) {
        String output = RoonApi.output(app);
        if (output == null) {
            toast(app, app.getString(R.string.roon_no_player_set));
            return true;
        }
        try {
            RoonApi api = RoonApi.get(app);
            JSONObject z = target(api, output);
            if (z == null) {
                toast(app, app.getString(R.string.roon_no_player,
                        RoonApi.prefs(app).getString(RoonApi.KEY_OUTPUT_NAME, "?")));
                return true;
            }
            String zone = z.optString("zone_id");
            switch (kind) {
                case PAUSE: api.control(zone, "pause"); break;
                case STOP: api.control(zone, "stop"); break;
                case NEXT: api.control(zone, "next"); break;
                case PREVIOUS: api.control(zone, "previous"); break;
                case PLAY:
                    toInternalPlayer(app);
                    api.control(zone, "play");
                    RoonHandoff.arm(app);
                    keepPlaying(api, output, null, null);
                    break;
                case LOUDER:
                case QUIETER:
                case VOLUME:
                    volume(app, api, z, kind, arg);
                    break;
                case MUTE:
                case UNMUTE:
                    for (String o : outputsOf(z)) api.mute(o, kind == RoonCommands.Kind.MUTE);
                    break;
                case RADIO: {
                    JSONObject np = z.optJSONObject("now_playing");
                    JSONObject two = np == null ? null : np.optJSONObject("two_line");
                    if (two == null) {
                        toast(app, app.getString(R.string.roon_plays_nothing));
                        break;
                    }
                    String words = two.optString("line1").replaceAll("\\s*[(\\[].*$", "") + " " + two.optString("line2");
                    RoonSearch.Result r = RoonSearch.play(api.browser(zone), words.trim(), RoonSearch.TRACK, "Start Radio");
                    toast(app, r == null ? app.getString(R.string.roon_failed) : app.getString(R.string.roon_radio, r.title));
                    break;
                }
                case PLAY_NEXT:
                case QUEUE: {
                    boolean next = kind == RoonCommands.Kind.PLAY_NEXT;
                    String[] k = RoonSearch.kindOf(arg);
                    RoonSearch.Result r = RoonSearch.play(api.browser(zone), k[1], k[0], next ? "Add Next" : "Queue");
                    if (r == null) {
                        toast(app, app.getString(R.string.roon_nothing, arg));
                        break;
                    }
                    String what = r.subtitle.isEmpty() ? r.title : r.title + " — " + r.subtitle;
                    toast(app, app.getString(next ? R.string.roon_added_next : R.string.roon_queued, what));
                    break;
                }
                case TRANSFER: {
                    JSONObject to = zoneNamed(api.zones(), arg);
                    if (to == null) {
                        toast(app, app.getString(R.string.roon_no_zone, arg));
                    } else if (to.optString("zone_id").equals(zone)) {
                        toast(app, app.getString(R.string.roon_already_there, to.optString("display_name")));
                    } else {
                        api.transfer(zone, to.optString("zone_id"));
                        toast(app, app.getString(R.string.roon_moved, to.optString("display_name")));
                    }
                    break;
                }
                case EVERYWHERE: {
                    org.json.JSONArray outs = api.outputs();
                    java.util.List<String> ids = new java.util.ArrayList<>(outputsOf(z));
                    for (int i = 0; outs != null && i < outs.length(); i++) {
                        String id = outs.optJSONObject(i).optString("output_id");
                        if (!ids.contains(id)) ids.add(id);
                    }
                    if (ids.size() < 2) {
                        toast(app, app.getString(R.string.roon_one_zone));
                    } else {
                        api.group(ids);
                        toast(app, app.getString(R.string.roon_everywhere, ids.size()));
                    }
                    break;
                }
                default:
                    break;
            }
        } catch (RoonApi.NotReady e) {
            toast(app, app.getString(e.notEnabled ? R.string.roon_not_enabled : R.string.roon_unreachable));
        } catch (IOException e) {
            if (mayRetry && dropped(e)) return false;
            android.util.Log.w("Roon", kind + ": " + e);
            toast(app, app.getString(R.string.roon_failed));
        }
        return true;
    }

    private static java.util.List<String> outputsOf(JSONObject zone) {
        java.util.List<String> ids = new java.util.ArrayList<>();
        org.json.JSONArray outs = zone.optJSONArray("outputs");
        for (int i = 0; outs != null && i < outs.length(); i++) ids.add(outs.optJSONObject(i).optString("output_id"));
        return ids;
    }

    /**
     * Louder, quieter or "звук на N" on the 0–100 scale, for each output of the zone that Roon sets the volume of, in
     * the output's own units, rounded down to its step.
     */
    private static void volume(Context app, RoonApi api, JSONObject zone, RoonCommands.Kind kind, String arg)
            throws IOException {
        org.json.JSONArray outs = zone.optJSONArray("outputs");
        String shown = null;
        for (int i = 0; outs != null && i < outs.length(); i++) {
            JSONObject o = outs.optJSONObject(i);
            JSONObject v = o == null ? null : o.optJSONObject("volume");
            if (v == null || !v.has("value")) continue;
            double min = v.optDouble("min"), max = v.optDouble("max"), step = v.optDouble("step", 1);
            double now = RoonCommands.toScale(v.optDouble("value"), min, max);
            double want;
            if (kind == RoonCommands.Kind.VOLUME) {
                want = RoonCommands.number(arg, now);
            } else {
                double by = RoonCommands.number(arg, step(app));
                want = kind == RoonCommands.Kind.LOUDER ? now + by : now - by;
            }
            double value = RoonCommands.toDevice(want, min, max, step);
            api.changeVolume(o.optString("output_id"), "absolute", value);
            android.util.Log.i("Roon", "volume " + RoonCommands.say(now) + " → " + RoonCommands.say(want) + ": "
                    + v.optDouble("value") + " → " + value + " " + v.optString("type"));
            shown = RoonCommands.say(RoonCommands.toScale(value, min, max));
        }
        if (RoonNowPlayingActivity.shown && shown != null) return;   // the screen shows the volume itself
        toast(app, shown == null ? app.getString(R.string.roon_no_volume) : app.getString(R.string.roon_volume_is, shown));
    }

    /** Waits for the zone to play, pressing Play again when it sits paused or stopped; false when it never plays. */
    /** Starts the search again (Play Now once more). */
    interface Replay {
        RoonSearch.Result run() throws IOException;
    }

    /**
     * Waits for the zone to play what was started. Right after the Eversolo changes input, Roon may take Play Now and
     * then sit stopped with the old queue: then the search is run again once, and after that Play is pressed (up to
     * twice) while it stands paused or stopped. False when it never plays.
     */
    static boolean keepPlaying(RoonApi api, String output, RoonSearch.Result expected, Replay replay)
            throws IOException {
        long start = SystemClock.uptimeMillis(), last = start;
        int presses = 0;
        boolean replayed = false;
        while (SystemClock.uptimeMillis() - start < PLAYING_WITHIN_MS) {
            JSONObject z = api.zoneOf(output);
            String state = z == null ? "" : z.optString("state");
            JSONObject np = z == null ? null : z.optJSONObject("now_playing");
            JSONObject three = np == null ? null : np.optJSONObject("three_line");
            boolean right = expected == null || three != null && expected.shows(three.optString("line1"),
                    three.optString("line2"), three.optString("line3"));
            if ("playing".equals(state) && right) return true;
            long now = SystemClock.uptimeMillis();
            if (now - last >= RETRY_AFTER_MS && !"loading".equals(state)) {
                if (!right && replay != null && !replayed) {
                    android.util.Log.i("Roon", "zone " + state + " with " + (three != null ? three.optString("line1") : "nothing")
                            + ", not " + expected.title + ": Play Now again");
                    replay.run();
                    replayed = true;
                    last = now;
                } else if (("paused".equals(state) || "stopped".equals(state)) && presses < 2) {
                    android.util.Log.i("Roon", "zone " + state + ": Play again");
                    api.control(output, "play");
                    presses++;
                    last = now;
                }
            }
            SystemClock.sleep(400);
        }
        return false;
    }

    static void toast(Context app, String text) {
        android.util.Log.i("Roon", "says: " + text);
        new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(app, text, Toast.LENGTH_LONG).show());
    }
}
