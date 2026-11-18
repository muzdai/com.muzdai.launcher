package com.muzdai.launcher;

import android.app.BroadcastOptions;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * Plays a Qobuz track or album without pressing on its screen, through the Qobuz Bridge module (an Xposed module for
 * Vector, github.com/muzdai/qobuz-bridge) running inside Qobuz: the launcher finds the tracks in the Qobuz catalogue
 * (QobuzCatalog) and sends them to the module, which puts them in Qobuz's own queue and starts playing. Then Qobuz
 * comes forward on its full screen player. Without the module, or when it does not answer, {@code fallback} runs
 * (the old way: typing into Qobuz's search and pressing the result).
 */
final class QobuzBridge {
    private QobuzBridge() { }

    static final String ACTION_PLAY = "com.muzdai.qobuzbridge.PLAY";
    /** The module's result codes; NO_ANSWER when no receiver took the broadcast (module off, Qobuz not running). */
    static final int DONE = 1, NOT_READY = 0, NO_ANSWER = -99;
    /** How long to wait for Qobuz's player once the module answers, and for any answer at all (Qobuz starting). */
    private static final long TRY_FOR_MS = 15000, ANSWER_WITHIN_MS = 6000, RETRY_MS = 800;
    /** How long Qobuz gets to be playing the first track after the module said it started it, and the poll step. */
    private static final long CHECK_FOR_MS = 5000, CHECK_EVERY_MS = 500;
    private static final String TAG = "QobuzBridge";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    static void play(Context ctx, String query, boolean album, Runnable fallback) {
        Context app = ctx.getApplicationContext();
        new Thread(() -> {
            String appId = QobuzCatalog.appId(app);
            List<JSONObject> tracks = null;
            try {
                if (appId != null) tracks = album ? QobuzCatalog.album(appId, query) : QobuzCatalog.track(appId, query);
            } catch (Exception e) {
                Log.w(TAG, "catalogue: " + e);
            }
            if (tracks == null || tracks.isEmpty()) {
                Log.i(TAG, (appId == null ? "no app id" : "nothing in the catalogue") + " for \"" + query
                        + "\": the screen way");
                MAIN.post(fallback);
                return;
            }
            String json = new JSONArray(tracks).toString();
            String first = tracks.get(0).optString("title");
            MAIN.post(() -> {
                openQobuz(app);
                long now = SystemClock.uptimeMillis();
                send(app, json, query, first, 1, now + ANSWER_WITHIN_MS, now + TRY_FOR_MS, fallback);
            });
        }, "qobuz-bridge").start();
    }

    /**
     * Sends until the module has Qobuz's player (Qobuz may still be starting). Once the module has started the queue,
     * checks that Qobuz really plays its first track: in a Qobuz process that had been running a long time the
     * module once answered "playing" while nothing started. Then it sends once more, and after that the screen way.
     */
    private static void send(Context app, String json, String query, String first, int attempt, long answerBy,
                             long until, Runnable fallback) {
        Intent intent = new Intent(ACTION_PLAY).setPackage(VoiceActions.QOBUZ)
                .putExtra("tracks", json).putExtra("query", query).putExtra("start", 0);
        BroadcastReceiver answer = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                int code = getResultCode();
                Log.i(TAG, "module answered " + code + ": " + getResultData());
                if (code == DONE) {
                    whenPlaying(app, first, SystemClock.uptimeMillis() + CHECK_FOR_MS, () -> {
                        Log.i(TAG, "Qobuz plays \"" + first + "\"");
                        // Qobuz's mini player opens the full screen player.
                        InputKeyService.press(VoiceActions.QOBUZ, InputKeyService.BY_ID + "player-card", 10000);
                    }, () -> {
                        if (attempt < 2) {
                            Log.w(TAG, "module said playing, Qobuz does not: sending again");
                            long now = SystemClock.uptimeMillis();
                            send(app, json, query, first, attempt + 1, now + ANSWER_WITHIN_MS, now + TRY_FOR_MS, fallback);
                        } else {
                            Log.w(TAG, "Qobuz still not playing: the screen way");
                            fallback.run();
                        }
                    });
                } else if (code == NOT_READY && SystemClock.uptimeMillis() < until
                        || code == NO_ANSWER && SystemClock.uptimeMillis() < answerBy) {
                    MAIN.postDelayed(() -> send(app, json, query, first, attempt, answerBy, until, fallback), RETRY_MS);
                } else {
                    fallback.run();
                }
            }
        };
        if (Build.VERSION.SDK_INT >= 34) {
            // The module only takes commands whose sender it can see is this launcher.
            Bundle options = BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle();
            app.sendOrderedBroadcast(intent, null, options, answer, MAIN, NO_ANSWER, null, null);
        } else {
            app.sendOrderedBroadcast(intent, null, answer, MAIN, NO_ANSWER, null, null);
        }
    }

    /** Runs playing once Qobuz's media session plays a track named like {@code title}, else notPlaying at the deadline. */
    private static void whenPlaying(Context app, String title, long deadline, Runnable playing, Runnable notPlaying) {
        android.media.session.MediaController c = MediaListener.playing(app, VoiceActions.QOBUZ);
        android.media.MediaMetadata m = c == null ? null : c.getMetadata();
        CharSequence now = m == null ? null : m.getText(android.media.MediaMetadata.METADATA_KEY_TITLE);
        if (now != null && QobuzCatalog.sameTitle(now.toString(), title)) {
            playing.run();
        } else if (SystemClock.uptimeMillis() < deadline) {
            MAIN.postDelayed(() -> whenPlaying(app, title, deadline, playing, notPlaying), CHECK_EVERY_MS);
        } else {
            notPlaying.run();
        }
    }

    private static void openQobuz(Context app) {
        Intent launch = app.getPackageManager().getLeanbackLaunchIntentForPackage(VoiceActions.QOBUZ);
        if (launch == null) launch = app.getPackageManager().getLaunchIntentForPackage(VoiceActions.QOBUZ);
        if (launch == null) return;
        try {
            app.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException ignored) {
        }
    }
}
