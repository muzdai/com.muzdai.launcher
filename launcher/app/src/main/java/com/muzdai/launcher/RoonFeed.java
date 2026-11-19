package com.muzdai.launcher;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Roon's zones and the chosen player's queue as Roon pushes them (subscribe_zones, subscribe_queue): no polling; while
 * a zone plays the core sends its position once a second, otherwise only changes. Feeds the screens (RoonNow) and
 * the launcher's claim on the music (RoonOwner, for RoonHandoff). Started with the key service; reconnects by itself.
 */
final class RoonFeed {
    private RoonFeed() { }

    private static final long RETRY_MS = 5000;
    private static final String KEY_OURS = "roon_ours";

    private static Handler worker;
    private static Context app;
    private static boolean started;
    /** The connection the subscriptions are on; null while (re)connecting. */
    private static RoonApi api;
    private static String output;
    private static final Map<String, JSONObject> zones = new LinkedHashMap<>();
    private static final RoonOwner owner = new RoonOwner();

    static synchronized void start(Context ctx) {
        app = ctx.getApplicationContext();
        if (started) return;
        started = true;
        HandlerThread t = new HandlerThread("roon-feed");
        t.start();
        worker = new Handler(t.getLooper());
        synchronized (owner) {
            owner.restore(RoonApi.prefs(app).getBoolean(KEY_OURS, false));
        }
        worker.post(RoonFeed::connect);
        worker.postDelayed(RoonFeed::tick, 2000);
    }

    /** Settings changed (core, player): subscribe again. */
    static void restart() {
        Handler w = worker;
        if (w == null) return;
        w.post(() -> {
            api = null;
            w.removeCallbacks(RoonFeed::connect);
            connect();
        });
    }

    private static void connect() {
        if (api != null && api.isOpen()) return;
        api = null;
        synchronized (zones) {
            zones.clear();
        }
        output = RoonApi.output(app);
        if (!RoonApi.configured(app)) {
            worker.postDelayed(RoonFeed::connect, 30000);
            return;
        }
        try {
            RoonApi a = RoonApi.get(app, 2000);
            a.subscribe(RoonApi.TRANSPORT + "subscribe_zones", new JSONObject(), m -> onZones(a, m));
            a.subscribe(RoonApi.TRANSPORT + "subscribe_queue", new JSONObject().put("zone_or_output_id", output)
                    .put("max_item_count", 6), m -> onQueue(a, m));
            api = a;
        } catch (IOException | JSONException e) {
            worker.postDelayed(RoonFeed::connect, RETRY_MS);
        }
    }

    private static void lost(RoonApi from) {
        if (worker == null) return;
        worker.post(() -> {
            if (api == from) {
                api = null;
                RoonNow.push(null);
                worker.removeCallbacks(RoonFeed::connect);
                worker.postDelayed(RoonFeed::connect, 1000);
            }
        });
    }

    // ---- zones ------------------------------------------------------------------------------------

    private static void onZones(RoonApi from, Moo m) {
        if ("CLOSED".equals(m.verb)) {
            lost(from);
            return;
        }
        JSONObject b = m.body;
        if (b == null) return;
        long now = SystemClock.uptimeMillis();
        synchronized (zones) {
            if ("Subscribed".equals(m.name)) zones.clear();
            for (String k : new String[]{"zones", "zones_added", "zones_changed"}) {
                JSONArray a = b.optJSONArray(k);
                for (int i = 0; a != null && i < a.length(); i++) {
                    JSONObject z = a.optJSONObject(i);
                    if (z != null) zones.put(z.optString("zone_id"), z);
                }
            }
            JSONArray removed = b.optJSONArray("zones_removed");
            for (int i = 0; removed != null && i < removed.length(); i++) zones.remove(removed.optString(i));
            JSONArray seeks = b.optJSONArray("zones_seek_changed");
            for (int i = 0; seeks != null && i < seeks.length(); i++) {
                JSONObject s = seeks.optJSONObject(i);
                JSONObject z = s == null ? null : zones.get(s.optString("zone_id"));
                JSONObject np = z == null ? null : z.optJSONObject("now_playing");
                if (np == null || !s.has("seek_position")) continue;
                try {
                    np.put("seek_position", s.optDouble("seek_position"));
                } catch (JSONException ignored) {
                    // the old position stays
                }
            }
        }
        JSONObject mine = mine();
        if (mine != null) {
            String why;
            synchronized (owner) {
                why = owner.onState(mine.optString("state"), now);
                JSONObject np = mine.optJSONObject("now_playing");
                if (why == null && np != null && np.has("seek_position") && b.has("zones_seek_changed")) {
                    why = owner.onSeek(np.optDouble("seek_position"), now);
                }
            }
            if (why != null) notOurs(why);
        }
        RoonNow.push(RoonNow.Snapshot.of(target()));
    }

