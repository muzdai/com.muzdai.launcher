package com.muzdai.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The remote's volume keys for Roon. On this TV the system (TCL's volume UI) takes the volume keys before any
 * accessibility service or media session sees them, and only moves the TV's own music volume, which does not reach
 * the Eversolo while it is on its own player. So while Roon's zone plays, each change of the TV's music volume is
 * turned into a step for Roon (the step in settings on the 0–100 scale, rounded down to the device's step); when Roon
 * stops, the TV's volume is put back where it was, so the TV sounds as before.
 */
final class RoonVolume {
    private RoonVolume() { }

    /** The system's broadcast on a stream volume change (AudioManager's hidden constants). */
    private static final String VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION";
    private static final String EXTRA_STREAM = "android.media.EXTRA_VOLUME_STREAM_TYPE";

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final ExecutorService sender = Executors.newSingleThreadExecutor();
    private static Context app;
    private static boolean on;
    /** The chosen player's output and its volume, from Roon's last update. */
    private static volatile String output;
    private static volatile JSONObject volume;
    /**
     * The TV's music volume when listening began (put back when it ends), and the one last seen: each broadcast
     * compares the live volume with the last seen one, so TCL's several broadcasts per press count once. The TV's
     * volume is not put back on every press: TCL's volume UI keeps its own value and would step from that.
     */
    private static int baseline = -1, seen = -1;
    /** The value last sent to Roon, ahead of Roon's update: quick presses add up. */
    private static double pending;
    private static long pendingAt;
    private static Toast toast;

    private static final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (i.getIntExtra(EXTRA_STREAM, -1) != AudioManager.STREAM_MUSIC || baseline < 0) return;
            AudioManager am = app.getSystemService(AudioManager.class);
            if (am == null) return;
            int now = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            if (now == seen) return;
            int d = now - seen;
            seen = now;
            step(d);
        }
    };

    /** A zone update (any thread): listening follows whether the chosen player's zone plays. */
    static void update(Context ctx, JSONObject zone) {
        app = ctx.getApplicationContext();
        String state = zone == null ? "" : zone.optString("state");
        JSONObject v = volumeOf(zone);
        boolean want = v != null && ("playing".equals(state) || "paused".equals(state) && RoonFeed.ours());
        output = outputOf(zone);
        volume = v;
        main.post(() -> listen(want));
    }

    private static void listen(boolean want) {
        if (want == on) return;
        on = want;
        if (want) {
            AudioManager am = app.getSystemService(AudioManager.class);
            baseline = am == null ? -1 : am.getStreamVolume(AudioManager.STREAM_MUSIC);
            seen = baseline;
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(receiver, new IntentFilter(VOLUME_CHANGED), Context.RECEIVER_EXPORTED);
            } else {
                app.registerReceiver(receiver, new IntentFilter(VOLUME_CHANGED));
            }
        } else {
            app.unregisterReceiver(receiver);
            // The TV's own volume as it was before Roon, for when the TV sounds again.
            AudioManager am = app.getSystemService(AudioManager.class);
            if (am != null && baseline >= 0 && am.getStreamVolume(AudioManager.STREAM_MUSIC) != baseline) {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, baseline, 0);
                android.util.Log.i("Roon", "the TV's volume back to " + baseline);
            }
            baseline = -1;
            seen = -1;
        }
    }

    /** {@code steps} key presses up (positive) or down. */
    private static void step(int steps) {
        JSONObject v = volume;
        String out = output;
        if (v == null || out == null) return;
        double min = v.optDouble("min"), max = v.optDouble("max"), deviceStep = v.optDouble("step", 1);
        long now = System.currentTimeMillis();
        double current = now - pendingAt < 1500 ? pending : v.optDouble("value");
        double value = RoonCommands.toDevice(RoonCommands.toScale(current, min, max) + steps * RoonPlay.step(app),
                min, max, deviceStep);
        if (value == current) value = Math.max(min, Math.min(max, current + (steps > 0 ? deviceStep : -deviceStep)));
        pending = value;
        pendingAt = now;
        double send = value;
        RoonHandoff.commanded();
        sender.execute(() -> {
            try {
                RoonApi.get(app, 1000).changeVolume(out, "absolute", send);
            } catch (IOException e) {
                android.util.Log.w("Roon", "volume key: " + e);
            }
        });
        android.util.Log.i("Roon", "volume key " + (steps > 0 ? "+" : "") + steps + ": " + current + " → " + value);
        if (RoonNowPlayingActivity.shown) return;   // the screen shows the volume itself
        String t = app.getString(R.string.roon_volume_is, RoonCommands.say(RoonCommands.toScale(value, min, max)));
        if (toast != null) toast.cancel();
        toast = Toast.makeText(app, t, Toast.LENGTH_SHORT);
        toast.show();
    }

    private static JSONObject volumeOf(JSONObject zone) {
        org.json.JSONArray outs = zone == null ? null : zone.optJSONArray("outputs");
        JSONObject o = outs == null ? null : outs.optJSONObject(0);
        JSONObject v = o == null ? null : o.optJSONObject("volume");
        return v != null && v.has("value") ? v : null;
    }

    private static String outputOf(JSONObject zone) {
        org.json.JSONArray outs = zone == null ? null : zone.optJSONArray("outputs");
        JSONObject o = outs == null ? null : outs.optJSONObject(0);
        return o == null ? null : o.optString("output_id", null);
    }
}
