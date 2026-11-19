package com.muzdai.launcher;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * What Roon's zone (the player chosen in settings, or whichever plays) is doing, for the screens that show it: Home's
 * mini player, the now playing screen, the screensaver. Pushed by Roon (RoonFeed), no polling.
 */
final class RoonNow {
    private RoonNow() { }

    /** One reading of the zone. */
    static final class Snapshot {
        String zoneId, state = "", title = "", artist = "", album = "", imageKey;
        /** Seconds; length 0 when unknown (radio). */
        double seek, length;
        /** The first output's volume: null when Roon does not set it. */
        JSONObject volume;
        boolean canNext, canPrevious;

        boolean playing() {
            return "playing".equals(state);
        }

        /** Something to show: playing, loading, or paused with a track. */
        boolean active() {
            return !title.isEmpty() && ("playing".equals(state) || "loading".equals(state) || "paused".equals(state));
        }

        /** "Artist — Title". */
        String line() {
            return artist.isEmpty() ? title : artist + " — " + title;
        }

        static Snapshot of(JSONObject zone) {
            Snapshot s = new Snapshot();
            if (zone == null) return s;
            s.zoneId = zone.optString("zone_id");
            s.state = zone.optString("state");
            s.canNext = zone.optBoolean("is_next_allowed");
            s.canPrevious = zone.optBoolean("is_previous_allowed");
            JSONObject np = zone.optJSONObject("now_playing");
            if (np != null) {
                JSONObject three = np.optJSONObject("three_line");
                if (three != null) {
                    s.title = three.optString("line1");
                    s.artist = three.optString("line2");
                    s.album = three.optString("line3");
                }
                s.imageKey = np.optString("image_key", null);
                s.seek = np.optDouble("seek_position", 0);
                s.length = np.optDouble("length", 0);
            }
            org.json.JSONArray outs = zone.optJSONArray("outputs");
            JSONObject o = outs == null ? null : outs.optJSONObject(0);
            JSONObject v = o == null ? null : o.optJSONObject("volume");
            s.volume = v != null && v.has("value") ? v : null;
            return s;
        }
    }

    interface Listener {
        /** On the main thread; null when Roon is not set up or does not answer. */
        void onRoon(Snapshot s);
    }

    private static final List<Listener> listeners = new ArrayList<>();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static volatile Snapshot last;

    /** The last reading, or null. */
    static Snapshot last() {
        return last;
    }

    static synchronized void listen(Context ctx, Listener l) {
        RoonFeed.start(ctx);
        if (!listeners.contains(l)) listeners.add(l);
        if (last != null) l.onRoon(last);
    }

    static synchronized void unlisten(Listener l) {
        listeners.remove(l);
    }

    /** Nothing to do: Roon pushes every change (RoonFeed). */
    static void soon() {
    }

    /** A new reading from RoonFeed (any thread); null when Roon is gone. */
    static void push(Snapshot s) {
        last = s;
        main.post(() -> {
            List<Listener> now;
            synchronized (RoonNow.class) {
                now = new ArrayList<>(listeners);
            }
            for (Listener l : now) l.onRoon(s);
        });
    }

    /** The cover as the core serves it over HTTP, fitted in size × size; null without one. */
    static String imageUrl(Context ctx, String key, int size) {
        if (key == null || key.isEmpty()) return null;
        return "http://" + RoonApi.host(ctx) + ":" + RoonApi.PORT + "/api/image/" + key + "?scale=fit&width=" + size
                + "&height=" + size;
    }

    /** "3:07"; "1:02:03" for an hour or more. */
    static String time(double seconds) {
        long s = Math.max(0, Math.round(Math.floor(seconds)));
        return s >= 3600 ? String.format(java.util.Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                : String.format(java.util.Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }
}