    // ---- queue ------------------------------------------------------------------------------------

    private static final List<Long> queue = new ArrayList<>();

    private static void onQueue(RoonApi from, Moo m) {
        if ("CLOSED".equals(m.verb)) {
            lost(from);
            return;
        }
        JSONObject b = m.body;
        if (b == null) return;
        List<Long> ids;
        synchronized (queue) {
            if (b.has("items")) {
                queue.clear();
                JSONArray items = b.optJSONArray("items");
                for (int i = 0; items != null && i < items.length(); i++) queue.add(items.optJSONObject(i).optLong("queue_item_id"));
            }
            JSONArray changes = b.optJSONArray("changes");
            for (int i = 0; changes != null && i < changes.length(); i++) {
                JSONObject c = changes.optJSONObject(i);
                int at = c.optInt("index");
                if ("remove".equals(c.optString("operation"))) {
                    for (int k = 0; k < c.optInt("count") && at < queue.size(); k++) queue.remove(at);
                } else if ("insert".equals(c.optString("operation"))) {
                    JSONArray items = c.optJSONArray("items");
                    for (int k = 0; items != null && k < items.length(); k++) {
                        queue.add(Math.min(at + k, queue.size()), items.optJSONObject(k).optLong("queue_item_id"));
                    }
                }
            }
            ids = new ArrayList<>(queue);
        }
        String why;
        synchronized (owner) {
            why = owner.onQueue(ids, SystemClock.uptimeMillis());
        }
        if (why != null) notOurs(why);
    }

    // ---- ours -------------------------------------------------------------------------------------

    private static void tick() {
        String why;
        synchronized (owner) {
            why = owner.tick(SystemClock.uptimeMillis());
        }
        if (why != null) notOurs(why);
        worker.postDelayed(RoonFeed::tick, 2000);
    }

    /** The launcher started music in Roon. */
    static void started(Context ctx) {
        synchronized (owner) {
            owner.started(SystemClock.uptimeMillis());
        }
        RoonApi.prefs(ctx).edit().putBoolean(KEY_OURS, true).apply();
    }

    /** Any other command from here. */
    static void commanded() {
        synchronized (owner) {
            owner.commanded(SystemClock.uptimeMillis());
        }
    }

    static boolean ours() {
        synchronized (owner) {
            return owner.ours();
        }
    }

    static void drop(Context ctx) {
        synchronized (owner) {
            owner.drop();
        }
        RoonApi.prefs(ctx).edit().putBoolean(KEY_OURS, false).apply();
    }

    private static void notOurs(String why) {
        android.util.Log.i("Roon", "not ours any more: " + why);
        if (app != null) RoonApi.prefs(app).edit().putBoolean(KEY_OURS, false).apply();
    }

    // ---- reading ----------------------------------------------------------------------------------

    /** The chosen player's zone, or null. */
    static JSONObject mine() {
        synchronized (zones) {
            return RoonApi.zoneOf(new JSONArray(zones.values()), output);
        }
    }

    /** The zone commands go to: the chosen player's when it plays, else any playing zone, else the chosen one's. */
    static JSONObject target() {
        synchronized (zones) {
            JSONObject mine = RoonApi.zoneOf(new JSONArray(zones.values()), output);
            if (mine != null && "playing".equals(mine.optString("state"))) return mine;
            for (JSONObject z : zones.values()) if ("playing".equals(z.optString("state"))) return z;
            return mine;
        }
    }

    /** Subscribed and up to date. */
    static boolean live() {
        RoonApi a = api;
        return a != null && a.isOpen();
    }
}
