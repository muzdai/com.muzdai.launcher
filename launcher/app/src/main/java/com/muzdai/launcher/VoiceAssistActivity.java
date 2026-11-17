package com.muzdai.launcher;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/**
 * The remote's microphone button, once this launcher holds the assistant role (Launcher settings → Voice search).
 * In Bluetooth mode the Google remote service calls SearchManager.launchAssist, in IR mode the button is
 * KEYCODE_ASSIST: both start the assistant's ACTION_ASSIST activity, this one. No window: it hands over to the same
 * voice search as a remote button set to "Voice search" (MainActivity.voiceKey), with the app that was on screen
 * (Intent.EXTRA_ASSIST_PACKAGE), and finishes.
 */
public class VoiceAssistActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        start(getIntent());
        finish();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        start(intent);
        finish();
    }

    private void start(Intent intent) {
        String onScreen = intent == null ? null : intent.getStringExtra(Intent.EXTRA_ASSIST_PACKAGE);
        Context app = getApplicationContext();
        // After this activity is gone, so Home (or the player) is in front again when listening starts.
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                () -> MainActivity.voiceKey(app, onScreen), 150);
    }
}
