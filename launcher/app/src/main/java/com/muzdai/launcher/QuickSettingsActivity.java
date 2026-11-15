package com.muzdai.launcher;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * What the remote's Settings button and the on-screen gear open (app_keys 83, settings_key_panel). It used to be a
 * small panel of shortcuts; now it goes straight to Launcher settings, whose first row is the TV's own settings.
 * Through Home, so Home rebuilds with the new choices when the settings close.
 */
public final class QuickSettingsActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        startActivity(new Intent(this, MainActivity.class).setAction(MainActivity.ACTION_LAUNCHER_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        finish();
        overridePendingTransition(0, 0);
    }
}
