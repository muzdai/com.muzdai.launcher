package com.muzdai.launcher;

import android.content.ComponentName;
import android.content.Context;

/**
 * vPro: the few system commands the launcher needs and an app may not run itself, as root through Magisk's su
 * (Root): force-stop an app, uninstall an app without the system's second confirmation, allow the launcher's own
 * notification listener, give the assistant role (the remote's microphone button). Needs no USB/network debugging.
 * Only these commands, with package and component names checked, are ever sent. Blocking: off the main thread.
 */
final class RootCommands {
    private RootCommands() { }

    /** NO_ROOT: su is missing or Magisk refused the launcher. */
    enum Result { STOPPED, STILL_RUNNING, UNINSTALLED, NO_ROOT, FAILED }

    /** Google's assistant on Google TV, the holder of the role before this launcher took it. */
    static final String GOOGLE_ASSISTANT = "com.google.android.katniss";

    private static boolean safe(String s) {
        return s != null && s.matches("[A-Za-z0-9_./]+");
    }

    /** Force-stops {@code pkg}; STOPPED once no process of it is left. */
    static Result forceStop(String pkg) {
        if (!safe(pkg) || pkg.contains("/")) return Result.FAILED;
        String out = Root.run("am force-stop " + pkg + "; pidof " + pkg + " || echo gone");
        if (out == null) return Result.NO_ROOT;
        return out.contains("gone") ? Result.STOPPED : Result.STILL_RUNNING;
    }

    /**
     * Uninstalls {@code pkg} for user 0: root may remove any app without the system's confirmation screen, which a
     * privileged launcher may not (Android 11+ only lets the installer of record do that).
     */
    static Result uninstall(String pkg) {
        if (!safe(pkg) || pkg.contains("/")) return Result.FAILED;
        String out = Root.run("pm uninstall --user 0 " + pkg);
        if (out == null) return Result.NO_ROOT;
        return out.contains("Success") ? Result.UNINSTALLED : Result.FAILED;
    }

    /**
     * Allows the launcher's own notification listener (MediaListener, for the mini player's media sessions). The
     * system keeps its own list, so writing enabled_notification_listeners is not enough.
     */
    static boolean allowListener(Context ctx, ComponentName own) {
        if (!ctx.getPackageName().equals(own.getPackageName())) return false;
        String c = own.flattenToString();
        if (!safe(c)) return false;
        String out = Root.run("cmd notification allow_listener " + c + " && echo ok");
        return out != null && out.contains("ok");
    }

    /** Gives the assistant role to this launcher (true) or back to Google's assistant. */
    static boolean setAssistant(Context ctx, boolean launcher) {
        String pkg = launcher ? ctx.getPackageName() : GOOGLE_ASSISTANT;
        if (!safe(pkg) || pkg.contains("/")) return false;
        String out = Root.run("cmd role add-role-holder android.app.role.ASSISTANT " + pkg
                + " && cmd role get-role-holders android.app.role.ASSISTANT");
        return out != null && out.contains(pkg);
    }
}
