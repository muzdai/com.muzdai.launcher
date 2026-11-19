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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
        // Back while voice search listens over a player: cancel it instead of going back in the player.
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && VoiceOverPlayer.busy()) {
            if (event.getAction() == KeyEvent.ACTION_UP) main.post(() -> VoiceOverPlayer.cancel(this));
            return true;
        }
        // Media keys go to Roon while it plays from here and no app on the TV plays (RoonPlay.mediaKey).
        if (RoonPlay.mediaKey(this, event)) return true;
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
            long since = -1;

            @Override
            public void run() {
                AccessibilityNodeInfo root = s.getRootInActiveWindow();
                long now = SystemClock.uptimeMillis();
                boolean front = root != null && pkg.contentEquals(root.getPackageName());
                if (front && since < 0) since = now;
                if (!front) since = -1;
                // Loaded: some text on it, or in front for a while (a full screen player at rest shows two lines).
                if (front && (texts(root, LOADED_TEXTS) >= LOADED_TEXTS || now - since >= 2 * SETTLE_MS)) {
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
    /** The package of the window in front, or null without the helper. */
    static String frontPackage() {
        InputKeyService s = running;
        if (s == null) return null;
        AccessibilityNodeInfo root = s.getRootInActiveWindow();
        return root == null || root.getPackageName() == null ? null : root.getPackageName().toString();
    }

    /** press(): a click target given by view id (a Compose testTag) instead of text, as "id:player-card". */
    static final String BY_ID = "id:";
    /**
     * press(): no node to click, the remote's OK once the page has settled (Spotify draws its album page without
     * accessibility nodes, with focus on Play).
     */
    static final String PRESS_OK = "#ok";

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
                    if (PRESS_OK.equals(text)) {
                        if (now - since >= 2 * SETTLE_MS && android.os.Build.VERSION.SDK_INT >= 33
                                && s.performGlobalAction(GLOBAL_ACTION_DPAD_CENTER)) return;
                        if (now < until) s.main.postDelayed(this, POLL_MS);
                        return;
                    }
                    AccessibilityNodeInfo target = text.isEmpty()
                            ? (now - since >= SETTLE_MS ? root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) : null)
                            : text.startsWith(BY_ID) ? byViewId(root, text.substring(BY_ID.length()))
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
     * Qobuz has no links, so voice search works its screens: back out of the full screen player if it is up, open
     * Search (the "navbar-search" tab), write {@code query} into the search field (results show as it changes) and press
     * the first track ("Track • Artist • Album"), or with {@code album} the album card whose title is in the query (else
     * the first album) and then its focused Play. Last, the mini player at the top ("player-card") opens the full screen
     * player. Gives up after the timeout.
     */
    static void qobuz(String pkg, String query, boolean album, long timeoutMs) {
        InputKeyService s = running;
        if (s == null) return;
        long until = SystemClock.uptimeMillis() + timeoutMs;
        s.main.post(new Runnable() {
            static final int FIND_SEARCH = 0, TYPE = 1, PICK = 2, RELEASES = 5, PLAY_ALBUM = 3, FULL_SCREEN = 4;
            int step = FIND_SEARCH, backs;
            long stepAt = SystemClock.uptimeMillis();

            void next(int to) {
                step = to;
                stepAt = SystemClock.uptimeMillis();
            }

            @Override
            public void run() {
                AccessibilityNodeInfo root = s.getRootInActiveWindow();
                long now = SystemClock.uptimeMillis(), inStep = now - stepAt;
                if (root != null && pkg.contentEquals(root.getPackageName())) {
                    switch (step) {
                        case FIND_SEARCH: {
                            AccessibilityNodeInfo tab = byViewId(root, "navbar-search");
                            if (editText(root) != null) {
                                next(TYPE);
                            } else if (tab != null) {
                                click(tab);
                            } else if (inStep >= SETTLE_MS && backs < 6) {
                                // The full screen player, or an album page under it, has no tab bar: back out.
                                s.performGlobalAction(GLOBAL_ACTION_BACK);
                                backs++;
                                stepAt = now;
                            }
                            break;
                        }
                        case TYPE: {
                            AccessibilityNodeInfo field = editText(root);
                            android.os.Bundle args = new android.os.Bundle();
                            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, query);
                            if (field != null && field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) next(PICK);
                            break;
                        }
                        case PICK: {
                            // Not sooner: results of an earlier search may still be on screen.
                            if (inStep < SETTLE_MS) break;
                            if (album) {
                                // Top results may leave the album out; the Releases tab lists the albums.
                                AccessibilityNodeInfo tab = find(root, "Releases");
                                if (tab == null) tab = find(root, "Релизы");
                                if (tab != null && click(tab)) next(RELEASES);
                                break;
                            }
                            AccessibilityNodeInfo hit = bestTrack(root, query);
                            if (hit != null && click(hit)) next(FULL_SCREEN);
                            break;
                        }
                        case RELEASES: {
                            if (inStep < SETTLE_MS) break;
                            AccessibilityNodeInfo hit = bestRelease(root, query);
                            if (hit != null && click(hit)) next(PLAY_ALBUM);
                            break;
                        }
                        case PLAY_ALBUM: {
                            // The album page opens with focus on its Play button.
                            if (inStep < SETTLE_MS || byViewId(root, "navbar-search") != null) break;
                            AccessibilityNodeInfo play = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                            if (play != null && click(play)) next(FULL_SCREEN);
                            break;
                        }
                        case FULL_SCREEN: {
                            if (inStep < SETTLE_MS) break;
                            AccessibilityNodeInfo card = byViewId(root, "player-card");
                            if (card != null && click(card)) return;
                            break;
                        }
                        default:
                            return;
                    }
                }
                if (now < until) s.main.postDelayed(this, POLL_MS);
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

    private static final String[] TRACK = {"track •", "трек •"}, ALBUM = {"album •", "альбом •"};

    /**
     * The track result whose title is in the query, the longest such title when several are ("Wish You Were Here"
     * over a track of the album of that name), the higher one on a tie; the first track when none is. The title is the text next to the
     * card's "Track • Artist • Album" label.
     */
    private static AccessibilityNodeInfo bestTrack(AccessibilityNodeInfo root, String query) {
        List<AccessibilityNodeInfo> labels = new ArrayList<>();
        collectLabelled(root, TRACK, labels);
        AccessibilityNodeInfo best = null;
        int bestLength = -1;
        for (AccessibilityNodeInfo label : labels) {
            String title = siblingTitle(label);
            if (title == null || !titleInQuery(title, query)) continue;
            // Without a "(Live …)" tail: the studio "Time" and a live "Time" tie, and Qobuz's order decides.
            int length = squash(title.replaceAll("\\s*[(\\[].*$", "")).length();
            if (length > bestLength) {
                best = label;
                bestLength = length;
            }
        }
        return best != null ? best : labels.isEmpty() ? null : labels.get(0);
    }

    /** The text beside a result's label in the same card: its title. */
    private static String siblingTitle(AccessibilityNodeInfo label) {
        AccessibilityNodeInfo parent = label.getParent();
        if (parent == null) return null;
        for (int i = 0; i < parent.getChildCount(); i++) {
            AccessibilityNodeInfo c = parent.getChild(i);
            if (c != null && c.getText() != null && c.getText().length() > 0 && !isLabel(c.getText(), TRACK)
                    && !isLabel(c.getText(), ALBUM)) {
                return c.getText().toString();
            }
        }
        return null;
    }

    private static void collectLabelled(AccessibilityNodeInfo node, String[] kind, List<AccessibilityNodeInfo> out) {
        if (node == null) return;
        if (isLabel(node.getText(), kind)) out.add(node);
        for (int i = 0; i < node.getChildCount(); i++) collectLabelled(node.getChild(i), kind, out);
    }

    /**
     * On the Releases tab, the album whose title (and best also its artist) is in the query: the cards are clickable
     * with the title and the artist as their first two texts. The first card when none matches; the "player-card"
     * at the top is not a result.
     */
    private static AccessibilityNodeInfo bestRelease(AccessibilityNodeInfo root, String query) {
        List<AccessibilityNodeInfo> cards = new ArrayList<>();
        collectCards(root, cards);
        AccessibilityNodeInfo titleOnly = null;
        for (AccessibilityNodeInfo card : cards) {
            List<String> t = childTexts(card);
            if (!titleInQuery(t.get(0), query)) continue;
            if (titleInQuery(t.get(1), query)) return card;
            if (titleOnly == null) titleOnly = card;
        }
        return titleOnly != null ? titleOnly : cards.isEmpty() ? null : cards.get(0);
    }

    private static void collectCards(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out) {
        if (node == null) return;
        String id = node.getViewIdResourceName();
        if (id != null && id.endsWith("player-card")) return;
        if (node.isClickable() && childTexts(node).size() >= 2) {
            out.add(node);
            return;
        }
        for (int i = 0; i < node.getChildCount(); i++) collectCards(node.getChild(i), out);
    }

    /** The texts of node's direct children, in order. */
    private static List<String> childTexts(AccessibilityNodeInfo node) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo c = node.getChild(i);
            if (c != null && c.getText() != null && c.getText().length() > 0) out.add(c.getText().toString());
        }
        return out;
    }

    /** "The Wall" in "Pink Floyd The Wall", ignoring case, punctuation and a "(Remastered)" tail. */
    static boolean titleInQuery(String title, String query) {
        String t = squash(title.replaceAll("\\s*[(\\[].*$", ""));
        return !t.isEmpty() && (" " + squash(query) + " ").contains(" " + t + " ");
    }

    private static String squash(String s) {
        return s.toLowerCase(java.util.Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    static boolean isLabel(CharSequence s, String[] kind) {
        if (s == null) return false;
        String v = s.toString().trim().toLowerCase(java.util.Locale.ROOT);
        for (String k : kind) if (v.startsWith(k)) return true;
        return false;
    }

    static boolean isTrackLabel(CharSequence s) {
        return isLabel(s, TRACK);
    }

    static boolean isAlbumLabel(CharSequence s) {
        return isLabel(s, ALBUM);
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
