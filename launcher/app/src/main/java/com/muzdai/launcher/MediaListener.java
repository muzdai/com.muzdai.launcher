package com.muzdai.launcher;

import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.service.notification.NotificationListenerService;

/**
 * Only there so Home may read the media sessions for its mini player: MediaSessionManager.getActiveSessions takes an
 * enabled notification listener of the caller's in place of MEDIA_CONTENT_CONTROL. Notifications are ignored.
 */
public class MediaListener extends NotificationListenerService {

    static ComponentName component(Context ctx) {
        return new ComponentName(ctx, MediaListener.class);
    }

    /**
     * True once this listener is allowed. The first time it asks the TV's own ADB (LocalAdb), which needs network
     * debugging on and this launcher's key allowed, as the tile menu's Close does. Blocking: off the main thread.
     */
    static boolean ensureEnabled(Context ctx) {
        if (granted(ctx)) return true;
        return LocalAdb.allowListener(ctx, component(ctx)) && granted(ctx);
    }

    /**
     * The app playing behind whatever is on screen (a minimised player), or null: the first session in the playing
     * state, leaving out com.tcl.tv (always "playing" for the Assistant) and this launcher.
     */
    static String playingApp(Context ctx) {
        android.media.session.MediaController c = playing(ctx, null);
        return c == null ? null : c.getPackageName();
    }

    /** The playing session, of {@code pkg} only when given; null without access or when nothing plays. */
    static android.media.session.MediaController playing(Context ctx, String pkg) {
        android.media.session.MediaSessionManager m = ctx.getSystemService(android.media.session.MediaSessionManager.class);
        if (m == null) return null;
        try {
            for (android.media.session.MediaController c : m.getActiveSessions(component(ctx))) {
                String p = c.getPackageName();
                if (VoiceActions.TV_APP.equals(p) || ctx.getPackageName().equals(p)) continue;
                if (pkg != null && !pkg.equals(p)) continue;
                android.media.session.PlaybackState s = c.getPlaybackState();
                if (s != null && s.getState() == android.media.session.PlaybackState.STATE_PLAYING) return c;
            }
        } catch (SecurityException e) {
            // the listener is not allowed (yet)
        }
        return null;
    }

    private static boolean granted(Context ctx) {
        if (android.os.Build.VERSION.SDK_INT >= 27) {
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            return nm != null && nm.isNotificationListenerAccessGranted(component(ctx));
        }
        String on = android.provider.Settings.Secure.getString(ctx.getContentResolver(), "enabled_notification_listeners");
        return on != null && on.contains(component(ctx).flattenToString());
    }
}
