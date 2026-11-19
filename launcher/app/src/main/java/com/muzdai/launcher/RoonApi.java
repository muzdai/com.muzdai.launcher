package com.muzdai.launcher;

import android.content.Context;
import android.content.SharedPreferences;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The launcher as a Roon extension: one WebSocket to the Roon Core (ws://core:9330/api), registered once the owner
 * enables "vPro TV Launcher" in Roon → Settings → Extensions; the token Roon gives then is kept, so later
 * connections need nothing. Uses the transport (zones, playback, volume) and browse (search, play) services and
 * answers the core's pings. Blocking calls: off the main thread only.
 */
final class RoonApi {
    static final String PREFS = "roon";
    static final String KEY_HOST = "host", KEY_TOKEN = "token", KEY_OUTPUT = "output", KEY_OUTPUT_NAME = "output_name";
    static final int PORT = 9330;
    static final String TRANSPORT = "com.roonlabs.transport:2/", BROWSE = "com.roonlabs.browse:1/";
    private static final String EXTENSION_ID = "com.muzdai.launcher";
    private static final long CONNECT_MS = 5000, CALL_MS = 10000;

    /** Why there is no connection. */
    static final class NotReady extends IOException {
        /** True when the core answers but the extension waits to be enabled in Roon. */
        final boolean notEnabled;

        NotReady(String message, boolean notEnabled) {
            super(message);
            this.notEnabled = notEnabled;
        }
    }

    static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static String host(Context ctx) {
        return prefs(ctx).getString(KEY_HOST, "").trim();
    }

    /** The output (player) chosen in settings, or null. */
    static String output(Context ctx) {
        String o = prefs(ctx).getString(KEY_OUTPUT, "");
        return o.isEmpty() ? null : o;
    }

    /** Roon is set up for voice: a core and a player chosen. */
    static boolean configured(Context ctx) {
        return !host(ctx).isEmpty() && output(ctx) != null;
    }

    private static RoonApi current;
    /** The core's name once connected ("rock"); for settings. */
    static volatile String coreName;

    /** The registered connection to the core in settings, opened when needed. Blocking. */
    static RoonApi get(Context ctx) throws IOException {
        return get(ctx, 4000);
    }

    /** As get, waiting up to {@code enableMs} for the owner to enable the extension on a first connection. */
    static RoonApi get(Context ctx, long enableMs) throws IOException {
        Context app = ctx.getApplicationContext();
        String host = host(app);
        if (host.isEmpty()) throw new NotReady("no core set", false);
        RoonApi api;
        synchronized (RoonApi.class) {
            if (current != null && (!current.host.equals(host) || current.closed || !current.ws.isOpen())) {
                current.close();
                current = null;
            }
            if (current == null) {
                current = new RoonApi(app, host);
                current.open();
            }
            api = current;
        }
        if (!api.waitRegistered(enableMs)) throw new NotReady("not enabled in Roon", true);
        return api;
    }

    private final Context app;
    final String host;
    private final AtomicInteger ids = new AtomicInteger();
    private final Map<Integer, LinkedBlockingQueue<Moo>> waiting = new ConcurrentHashMap<>();
    /** Subscriptions: every answer (Subscribed, Changed), and a "CLOSED" one when the connection ends. */
    interface Subscriber {
        void on(Moo m);
    }

    private final Map<Integer, Subscriber> subscribers = new ConcurrentHashMap<>();
    private final CountDownLatch registered = new CountDownLatch(1);
    private volatile boolean closed;
    private WebSocketClient ws;

    private RoonApi(Context app, String host) {
        this.app = app;
        this.host = host;
    }

    private void open() throws IOException {
        CountDownLatch opened = new CountDownLatch(1);
        ws = new WebSocketClient(URI.create("ws://" + host + ":" + PORT + "/api")) {
            @Override public void onOpen(ServerHandshake h) {
                opened.countDown();
            }

            @Override public void onMessage(String text) {
                received(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }

            @Override public void onMessage(ByteBuffer bytes) {
                byte[] b = new byte[bytes.remaining()];
                bytes.get(b);
                received(b);
            }

            @Override public void onClose(int code, String reason, boolean remote) {
                closed = true;
                opened.countDown();
                for (LinkedBlockingQueue<Moo> q : waiting.values()) q.offer(new Moo("CLOSED", "closed", -1, null));
                for (Subscriber s : subscribers.values()) s.on(new Moo("CLOSED", "closed", -1, null));
                subscribers.clear();
            }

            @Override public void onError(Exception e) {
                android.util.Log.w("Roon", "socket: " + e);
            }
        };
        // Roon does not answer WebSocket pings (the library would close the socket after a minute); the core pings
        // extensions itself over MOO (com.roonlabs.ping), answered in received().
        ws.setConnectionLostTimeout(0);
        ws.connect();
        try {
            if (!opened.await(CONNECT_MS, TimeUnit.MILLISECONDS) || closed || !ws.isOpen()) {
                close();
                throw new NotReady("the core does not answer", false);
            }
        } catch (InterruptedException e) {
            close();
            throw new NotReady("interrupted", false);
        }
        JSONObject info = call("com.roonlabs.registry:1/info", null).body;
        String coreId = info == null ? "" : info.optString("core_id");
        register(coreId);
    }

    /** Sends the registration; the core answers "Registered" once the extension is enabled (now or later). */
    private void register(String coreId) throws IOException {
        SharedPreferences p = prefs(app);
        JSONObject r = new JSONObject();
        try {
            r.put("extension_id", EXTENSION_ID)
                    .put("display_name", "vPro TV Launcher")
                    .put("display_version", version())
                    .put("publisher", "muzdai")
                    .put("email", "muzdai@users.noreply.github.com")
                    .put("website", "https://github.com/muzdai/com.muzdai.launcher")
                    .put("required_services", new JSONArray().put("com.roonlabs.transport:2").put("com.roonlabs.browse:1"))
                    .put("optional_services", new JSONArray())
                    .put("provided_services", new JSONArray().put("com.roonlabs.ping:1"));
            String token = p.getString(KEY_TOKEN + ":" + coreId, null);
            if (token != null) r.put("token", token);
        } catch (JSONException e) {
            throw new IOException(e);
        }
        int id = ids.getAndIncrement();
        LinkedBlockingQueue<Moo> answers = new LinkedBlockingQueue<>();
        waiting.put(id, answers);
        send(Moo.request(id, "com.roonlabs.registry:1/register", r));
        Thread t = new Thread(() -> {
            try {
                while (!closed) {
                    Moo m = answers.take();
                    if ("Registered".equals(m.name) && m.body != null) {
                        p.edit().putString(KEY_TOKEN + ":" + coreId, m.body.optString("token")).apply();
                        coreName = m.body.optString("display_name", null);
                        registered.countDown();
                    }
                    if ("COMPLETE".equals(m.verb) || "CLOSED".equals(m.verb)) break;
                }
            } catch (InterruptedException ignored) {
                // closing
            }
            waiting.remove(id);
        }, "roon-register");
        t.setDaemon(true);
        t.start();
    }

    private String version() {
        try {
            return app.getPackageManager().getPackageInfo(app.getPackageName(), 0).versionName;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    private boolean waitRegistered(long ms) {
        try {
            return registered.await(ms, TimeUnit.MILLISECONDS) && !closed;
        } catch (InterruptedException e) {
            return false;
        }
    }

    boolean isRegistered() {
        return registered.getCount() == 0 && !closed;
    }

    private void received(byte[] raw) {
        Moo m = Moo.parse(raw);
        if (m == null) return;
        if ("REQUEST".equals(m.verb)) {   // the core's ping (and anything else asked of us): fine
            send(Moo.format("COMPLETE", "Success", m.requestId, null));
            return;
        }
        Subscriber s = subscribers.get(m.requestId);
        if (s != null) {
            try {
                s.on(m);
            } catch (RuntimeException e) {
                android.util.Log.w("Roon", "subscriber: " + e);
            }
            return;
        }
        LinkedBlockingQueue<Moo> q = waiting.get(m.requestId);
        if (q != null) q.offer(m);
    }

    /** Subscribes (subscribe_zones, subscribe_queue…); the subscriber hears every answer on the socket's thread. */
    void subscribe(String name, JSONObject body, Subscriber s) throws IOException {
        int id = ids.getAndIncrement();
        try {
            body.put("subscription_key", id);
        } catch (JSONException e) {
            throw new IOException(e);
        }
        subscribers.put(id, s);
        send(Moo.request(id, name, body));
        if (closed) {
            subscribers.remove(id);
            throw new IOException(name + ": not connected");
        }
    }

    boolean isOpen() {
        return !closed && ws != null && ws.isOpen() && isRegistered();
    }

    private void send(byte[] b) {
        try {
            ws.send(b);
        } catch (RuntimeException e) {   // not connected: the next get() opens a new connection
            android.util.Log.w("Roon", "send: " + e);
            closed = true;
        }
    }

    /** One request and its first answer; IOException when it fails or no answer comes. */
    Moo call(String name, JSONObject body) throws IOException {
        int id = ids.getAndIncrement();
        LinkedBlockingQueue<Moo> q = new LinkedBlockingQueue<>();
        waiting.put(id, q);
        try {
            send(Moo.request(id, name, body));
            if (closed) throw new IOException(name + ": not connected");
            Moo m = q.poll(CALL_MS, TimeUnit.MILLISECONDS);
            if (m == null) throw new IOException(name + ": no answer");
            if ("CLOSED".equals(m.verb)) throw new IOException(name + ": connection closed");
            if (!"Success".equals(m.name)) throw new IOException(name + ": " + m.name
                    + (m.body != null ? " " + m.body : ""));
            return m;
        } catch (InterruptedException e) {
            throw new IOException(name + ": interrupted");
        } finally {
            waiting.remove(id);
        }
    }

    JSONObject callBody(String name, JSONObject body) throws IOException {
        Moo m = call(name, body);
        return m.body != null ? m.body : new JSONObject();
    }

    void close() {
        closed = true;
        try {
            if (ws != null) ws.close();
        } catch (RuntimeException ignored) {
            // already closed
        }
    }

    // ---- transport ------------------------------------------------------------------------------

    JSONArray zones() throws IOException {
        return callBody(TRANSPORT + "get_zones", null).optJSONArray("zones");
    }

    JSONArray outputs() throws IOException {
        return callBody(TRANSPORT + "get_outputs", null).optJSONArray("outputs");
    }

    /** The zone that holds the output, or null. */
    JSONObject zoneOf(String outputId) throws IOException {
        return zoneOf(zones(), outputId);
    }

    static JSONObject zoneOf(JSONArray zones, String outputId) {
        if (zones == null || outputId == null) return null;
        for (int i = 0; i < zones.length(); i++) {
            JSONObject z = zones.optJSONObject(i);
            JSONArray outs = z == null ? null : z.optJSONArray("outputs");
            if (outs == null) continue;
            for (int k = 0; k < outs.length(); k++) {
                JSONObject o = outs.optJSONObject(k);
                if (o != null && outputId.equals(o.optString("output_id"))) return z;
            }
        }
        return null;
    }

    /** play, pause, playpause, stop, previous, next. */
    void control(String zoneOrOutput, String what) throws IOException {
        try {
            call(TRANSPORT + "control", new JSONObject().put("zone_or_output_id", zoneOrOutput).put("control", what));
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    /** how: absolute (value in the output's own units), relative, relative_step. */
    void changeVolume(String outputId, String how, double value) throws IOException {
        try {
            call(TRANSPORT + "change_volume", new JSONObject().put("output_id", outputId).put("how", how)
                    .put("value", value));
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    void mute(String outputId, boolean on) throws IOException {
        try {
            call(TRANSPORT + "mute", new JSONObject().put("output_id", outputId).put("how", on ? "mute" : "unmute"));
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    /** Moves what plays (queue and all) from one zone to another. */
    void transfer(String from, String to) throws IOException {
        try {
            call(TRANSPORT + "transfer_zone", new JSONObject().put("from_zone_or_output_id", from)
                    .put("to_zone_or_output_id", to));
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    /** Groups the outputs into one zone (the first one's music plays on all). */
    void group(java.util.List<String> outputIds) throws IOException {
        try {
            call(TRANSPORT + "group_outputs", new JSONObject().put("output_ids", new JSONArray(outputIds)));
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    // ---- browse ---------------------------------------------------------------------------------

    /** RoonSearch's view of the browse service, for one zone. */
    RoonSearch.Browser browser(String zoneOrOutput) {
        return new RoonSearch.Browser() {
            @Override public JSONObject browse(JSONObject args) throws IOException {
                try {
                    args.put("hierarchy", "search").put("zone_or_output_id", zoneOrOutput);
                } catch (JSONException e) {
                    throw new IOException(e);
                }
                return callBody(BROWSE + "browse", args);
            }

            @Override public JSONArray load(int count) throws IOException {
                try {
                    JSONArray items = callBody(BROWSE + "load", new JSONObject().put("hierarchy", "search")
                            .put("offset", 0).put("count", count)).optJSONArray("items");
                    return items != null ? items : new JSONArray();
                } catch (JSONException e) {
                    throw new IOException(e);
                }
            }
        };
    }
}
