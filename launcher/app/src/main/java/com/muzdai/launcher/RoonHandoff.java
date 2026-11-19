package com.muzdai.launcher;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;

import java.io.IOException;

/**
 * Gives the Eversolo back to the TV: whenever the TV makes its own sound (another app's media starts playing, or live
 * TV or an HDMI input comes up in com.tcl.tv) while the Eversolo is on its own player, Roon is paused and the Eversolo
 * goes back to the TV's eARC input, whoever started Roon (the launcher, the Roon app, a phone). The check is local
 * every couple of seconds (media sessions, the window in front); the Eversolo is asked only when that changes.
 * Runs from the key service's start for as long as the launcher's process lives.
 */
final class RoonHandoff {
    private RoonHandoff() { }

    private static final long EVERY_MS = 2000;

    private static Handler worker;
    private static Context app;
    private static boolean watching;
    /** Roon was started from here and the TV has not taken the sound back: a paused Roon is still "ours" to show. */
    private static volatile boolean ours;
    /** The app whose sound was last seen (null: none): each new one is checked once. */
    private static String lastSound;

    /** Starts watching (once per process). */
    static synchronized void watch(Context ctx) {
        app = ctx.getApplicationContext();
        if (watching) return;
        watching = true;
        if (worker == null) {
            HandlerThread t = new HandlerThread("roon-handoff");
            t.start();
            worker = new Handler(t.getLooper());
        }
        worker.postDelayed(RoonHandoff::tick, EVERY_MS);
    }

    /** Roon was just started from here. */
    static void arm(Context ctx) {
        ours = true;
        watch(ctx);
    }

    static boolean armed() {
        return ours;
    }

    static void disarm() {
        ours = false;
    }

    /** Giving the input back is on (its own switch in settings) and the Eversolo's address is known. */
    static boolean enabled(Context ctx) {
        return !Eversolo.host(ctx).isEmpty() && RoonApi.prefs(ctx).getBoolean(Eversolo.KEY_BACK, true);
    }

    private static void tick() {
        try {
            if (enabled(app)) check();
        } catch (RuntimeException e) {
            android.util.Log.w("Roon", "handoff: " + e);
        }
        worker.postDelayed(RoonHandoff::tick, EVERY_MS);
    }

    private static void check() {
        String other = MediaListener.playingApp(app);
        String front = InputKeyService.frontPackage();
        String sound = other != null ? other : VoiceActions.TV_APP.equals(front) ? front : null;
        if (sound == null || sound.equals(lastSound)) {
            lastSound = sound;
            return;
        }
        lastSound = sound;
        try {
            if (!Eversolo.INTERNAL.equals(new Eversolo(Eversolo.host(app)).inputs().currentTag())) return;
        } catch (IOException e) {
            lastSound = null;   // the Eversolo did not answer: ask again next time
            return;
        }
        android.util.Log.i("Roon", "the TV's own sound: " + (other != null ? other + " plays" : front + " in front"));
        handBack(app);
    }

    /** Pauses Roon's zone and puts the Eversolo back on eARC, when it is on its own player. Blocking. */
    static void handBack(Context app) {
        ours = false;
        String output = RoonApi.output(app);
        if (output != null) {
            try {
                RoonApi api = RoonApi.get(app, 1000);
                org.json.JSONObject z = RoonPlay.target(api, output);
                if (z != null && "playing".equals(z.optString("state"))) api.control(z.optString("zone_id"), "pause");
            } catch (IOException e) {
                android.util.Log.w("Roon", "pause: " + e);
            }
        }
        if (!enabled(app)) return;
        try {
            Eversolo e = new Eversolo(Eversolo.host(app));
            if (Eversolo.INTERNAL.equals(e.inputs().currentTag())) e.setInput(Eversolo.ARC);
        } catch (IOException e) {
            android.util.Log.w("Roon", "eversolo back to eARC: " + e);
        }
    }
}
