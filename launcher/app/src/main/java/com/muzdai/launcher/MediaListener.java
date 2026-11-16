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

    private static boolean granted(Context ctx) {
        if (android.os.Build.VERSION.SDK_INT >= 27) {
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            return nm != null && nm.isNotificationListenerAccessGranted(component(ctx));
        }
        String on = android.provider.Settings.Secure.getString(ctx.getContentResolver(), "enabled_notification_listeners");
        return on != null && on.contains(component(ctx).flattenToString());
    }
}
