package com.muzdai.launcher;

import android.accessibilityservice.AccessibilityService;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
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

    /** The running helper, for voice search's presses in other apps (VoiceRun); null while it is off. Main thread. */
    private static InputKeyService running;
    private final Handler main = new Handler(Looper.getMainLooper());
    /** SETTLE_MS: a page's focus is final, or an app whose first page is drawn takes links. */
    private static final long POLL_MS = 400, SETTLE_MS = 1500;
    /** Texts on screen that mean an app's first page has loaded, not only its splash. */
    private static final int LOADED_TEXTS = 5;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        running = this;
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
        if (running == this) running = null;
        RemoteKeys.release(this);
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        if (running == this) running = null;
        main.removeCallbacksAndMessages(null);
        RemoteKeys.release(this);
        super.onDestroy();
    }

    // ---- voice search: pressing in other apps ---------------------------------------------------------
    // The screen is read only while voice search waits for a page (canRetrieveWindowContent, no event types): a short
    // poll of the window in front, then nothing.

    /**
     * Runs {@code then} once {@code pkg}'s first page has loaded (its window in front, with some text on it); false
     * without the helper. Deezer crashes on a link that arrives while it is still starting.
     */
    static boolean whenShown(String pkg, long timeoutMs, Runnable then) {
        InputKeyService s = running;
        if (s == null) return false;
        long until = SystemClock.uptimeMillis() + timeoutMs;
        s.main.post(new Runnable() {
            @Override
            public void run() {
                AccessibilityNodeInfo root = s.getRootInActiveWindow();
                if (root != null && pkg.contentEquals(root.getPackageName()) && texts(root, LOADED_TEXTS) >= LOADED_TEXTS) {
                    s.main.postDelayed(then, SETTLE_MS);
                } else if (SystemClock.uptimeMillis() < until) {
                    s.main.postDelayed(this, POLL_MS);
                }
            }
        });
        return true;
    }

    /**
     * Once {@code pkg}'s page is in front, presses its focused button ({@code text} empty: Play, Watch, Continue) or the
     * row whose text or description is {@code text} or starts with "{@code text},". Gives up after the timeout.
     */
    static void press(String pkg, String text, long timeoutMs) {
        InputKeyService s = running;
        if (s == null) return;
        long until = SystemClock.uptimeMillis() + timeoutMs;
        s.main.postDelayed(new Runnable() {
            long since = -1;

            @Override
            public void run() {
                AccessibilityNodeInfo root = s.getRootInActiveWindow();
                long now = SystemClock.uptimeMillis();
                if (root != null && pkg.contentEquals(root.getPackageName())) {
                    if (since < 0) since = now;
                    // The focused button only once the page has settled: before that focus may still be on a menu.
                    AccessibilityNodeInfo target = text.isEmpty()
                            ? (now - since >= SETTLE_MS ? root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) : null)
                            : find(root, text);
                    if (target != null && click(target)) return;
                } else {
                    since = -1;
                }
                if (now < until) s.main.postDelayed(this, POLL_MS);
            }
        }, POLL_MS);
    }

    /**
     * Qobuz has no links: in its window, opens Search (the "navbar-search" tab), writes {@code query} into the search
     * field and, once results show, presses the first track ("Track • Artist • Album"), which starts it, as OK does.
     * Gives up after the timeout.
     */
    static void searchAndPlayTrack(String pkg, String query, long timeoutMs) {
        InputKeyService s = running;
        if (s == null) return;
        long until = SystemClock.uptimeMillis() + timeoutMs;
        s.main.post(new Runnable() {
            int step;
            long typedAt;

            @Override
            public void run() {
                AccessibilityNodeInfo root = s.getRootInActiveWindow();
                if (root != null && pkg.contentEquals(root.getPackageName())) {
                    if (step == 0) {
                        AccessibilityNodeInfo field = editText(root);
                        if (field != null) {
                            step = 1;
                        } else {
                            AccessibilityNodeInfo tab = byViewId(root, "navbar-search");
                            if (tab != null) click(tab);
                        }
                    }
                    if (step == 1) {
                        AccessibilityNodeInfo field = editText(root);
                        android.os.Bundle args = new android.os.Bundle();
                        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, query);
                        if (field != null && field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                            step = 2;
                            typedAt = SystemClock.uptimeMillis();
                        }
                    } else if (step == 2 && SystemClock.uptimeMillis() - typedAt >= SETTLE_MS) {
                        // Not sooner: results of an earlier search may still be on screen.
                        AccessibilityNodeInfo track = firstTrack(root);
                        if (track != null && click(track)) return;
                    }
                }
                if (SystemClock.uptimeMillis() < until) s.main.postDelayed(this, POLL_MS);
            }
        });
    }

    private static AccessibilityNodeInfo editText(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.getClassName() != null && "android.widget.EditText".contentEquals(node.getClassName())) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo hit = editText(node.getChild(i));
            if (hit != null) return hit;
        }
        return null;
    }

    private static AccessibilityNodeInfo byViewId(AccessibilityNodeInfo node, String id) {
        if (node == null) return null;
        String v = node.getViewIdResourceName();
        if (v != null && (v.equals(id) || v.endsWith("/" + id))) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo hit = byViewId(node.getChild(i), id);
            if (hit != null) return hit;
        }
        return null;
    }

    /** The first search result labelled as a track, in screen order. */
    private static AccessibilityNodeInfo firstTrack(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (isTrackLabel(node.getText())) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo hit = firstTrack(node.getChild(i));
            if (hit != null) return hit;
        }
        return null;
    }

    static boolean isTrackLabel(CharSequence s) {
        if (s == null) return false;
        String v = s.toString().trim().toLowerCase(java.util.Locale.ROOT);
        return v.startsWith("track •") || v.startsWith("трек •");
    }

    /** How many nodes under node show some text or description, counting up to max. */
    private static int texts(AccessibilityNodeInfo node, int max) {
        if (node == null) return 0;
        int n = (node.getText() != null && node.getText().length() > 0)
                || (node.getContentDescription() != null && node.getContentDescription().length() > 0) ? 1 : 0;
        for (int i = 0; i < node.getChildCount() && n < max; i++) n += texts(node.getChild(i), max - n);
        return n;
    }

    private static AccessibilityNodeInfo find(AccessibilityNodeInfo node, String text) {
        if (node == null) return null;
        if (matches(node.getContentDescription(), text) || matches(node.getText(), text)) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo hit = find(node.getChild(i), text);
            if (hit != null) return hit;
        }
        return null;
    }

    static boolean matches(CharSequence s, String text) {
        if (s == null) return false;
        String v = s.toString().trim();
        return v.equalsIgnoreCase(text) || v.regionMatches(true, 0, text + ",", 0, text.length() + 1);
    }

    /** Clicks the node, or the nearest ancestor that takes a click. */
    private static boolean click(AccessibilityNodeInfo node) {
        for (AccessibilityNodeInfo n = node; n != null; n = n.getParent()) {
            if (n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        }
        return false;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }
}
