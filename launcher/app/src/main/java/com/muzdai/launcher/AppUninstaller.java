package com.muzdai.launcher;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.util.Log;
import android.widget.Toast;

/**
 * Uninstalls an app from the TV after the launcher's own confirmation. First as root (RootCommands),
 * which removes it without another screen; without root, the system's uninstall screen, which asks once more.
 */
final class AppUninstaller {
    private static final String TAG = "VPro.Uninstall";

    private AppUninstaller() { }

    /** Only apps the owner installed: a preinstalled system app cannot be removed, only its updates. */
    static boolean canUninstall(Context ctx, String pkg) {
        if (pkg == null || pkg.equals(ctx.getPackageName())) return false;
        try {
            ApplicationInfo info = ctx.getPackageManager().getApplicationInfo(pkg, 0);
            return (info.flags & ApplicationInfo.FLAG_SYSTEM) == 0;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** Uninstalls {@code pkg}; the caller has already asked the owner. A toast reports the result. */
    static void uninstall(Activity activity, String pkg, String label) {
        Context app = activity.getApplicationContext();
        android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        new Thread(() -> {
            RootCommands.Result r = RootCommands.uninstall(pkg);
            main.post(() -> {
                if (r == RootCommands.Result.UNINSTALLED) {
                    Toast.makeText(app, app.getString(R.string.uninstall_done, label), Toast.LENGTH_LONG).show();
                } else {
                    Log.w(TAG, "root uninstall " + pkg + ": " + r);
                    if (!activity.isFinishing() && !activity.isDestroyed()) withInstaller(activity, pkg, label);
                }
            });
        }, "uninstall").start();
    }

    /** Without root: the system's uninstall screen, which asks once more. */
    private static void withInstaller(Activity activity, String pkg, String label) {
        Context app = activity.getApplicationContext();
        try {
            activity.startActivity(new Intent(Intent.ACTION_DELETE, Uri.fromParts("package", pkg, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            Toast.makeText(app, app.getString(R.string.uninstall_failed, label), Toast.LENGTH_LONG).show();
        }
    }
}
