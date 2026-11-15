package com.muzdai.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;

/** Intents for the TV's own settings screens. */
final class SystemSettings {
    private SystemSettings() {}

    /** The standard settings intent for this action (Google TV's com.android.tv.settings answers them all). */
    static Intent intent(Context context, String action) {
        return new Intent(action);
    }

    /** The TV's App info page for pkg, or null where the firmware has none. */
    static Intent appInfo(Context context, String pkg) {
        Intent info = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg));
        return context.getPackageManager().resolveActivity(info, PackageManager.MATCH_DEFAULT_ONLY) != null ? info : null;
    }
}
