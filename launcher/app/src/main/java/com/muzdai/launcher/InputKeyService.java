package com.muzdai.launcher;

import android.accessibilityservice.AccessibilityService;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import java.util.Collections;
import java.util.Map;

/**
 * The remote's buttons that only the stock Google TV launcher answered, from any app. The Input button (KEYCODE_TV or
 * KEYCODE_TV_INPUT) and the buttons in input_key_keycodes (config.xml) open the input switcher; app_keys and the
 * owner's choices in Launcher settings (ForkKeys) open an app or run an action. Every other key passes through
 * untouched. Enabled once over ADB: settings put secure enabled_accessibility_services
 * com.muzdai.launcher/.InputKeyService.
 */
public class InputKeyService extends AccessibilityService {

    private static final String ACTION_VIEW_INPUTS = "com.android.tv.action.VIEW_INPUTS";

    private int[] inputKeycodes = new int[0];
    private Map<Integer, String> appKeys = Collections.emptyMap();
    private Map<Integer, String> appScancodes = Collections.emptyMap();
    private Map<Integer, String> holdAppKeys = Collections.emptyMap();
    private Map<Integer, String> holdAppScancodes = Collections.emptyMap();

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Config config = Config.load(this);
        inputKeycodes = config.inputKeycodes;
        appKeys = RemoteKeys.parseAppKeys(config.appKeys);
        appScancodes = RemoteKeys.parseAppScancodes(config.appKeys);
        RemoteKeys.applyKeyFilter(this, config.keyFilter);
        holdAppKeys = RemoteKeys.parseHoldAppKeys(config.appKeys);
        holdAppScancodes = RemoteKeys.parseHoldAppScancodes(config.appKeys);
    }

    private boolean isInputKey(int keyCode) {
        if (keyCode == KeyEvent.KEYCODE_TV || keyCode == KeyEvent.KEYCODE_TV_INPUT) return true;
        for (int code : inputKeycodes) if (keyCode == code) return true;
        return false;
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        // TCL Channel is voice search, whatever app is in front.
        if (event.getKeyCode() == ForkKeys.VOICE_KEY) return RemoteKeys.handle(this, event, RemoteKeys.VOICE, null);
        // vPro: the owner's choice for this button in Launcher settings wins over config.xml app_keys.
        String chosen = ForkKeys.target(this, event.getKeyCode());
        if (chosen != null && RemoteKeys.handle(this, event, chosen, null)) return true;
        // A scancode entry is more specific than any keycode, so it goes first.
        if (RemoteKeys.handle(this, event, appScancodes.get(event.getScanCode()),
                holdAppScancodes.get(event.getScanCode()))) return true;
        if (RemoteKeys.handle(this, event, appKeys.get(event.getKeyCode()),
                holdAppKeys.get(event.getKeyCode()))) return true;
        if (!isInputKey(event.getKeyCode())) return false;
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
            try {
                startActivity(new Intent(ACTION_VIEW_INPUTS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (ActivityNotFoundException ignored) {
            }
        }
        return true;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        RemoteKeys.release(this);
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        RemoteKeys.release(this);
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }
}
