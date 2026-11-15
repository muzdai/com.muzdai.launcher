package com.muzdai.launcher;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.util.Log;
import android.widget.Toast;

/**
 * Uninstalls an app from the TV after the launcher's own confirmation. First through the TV's own ADB (LocalAdb),
 * where the shell removes it without another screen; Android only lets the installer of record uninstall silently,
 * so even a privileged launcher with DELETE_PACKAGES would get the system's confirmation. Without ADB it falls back to
 * PackageInstaller (DELETE_PACKAGES) or the system's uninstall screen, which ask once more.
 */
final class AppUninstaller {
    private static final String TAG = "VPro.Uninstall";
    private static final String ACTION_RESULT = "com.muzdai.launcher.action.UNINSTALL_RESULT";
    private static final String EXTRA_LABEL = "label";

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

    static boolean silent(Context ctx) {
        return ctx.checkSelfPermission(android.Manifest.permission.DELETE_PACKAGES) == PackageManager.PERMISSION_GRANTED;
    }

    /** Uninstalls {@code pkg}; the caller has already asked the owner. A toast reports the result. */
    static void uninstall(Activity activity, String pkg, String label) {
        Context app = activity.getApplicationContext();
        android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        new Thread(() -> {
            LocalAdb.Result r = LocalAdb.uninstall(app, pkg);
            main.post(() -> {
                if (r == LocalAdb.Result.UNINSTALLED) {
                    Toast.makeText(app, app.getString(R.string.uninstall_done, label), Toast.LENGTH_LONG).show();
                } else {
                    Log.w(TAG, "adb uninstall " + pkg + ": " + r);
                    if (!activity.isFinishing() && !activity.isDestroyed()) withInstaller(activity, pkg, label);
                }
            });
        }, "uninstall").start();
    }

    private static void withInstaller(Activity activity, String pkg, String label) {
        Context app = activity.getApplicationContext();
        if (!silent(app)) {
            try {
                activity.startActivity(new Intent(Intent.ACTION_DELETE, Uri.fromParts("package", pkg, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException e) {
                Toast.makeText(app, app.getString(R.string.uninstall_failed, label), Toast.LENGTH_LONG).show();
            }
            return;
        }
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
                if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                    if (confirm != null) {
                        try {
                            c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        } catch (RuntimeException e) {
                            Log.w(TAG, "no confirmation screen: " + e);
                        }
                    }
                    return;   // the final status arrives on this receiver again
                }
                c.unregisterReceiver(this);
                boolean ok = status == PackageInstaller.STATUS_SUCCESS;
                if (!ok) Log.w(TAG, pkg + ": " + intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
                Toast.makeText(c, c.getString(ok ? R.string.uninstall_done : R.string.uninstall_failed, label),
                        Toast.LENGTH_LONG).show();
            }
        };
        String action = ACTION_RESULT + "." + pkg;
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(receiver, new IntentFilter(action), Context.RECEIVER_NOT_EXPORTED);
        } else {
            app.registerReceiver(receiver, new IntentFilter(action));
        }
        Intent result = new Intent(action).setPackage(app.getPackageName()).putExtra(EXTRA_LABEL, label);
        PendingIntent pending = PendingIntent.getBroadcast(app, pkg.hashCode(), result,
                PendingIntent.FLAG_UPDATE_CURRENT
                        | (android.os.Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0));
        try {
            app.getPackageManager().getPackageInstaller().uninstall(pkg, pending.getIntentSender());
        } catch (RuntimeException e) {
            app.unregisterReceiver(receiver);
            Log.w(TAG, "uninstall " + pkg, e);
            Toast.makeText(app, app.getString(R.string.uninstall_failed, label), Toast.LENGTH_LONG).show();
        }
    }
}
