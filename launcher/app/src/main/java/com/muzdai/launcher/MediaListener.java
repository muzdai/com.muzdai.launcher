package com.muzdai.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;

/**
 * Only there so Home may read the media sessions for its mini player: MediaSessionManager.getActiveSessions takes an
 * enabled notification listener of the caller's in place of MEDIA_CONTENT_CONTROL. Notifications are ignored.
 */
public class MediaListener extends NotificationListenerService {

    static ComponentName component(Context ctx) {
        return new ComponentName(ctx, MediaListener.class);
    }

    /** Turns this listener on once (WRITE_SECURE_SETTINGS, allowlisted for the privileged launcher); true when on. */
    static boolean ensureEnabled(Context ctx) {
        String me = component(ctx).flattenToString();
        String on = Settings.Secure.getString(ctx.getContentResolver(), "enabled_notification_listeners");
        if (on != null) {
            for (String c : on.split(":")) {
                ComponentName cn = ComponentName.unflattenFromString(c);
                if (cn != null && cn.equals(component(ctx))) return true;
            }
        }
        if (ctx.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        try {
            return Settings.Secure.putString(ctx.getContentResolver(), "enabled_notification_listeners",
                    on == null || on.isEmpty() ? me : on + ":" + me);
        } catch (SecurityException e) {
            return false;
        }
    }
}
