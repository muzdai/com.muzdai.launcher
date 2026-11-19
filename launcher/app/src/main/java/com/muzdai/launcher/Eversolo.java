package com.muzdai.launcher;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * The Eversolo streamer/DAC the TV sounds through (eARC), over its own HTTP control API (port 9529, plain GET, as the
 * Eversolo app and the owner's Home Assistant integration github.com/muzdai/eversolo use it): which input is on,
 * switching it, and its volume. Each input keeps its own volume (0..200, 0.5 dB a step, 200 = 0 dB). Blocking.
 */
final class Eversolo {
    static final String KEY_HOST = "eversolo_host", KEY_ON = "eversolo_on";
    static final int PORT = 9529;
    /** Input tags: the streamer's own player (Roon Ready) and the TV's eARC. */
    static final String INTERNAL = "XMOS", ARC = "EARC";
    private static final String MUSIC = "/ZidooMusicControl/v2/";
    private static final int TIMEOUT_MS = 3000;

    static String host(Context ctx) {
        return RoonApi.prefs(ctx).getString(KEY_HOST, "").trim();
    }

    /** Input switching is on (the default) and the streamer's address is known. */
    static boolean enabled(Context ctx) {
        return RoonApi.prefs(ctx).getBoolean(KEY_ON, true) && !host(ctx).isEmpty();
    }

    private final String base;

    Eversolo(String host) {
        base = "http://" + host + ":" + PORT;
    }

    /** The tag and name of each input in order, and the index of the one on. */
    static final class Inputs {
        final String[] tags, names;
        final int current;

        Inputs(String[] tags, String[] names, int current) {
            this.tags = tags;
            this.names = names;
            this.current = current;
        }

        String currentTag() {
            return current >= 0 && current < tags.length ? tags[current] : null;
        }

        int indexOf(String tag) {
            for (int i = 0; i < tags.length; i++) if (tag.equals(tags[i])) return i;
            return -1;
        }
    }

    static Inputs parseInputs(String json) throws IOException {
        try {
            JSONObject o = new JSONObject(json);
            JSONArray a = o.optJSONArray("inputData");
            if (a == null) throw new IOException("no inputs");
            String[] tags = new String[a.length()], names = new String[a.length()];
            for (int i = 0; i < a.length(); i++) {
                JSONObject in = a.optJSONObject(i);
                tags[i] = in == null ? "" : in.optString("tag");
                names[i] = in == null ? "" : in.optString("name");
            }
            return new Inputs(tags, names, o.optInt("inputIndex", -1));
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    Inputs inputs() throws IOException {
        return parseInputs(get(MUSIC + "getInputAndOutputList?isLight=true"));
    }

    /** True when the input is there and the request went through. */
    boolean setInput(String tag) throws IOException {
        int i = inputs().indexOf(tag);
        if (i < 0) return false;
        get(MUSIC + "setInputList?tag=" + ContentSearch.enc(tag) + "&index=" + i + "&isLight=true");
        return true;
    }

    /** The volume of the input on now, in device units (0..max), or -1. */
    int volume() throws IOException {
        try {
            JSONObject v = new JSONObject(get(MUSIC + "getState")).optJSONObject("volumeData");
            return v == null ? -1 : v.optInt("currenttVolume", -1);
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    void setVolume(int units) throws IOException {
        get(MUSIC + "setDevicesVolume?volume=" + Math.max(0, units));
    }

    /** True when an Eversolo answers at this address. */
    static boolean answers(String host) {
        try {
            new Eversolo(host).inputs();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private String get(String path) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        try {
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                return out.toString("UTF-8");
            }
        } finally {
            c.disconnect();
        }
    }
}
