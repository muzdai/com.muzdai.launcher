package com.muzdai.launcher;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

/**
 * Opens a found card and gets it playing, the way each app was found to need on the TV: a YouTube watch link or a
 * spotify:track link plays by itself; Deezer must be running before its album link works, and then the track's row is
 * pressed; Emby and ALPAC open the title's page with focus on Play / Watch, which is pressed; Qobuz, which has no
 * links, gets the words typed into its own search and its first track pressed. The pressing is done by
 * InputKeyService (an accessibility service); without it the page is left open for OK.
 */
final class VoiceRun {
    private VoiceRun() { }

    /** How long an app gets to show the page whose button or row is pressed. */
    private static final long WAIT_MS = 20000;

    static void play(Context ctx, ContentSearch.Card card) {
        Context app = ctx.getApplicationContext();
        Intent link = link(card);
        if (card.launchFirst) {
            Intent launch = app.getPackageManager().getLeanbackLaunchIntentForPackage(card.pkg);
            if (launch == null) launch = app.getPackageManager().getLaunchIntentForPackage(card.pkg);
            if (launch != null && start(app, launch)) {
                // The link only once the app's own window is up; without the helper, after a fixed wait.
                if (!InputKeyService.whenShown(card.pkg, WAIT_MS, () -> openAndPress(app, card, link))
                        && !isQobuz(card)) {
                    new Handler(Looper.getMainLooper()).postDelayed(() -> start(app, link), 8000);
                }
                return;
            }
        }
        openAndPress(app, card, link);
    }

    private static void openAndPress(Context app, ContentSearch.Card card, Intent link) {
        if (isQobuz(card)) {   // no link: the words go into the app's own search
            InputKeyService.qobuz(card.pkg, card.click, ContentSearch.QOBUZ_ALBUM.equals(card.uri), 2 * WAIT_MS);
            return;
        }
        if (!start(app, link) || card.click == null) return;
        InputKeyService.press(card.pkg, card.click, WAIT_MS);
    }

    /**
     * "Close": pauses the app's music, then force-stops it through the TV's own ADB (LocalAdb, as the tile menu's
     * Close). Never this launcher; null says there is nothing to close.
     */
    static void close(Context ctx, String pkg) {
        Context app = ctx.getApplicationContext();
        if (pkg == null || pkg.equals(app.getPackageName())) {
            Toast.makeText(app, R.string.voice_nothing_to_close, Toast.LENGTH_SHORT).show();
            return;
        }
        android.media.session.MediaController playing = MediaListener.playing(app, pkg);
        if (playing != null) {
            try {
                playing.getTransportControls().pause();
            } catch (RuntimeException ignored) {
            }
        }
        String name = pkg;
        try {
            name = String.valueOf(app.getPackageManager().getApplicationLabel(app.getPackageManager().getApplicationInfo(pkg, 0)));
        } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {
        }
        String label = name;
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            LocalAdb.Result r = LocalAdb.forceStop(app, pkg);
            main.post(() -> Toast.makeText(app, r == LocalAdb.Result.STOPPED
                    ? app.getString(R.string.fork_tile_closed, label) : app.getString(R.string.voice_close_failed, label),
                    Toast.LENGTH_SHORT).show());
        }, "voice-close").start();
    }

    private static boolean isQobuz(ContentSearch.Card card) {
        return card.uri.startsWith("qobuz:");
    }

    static Intent link(ContentSearch.Card card) {
        // YouTube Music ignores links: the watch link goes to YouTube, which plays it.
        String pkg = VoiceActions.YOUTUBE_MUSIC.equals(card.pkg) ? VoiceActions.YOUTUBE : card.pkg;
        return new Intent(Intent.ACTION_VIEW, Uri.parse(card.uri)).setPackage(pkg);
    }

    private static boolean start(Context ctx, Intent i) {
        try {
            ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(ctx, R.string.not_available, Toast.LENGTH_SHORT).show();
            return false;
        }
    }
}
