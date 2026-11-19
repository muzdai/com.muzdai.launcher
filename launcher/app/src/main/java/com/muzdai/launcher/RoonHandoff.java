package com.muzdai.launcher;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;

import java.io.IOException;

/**
 * After Roon starts playing from here: once the TV makes its own sound again (another app's media plays, or live TV
 * or an HDMI input comes up in com.tcl.tv) Roon is paused and the Eversolo goes back to the TV's eARC input. Checks
 * every few seconds, only while armed; disarms when the Eversolo is no longer on its own player (someone chose
 * another input) or after handing back.
 */
final class RoonHandoff {
    private RoonHandoff() { }

    private static final long EVERY_MS = 3000;
    /** How often (in checks) the Eversolo's input is read to see whether to keep watching. */
    private static final int INPUT_EVERY = 5;

    private static Handler worker;
    private static boolean armed;
    private static int ticks;

    static synchronized void arm(Context ctx) {
        Context app = ctx.getApplicationContext();
        if (worker == null) {
            HandlerThread t = new HandlerThread("roon-handoff");
            t.start();
            worker = new Handler(t.getLooper());
        }
        armed = true;
        ticks = 0;
        worker.removeCallbacksAndMessages(null);
        worker.postDelayed(() -> tick(app), EVERY_MS);
    }

    /** Roon was started from here and the TV has not taken the sound back: a paused Roon is still "ours" to show. */
    static synchronized boolean armed() {
        return armed;
    }

    static synchronized void disarm() {
        armed = false;
        if (worker != null) worker.removeCallbacksAndMessages(null);
    }

    private static void tick(Context app) {
        synchronized (RoonHandoff.class) {
            if (!armed) return;
        }
        String other = MediaListener.playingApp(app);
        String front = InputKeyService.frontPackage();
        if (other != null || VoiceActions.TV_APP.equals(front)) {
            android.util.Log.i("Roon", "the TV's own sound: " + (other != null ? other + " plays" : front + " in front"));
            handBack(app);
            disarm();
            return;
        }
        if (++ticks % INPUT_EVERY == 0 && Eversolo.enabled(app)) {
            try {
                if (!Eversolo.INTERNAL.equals(new Eversolo(Eversolo.host(app)).inputs().currentTag())) {
                    disarm();
                    return;
                }
            } catch (IOException e) {
                // not answering for now: keep watching
            }
        }
        synchronized (RoonHandoff.class) {
            if (armed) worker.postDelayed(() -> tick(app), EVERY_MS);
        }
    }

    /** Pauses Roon's zone and puts the Eversolo back on eARC, when it is on its own player. Blocking. */
    static void handBack(Context app) {
        String output = RoonApi.output(app);
        if (output != null) {
            try {
                RoonApi.get(app, 1000).control(output, "pause");
            } catch (IOException e) {
                android.util.Log.w("Roon", "pause: " + e);
            }
        }
        if (!Eversolo.enabled(app)) return;
        try {
            Eversolo e = new Eversolo(Eversolo.host(app));
            if (Eversolo.INTERNAL.equals(e.inputs().currentTag())) e.setInput(Eversolo.ARC);
        } catch (IOException e) {
            android.util.Log.w("Roon", "eversolo back to eARC: " + e);
        }
    }
}
