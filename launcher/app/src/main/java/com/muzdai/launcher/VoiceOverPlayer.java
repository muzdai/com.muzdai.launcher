package com.muzdai.launcher;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

/**
 * The microphone (or a voice search button) over a player (VoiceActions.PLAYER_APPS): the player stays on screen; a beep, listening in the
 * background (from the key service's process, no window), and the request played or searched in that player unless
 * it names another app. A choice the request leaves open still needs the voice panel. Main thread only.
 */
final class VoiceOverPlayer {
    private VoiceOverPlayer() { }

    private static VoiceInput voice;
    /** Counts heard requests, so Back can drop the one the AI is still reading. */
    private static int request;
    private static boolean thinking;

    /** Listening, or the AI still reading what was heard: Back cancels (InputKeyService). */
    static boolean busy() {
        return (voice != null && voice.listening()) || thinking;
    }

    /** Back over the player: stop listening, or forget the request being read. */
    static void cancel(Context ctx) {
        request++;
        thinking = false;
        if (voice != null) voice.cancel();
        Toast.makeText(ctx.getApplicationContext(), R.string.voice_cancelled, Toast.LENGTH_SHORT).show();
    }
    private static String player;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** Starts listening, or stops when already listening; false when it cannot record here. */
    static boolean listen(Context ctx, String pkg) {
        Context app = ctx.getApplicationContext();
        if (voice != null && voice.listening()) {
            voice.cancel();
            return true;
        }
        player = pkg;
        voice = new VoiceInput(app, new VoiceInput.Callback() {
            @Override
            public void onListening(boolean on) { }

            @Override
            public void onResult(String text) {
                Toast.makeText(app, "«" + text + "»", Toast.LENGTH_SHORT).show();
                String inApp = player;
                int mine = ++request;
                thinking = true;
                new Thread(() -> {
                    VoiceActions.Plan p = new VoiceActions(app).inApp(inApp).front(inApp).planWithAi(text);
                    MAIN.post(() -> {
                        if (mine != request) return;   // cancelled with Back, or a newer request
                        thinking = false;
                        run(app, text, p);
                    });
                }, "voice-over-player").start();
            }

            @Override
            public void onError(int messageRes, int code) {
                Toast.makeText(app, app.getString(messageRes, code), Toast.LENGTH_SHORT).show();
            }
        });
        return voice.start();
    }

    private static void run(Context app, String text, VoiceActions.Plan p) {
        try {
            if (p.kind == VoiceActions.Plan.Kind.START) {
                app.startActivity(p.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } else if (p.kind == VoiceActions.Plan.Kind.PLAY) {
                VoiceRun.play(app, p.card);
            } else if (p.kind == VoiceActions.Plan.Kind.CLOSE) {
                VoiceRun.close(app, p.pkg);
            } else {
                app.startActivity(new Intent(app, VoiceSearchActivity.class)
                        .putExtra(VoiceSearchActivity.EXTRA_QUERY, text)
                        .putExtra(VoiceSearchActivity.EXTRA_PLAN, VoiceSearchActivity.pack(p))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(app, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }
}
