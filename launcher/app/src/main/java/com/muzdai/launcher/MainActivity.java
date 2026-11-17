package com.muzdai.launcher;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ChangedPackages;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.SoundEffectConstants;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.AnimationUtils;
import android.view.animation.LayoutAnimationController;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * tvOS style home: black, a pinned dock row, an app grid, and one glass highlight that springs between tiles.
 */
public class MainActivity extends Activity implements AppAdapter.TileListener {
    private static final int LAUNCHER_SETTINGS_REQUEST = 42;
    /** Sent by QuickSettingsActivity: open Launcher settings from here so Home rebuilds when they close. */
    static final String ACTION_LAUNCHER_SETTINGS = "com.muzdai.launcher.action.LAUNCHER_SETTINGS";
    /** Sent by the "library" action word of app_keys: open the App library over Home (needs app_library). */
    static final String ACTION_APP_LIBRARY = "com.muzdai.launcher.action.APP_LIBRARY";
    /** The "inputs" action word of app_keys: open the Inputs menu over Home. */
    static final String ACTION_VIEW_INPUTS = "com.android.tv.action.VIEW_INPUTS";
    /** Tiles per row and dock slots (res/values/config.xml, columns). Set in onCreate. */
    static int COLUMNS = 4;
    private static final long MINUTE_MS = 60_000L;
    /**
     * 2025-01-01. TVs without a clock battery boot at the epoch (08:00 in China) and keep it until network
     * time arrives; the clock, date and greeting stay hidden before then.
     */
    private static final long TIME_VALID_AFTER_MS = 1_735_689_600_000L;
    private String liveTvPkg;
    /** Launcher settings' change count this Home was built with (see onResume). */
    private long settingsRevision;
    /** The app library is on (config.xml, app_library): only then can the tile menu take an app off Home. */
    private boolean appLibraryEnabled;
    /** The same opt-in menu is available from either hold-OK or the remote's Menu key. */
    private boolean tileMenuEnabled;

    private AppAdapter adapter;
    private RecyclerView grid;
    private FrameLayout root;
    /** What an app plays behind Home, above the top bar's round buttons. */
    private MiniPlayer miniPlayer;
    private WallpaperPlayer wallpaperPlayer;
    private GlassView glass;
    private TextView heroName;
    /** The top shelf: the focused dock app's name above the dock. */
    private View hero;
    private boolean heroShown;
    private boolean animations;
    private boolean advancedEffects;
    private TextView greeting;
    private View clockBlock;
    private View btnInputs;
    /** config show_inputs_pill: false keeps the Inputs pill hidden even when the TV has inputs. */
    private boolean showInputsPill = true;
    private TextView btnWifi;
    /** The network pill's icon as last set, so a refresh that changes nothing does not lay the top bar out again. */
    private int wifiIcon;
    /** The pills have been filled in once; later resumes refresh them after the first frame. */
    private boolean pillsShown;
    /** config.xml inputs_connected_only: the Inputs menu lists only connected inputs, the rest behind "More inputs". */
    private boolean inputsConnectedOnly;
    /** config.xml inputs_cec_state_only: only CEC device rows show a connection state, bare HDMI ports none. */
    private boolean inputsCecStateOnly;
    /** greeting_name: said after the greeting, which is shown in capitals. */
    private String greetingName = "";
    private SpringAnimation springX, springY;
    private float glassTargetX, glassTargetY;
    // How far the grid has scrolled the glass. springY works without it, in grid content space, so a
    // scroll moves the glass in lockstep with its tile instead of becoming a moving spring target.
    private float glassScrollShift;
    private int glassCardW;   // width of the tile the glass was last fitted to
    /**
     * The focused tile's rect in root coordinates as {@link #onTileFocused} last fitted the glass to it. The size
     * alone cannot say whether the glass is on its tile: a cold start can run onTileFocused once the tile has its
     * final width but before the grid has its top padding, which leaves the right size at the old Y.
     */
    private final Rect glassCardRect = new Rect();
    private boolean glassShown;
    private final Rect tileRect = new Rect();
    private final Rect revealRect = new Rect();
    // Static so they survive the activity being destroyed while another app is open (low-memory TVs,
    // or Developer options → "Don't keep activities"); they only reset when the process dies.
    private static int lastFocused = 0;
    /** App of the last focused tile, so focus follows it when the app list changes. */
    private static String lastFocusedKey;
    private float density;
    /** config.xml dock_row: the first row is a separate dock (band, parked low, top shelf) rather than plain grid. */
    private boolean dockRow;
    private int greetingColor;
    /** open_on_start_choice: the app picked in Launcher settings may open on the first Home of each boot. */
    private boolean openOnStartChoice;
    /** open_on_start_network_wait, in ms; the boot's app while it waits for the network, and until when. */
    private long openOnStartWaitMs;
    /** open_on_start_on_wake: wakeReceiver is registered for this activity's life. */
    private boolean openOnStartOnWake;
    private volatile Intent startPending;
    private long startDeadline;
    /** Opens the boot's app once the network is validated (networkCallback posts this) or the wait is over. */
    private final Runnable openWhenOnline = new Runnable() {
        @Override
        public void run() {
            if (startPending == null) return;
            if (!OpenOnStart.opensNow(OpenOnStart.online(MainActivity.this), SystemClock.elapsedRealtime(),
                    startDeadline)) return;
            handler.removeCallbacks(this);   // the deadline's run, when the network came first
            Intent i = startPending;
            startPending = null;
            open(i);
        }
    };
    private final BroadcastReceiver wakeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            openStartAppOnWake();
        }
    };
    /** vPro: menu_opacity and library_button_left (ForkConfig). */
    private ForkConfig fork;
    /** vPro, InfoWidgets: NBG rates next to the greeting, the weather under the clock. */
    private TextView ratesView, weatherNow, weatherDays;
    private NewsTicker newsTicker;
    private final Runnable infoTick = new Runnable() {
        @Override
        public void run() {
            refreshInfoWidgets();
            // Weather and rates keep their own ages; the tick follows the news interval when that is shorter.
            long every = Math.min(10, InfoWidgets.newsEveryMinutes(MainActivity.this)) * 60_000L;
            if (root != null) root.postDelayed(this, every);
        }
    };
    /** library_excluded_apps: packages the App library catalogue never offers. */
    private Set<String> libraryExcludedApps = Collections.emptySet();
    /** The grid has been laid out and focused since the last resume; a reorder asked for by Launcher settings waits for this. */
    private boolean tilesReady;
    private boolean settingsRecreationPending;
    /** Between onStop and onStart: Home was fully covered by an app, not just by the translucent quick panel. */
    private boolean stopped;
    /** Between onStart and onStop: Home is (at least partly) on screen. */
    private boolean started;
    /** Between onResume and onPause: Home is in front. */
    private boolean resumed;
    /** Rebuild the tiles on the way back to Home only when apps changed meanwhile (TileRefresh). */
    private final TileRefresh tileRefresh = new TileRefresh();
    /** PackageManager's change counter at the last rebuild, to catch a package broadcast that never arrived. */
    private int packageSequence;
    /** The Inputs dropdown or a menu while one is open, so onPause can close it. */
    private PopupWindow openPopup;
    /** Reorder mode is on. */
    private boolean reordering;
    /** Key of the tile being carried in reorder mode, or null. */
    private String movingKey;
    /** Tile order when reorder mode started, to save only when something moved. */
    private List<String> orderAtStart;
    /**
     * fill_dock false: tiles in the dock when reorder mode started, plus those carried or swapped into it since
     *. A grid app that only slid into a free slot, which reorder mode does not show, is not one of them.
     */
    private Set<String> dockInSession;
    /** fill_dock false: how many dock slots held an app when reorder mode started. */
    private int dockedAtStart;
    /** dock_apps as loaded (config.xml): a saved Order only applies while these are unchanged. */
    private List<String> dockApps;
    /** fill_dock false: the dock can have empty slots, which reorder mode leaves out and restores afterwards. */
    private boolean fillDockLeavesSlots;
    /** Edit-dock mode (Settings menu): a reorder session that swaps one chosen app into one dock place. */
    private boolean dockEditing;
    /** Dock place being changed in edit-dock mode, or -1 while the place is still being chosen. */
    private int dockEditSlot = -1;
    /** Tile menu's "Add to dock": the app waiting for a dock place in edit-dock mode, or null. */
    private String dockEditAppKey;
    private AppLibraryDialog appLibrary;
    private boolean appLibrarySelection;
    private boolean libraryOkOpens;
    private boolean libraryFocusPending;
    private RecyclerView.OnScrollListener pendingFocusScroll;

    private final Handler handler = new Handler(Looper.getMainLooper());
    /** Refreshes the network pill when the connection changes: at boot Wi-Fi often connects after Home is shown. */
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }

        @Override
        public void onLost(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }

        /** wifi_status_warnings follows validation; signal-strength updates also land here, so skip those. */
        private boolean lastValidated;

        @Override
        public void onCapabilitiesChanged(@NonNull Network network, @NonNull NetworkCapabilities caps) {
            boolean validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
            // open_on_start_network_wait: the boot's app opens on the first validated network.
            if (validated && startPending != null) handler.post(openWhenOnline);
            if (validated == lastValidated) return;
            lastValidated = validated;
            if (wifiNoInternetTint != null) handler.post(MainActivity.this::updateWifiLabel);
        }
    };
    /** Shows the Inputs pill once the TV registers an input: at boot HDMI inputs can appear after Home is shown. */
    private final TvInputManager.TvInputCallback inputCallback = new TvInputManager.TvInputCallback() {
        @Override
        public void onInputAdded(String inputId) {
            updateInputsButton();
        }

        @Override
        public void onInputRemoved(String inputId) {
            updateInputsButton();
        }
    };
    private final IntentFilter packageFilter = new IntentFilter();
    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Updates send REMOVED followed by ADDED; keep the tile until the new APK is ready.
            if (Intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction())
                    && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;
            // In the background this only marks the tiles stale; onResume rebuilds them once.
            if (!tileRefresh.onPackagesChanged()) return;
            reloadApps(false);
            if (appLibrary != null) appLibrary.refresh();
        }
    };
    // Right after boot the clock is often wrong until network time arrives; don't wait a minute to catch up.
    private final IntentFilter timeFilter = new IntentFilter(Intent.ACTION_TIME_CHANGED);
    private final BroadcastReceiver timeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateClockVisibility();
            clockRefresh.start();   // the wall clock was set: line the redraw up with the new minute
        }
    };
    /** Keeps the Home clocks moving on Android before 12 when the firmware stops ACTION_TIME_TICK (see ClockRefresh). */
    private ClockRefresh clockRefresh;
    private final Runnable minuteTick = new Runnable() {
        @Override
        public void run() {
            updateGreeting();
            wallpaperPlayer.update();
            handler.postDelayed(this, MINUTE_MS);
        }
    };

    private java.util.Map<Integer, String> homeAppKeys = java.util.Collections.emptyMap();
    /** clock_format (the TextClocks take it in onCreate). */
    private String clockFormat = "system";
    private java.util.Map<Integer, String> homeHoldAppKeys = java.util.Collections.emptyMap();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        density = getResources().getDisplayMetrics().density;
        // config.xml plus the owner.s choices (see Config).
        Config cfg = Config.load(this);
        homeAppKeys = RemoteKeys.parseAppKeys(cfg.appKeys);
        clockFormat = cfg.clockFormat;
        ClockFormat.apply(getWindow().getDecorView(), clockFormat);
        homeHoldAppKeys = RemoteKeys.parseHoldAppKeys(cfg.appKeys);
        appLibrarySelection = cfg.appLibrarySelection;
        libraryOkOpens = cfg.libraryOkOpens;
        settingsRevision = settingsRevision();
        COLUMNS = cfg.columns;
        animations = cfg.animations;
        advancedEffects = cfg.animations && cfg.advancedEffects;
        dockRow = cfg.dockRow;
        liveTvPkg = cfg.liveTvPackage;
        openOnStartChoice = cfg.openOnStartChoice;
        openOnStartWaitMs = cfg.openOnStartNetworkWait * 1000L;
        openOnStartOnWake = OpenOnStart.onWake(cfg.openOnStartChoice, cfg.openOnStartOnWake);
        appLibraryEnabled = cfg.appLibrary;
        tileMenuEnabled = cfg.tileMenu;

        root = findViewById(R.id.root);
        root.setBackgroundColor(cfg.backgroundColor);
        // root fills the window with an opaque colour (Config keeps background_color opaque), so the window's own
        // black under it is a second full-screen fill on every frame for nothing. The starting window still uses
        // the configured background, so launch looks the same.
        getWindow().setBackgroundDrawable(null);
        wallpaperPlayer = new WallpaperPlayer(this, root, cfg);
        glass = findViewById(R.id.glass);
        glass.setStyle(cfg.glassStyle);
        heroName = findViewById(R.id.heroName);
        hero = findViewById(R.id.hero);
        // A group (the name in its frame): without this every fade of the shelf would render it through an
        // offscreen buffer first.
        hero.forceHasOverlappingRendering(false);
        // dock_title false: the name above the dock never shows.
        if (!cfg.dockTitle) hero.setVisibility(View.GONE);
        greeting = findViewById(R.id.greeting);
        clockBlock = findViewById(R.id.leftBlock);
        clockRefresh = new ClockRefresh(clockBlock);
        greetingColor = greeting.getCurrentTextColor();
        if (!cfg.showGreeting) greeting.setVisibility(View.GONE);
        // Keep the other header layouts as designed: their extra lines change the block's centre.
        if (!cfg.showGreeting && !cfg.dateBelowClock) centerCompactHeader();
        if (cfg.headerWhite) {
            // The clock's own white for the date and the line before it, instead of their dimmed whites.
            ((TextView) findViewById(R.id.date)).setTextColor(Color.WHITE);
            findViewById(R.id.clockDivider).setBackgroundColor(Color.WHITE);
        }
        btnWifi = findViewById(R.id.btnWifi);
        inputsConnectedOnly = cfg.inputsConnectedOnly;
        inputsCecStateOnly = cfg.inputsCecStateOnly;
        greetingName = cfg.greetingName;
        if (cfg.dateBelowClock) moveDateBelowClock();
        if (cfg.dateBelowClock && cfg.iconOnlyButtons) alignDateBelowIconsWithClock();
        fork = ForkConfig.load(this);
        setupInfoWidgets();
        libraryExcludedApps = new HashSet<>(cfg.libraryExcludedApps);

        grid = findViewById(R.id.grid);
        // vPro, home_dock_only: half-column spans, so the dock can be centred by half a tile (dockSpareHalves()).
        final boolean centreDock = fork != null && fork.homeDockOnly && dockRow;
        final int unit = centreDock ? 2 : 1;
        grid.setLayoutManager(new GridLayoutManager(this, COLUMNS * unit) {
            /**
             * Every "bring this tile on screen" scroll lands here, both RecyclerView's own on focus and
             * onTileFocused's. The stock version treats the grid's top padding as the visible top, but that
             * padding only pushes the dock down the screen: grid rows scroll up into it. With the stock rule,
             * Up into a row above the padding dragged that row down to the padding line, cut it off at the
             * bottom of the screen and could scroll the focused tile out entirely, dropping focus into the
             * top bar. Grid tiles now scroll just enough to show the rect between the grid's top edge and its
             * bottom; dock tiles are left to onTileFocused, which parks the dock row on the padding line.
             */
            @Override
            public boolean requestChildRectangleOnScreen(@NonNull RecyclerView parent, @NonNull View child,
                                                         @NonNull Rect rect, boolean immediate,
                                                         boolean focusedChildVisible) {
                if (dockRow && getPosition(child) < COLUMNS) return false;
                int top = child.getTop() + rect.top;
                int bottom = top + rect.height();
                // A row already fully on screen stays put, even inside the bottom padding (the last row
                // rows_below_dock leaves visible ends there; nudging it up was the bug). A row that has to scroll
                // in still stops at the padding line, clear of the screen edge, as before.
                int limitBottom = getHeight() - getPaddingBottom();
                int dy;
                if (top < 0) dy = top;
                else if (bottom <= getHeight()) dy = 0;
                else dy = Math.min(top, bottom - limitBottom);
                if (dy == 0) return false;
                if (immediate || !animations) parent.scrollBy(0, dy);
                else parent.smoothScrollBy(0, dy);
                return true;
            }
        });
        ((GridLayoutManager) grid.getLayoutManager()).setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override public int getSpanSize(int position) {
                if (adapter != null && adapter.isLibraryAt(position)) return COLUMNS * unit;
                // The first dock tile also takes the free half of the row on its left; the decoration below
                // turns that into an inset, so every tile keeps one column's width and the row is centred.
                if (centreDock && position == 0) return unit + dockSpareHalves();
                return unit;
            }
        });
        if (centreDock) grid.addItemDecoration(new RecyclerView.ItemDecoration() {
            @Override
            public void getItemOffsets(@NonNull Rect out, @NonNull View view, @NonNull RecyclerView parent,
                                       @NonNull RecyclerView.State state) {
                if (parent.getChildAdapterPosition(view) != 0) return;
                int inner = parent.getWidth() - parent.getPaddingLeft() - parent.getPaddingRight();
                out.left = inner * dockSpareHalves() / (2 * COLUMNS);
            }
        });
        grid.setHasFixedSize(true);
        grid.setItemAnimator(null);
        // Tiles that scroll out while one is carried come back from the view cache without a rebind; set their
        // dimming again whenever one attaches, so none stays dim after the drop.
        grid.addOnChildAttachStateChangeListener(new RecyclerView.OnChildAttachStateChangeListener() {
            @Override
            public void onChildViewAttachedToWindow(@NonNull View v) {
                v.setAlpha(tileAlpha(grid.getChildAdapterPosition(v)));
            }

            @Override
            public void onChildViewDetachedFromWindow(@NonNull View v) {
            }
        });
        if (dockRow) {
            DockDecoration dockDecoration = new DockDecoration(COLUMNS, density, cfg.alwaysShowNames, cfg.dockOpacity,
                    cfg.dockGlass, cfg.dockEvenPadding, cfg.dockBand);
            if (centreDock) dockDecoration.forkSpareHalves = this::dockSpareHalves;
            grid.addItemDecoration(dockDecoration);
        }
        if (animations) {
            grid.setLayoutAnimation(new LayoutAnimationController(
                    AnimationUtils.loadAnimation(this, R.anim.tile_enter), 0.06f));
        }
        adapter = new AppAdapter(this, this, COLUMNS, cfg);
        dockApps = cfg.dockApps;
        fillDockLeavesSlots = !cfg.fillDock;
        Order savedOrder = Order.load(Order.file(this));
        adapter.setSavedOrder(Order.ranks(savedOrder, dockApps), Order.docked(savedOrder, dockApps));
        grid.setAdapter(adapter);

        // tvOS layout: push the dock row towards the bottom of the screen, leaving room for
        // rows_below_dock grid rows under it; the rest of the grid stays below until you press Down.
        int rowsBelowDock = cfg.rowsBelowDock;
        grid.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int h = b - t;
            // Item height follows the tile size, which follows the columns and the rows that must fit (AppAdapter.tileWidthPx).
            int inner = (r - l) - grid.getPaddingLeft() - grid.getPaddingRight();
            int tileW = AppAdapter.tileWidthPx(inner, h, COLUMNS, rowsBelowDock, cfg.dockTitle, cfg.dockRow, density);
            int itemH = AppAdapter.itemHeightPx(AppAdapter.tileHeightPx(tileW), density);
            int dockRow = itemH + (int) (AppAdapter.DOCK_EXTRA_DP * density);   // item height plus DockDecoration offsets
            int gridRows = itemH * rowsBelowDock;
            // Keep the hero below the header when the library is enabled on a short screen,
            // even when config.xml requests an extra visible row below the dock.
            int shelfClearance = cfg.appLibrary ? hero.getMeasuredHeight() + (int) (40 * density) : 0;
            int want = Math.max(shelfClearance, h - dockRow - gridRows - (int) (AppAdapter.BOTTOM_ROOM_DP * density));
            // Where the rows cannot fit even at the smallest tile (3 rows with the title on), the title keeps its
            // room above the dock and the last rows run off the bottom instead of the title drawing over the clock.
            if (MainActivity.this.dockRow && cfg.dockTitle && rowsBelowDock > 0) want = Math.max(want, (int) (AppAdapter.TITLE_ROOM_DP * density));
            // No dock to push down: the grid starts at the top, with room for the first row's zoom and glass.
            if (!MainActivity.this.dockRow) want = (int) (AppAdapter.TOP_ROOM_DP * density);
            // The vertical grid parks the dock on the new padding line by scrolling (onTileFocused).
            if (grid.getPaddingTop() != want) {
                grid.setPadding(grid.getPaddingLeft(), want, grid.getPaddingRight(), grid.getPaddingBottom());
            }
            // Cold start or column count > 4: the first focus can run before the grid has its final width or
            // padding, leaving the glass at the XML design size until a key is pressed.
            grid.post(this::refitGlass);
        });

        // Keep the glass glued to its tile while the grid scrolls.
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (!glassShown) return;
                glassTargetY -= dy;
                glassScrollShift -= dy;
                glass.setTranslationY(glass.getTranslationY() - dy);
            }
        });

        // Liquid feel: soft springs on the highlight's position.
        springX = new SpringAnimation(glass, DynamicAnimation.TRANSLATION_X);
        springY = new SpringAnimation(glass, new FloatPropertyCompat<View>("glassContentY") {
            @Override
            public float getValue(View v) {
                return v.getTranslationY() - glassScrollShift;
            }

            @Override
            public void setValue(View v, float value) {
                v.setTranslationY(value + glassScrollShift);
            }
        });
        for (SpringAnimation s : new SpringAnimation[]{springX, springY}) {
            // Critically damped: glides straight onto the tile without swinging past it.
            s.setSpring(new SpringForce().setStiffness(advancedEffects ? 520f : 440f).setDampingRatio(1f));
        }

        btnInputs = findViewById(R.id.btnInputs);
        // vPro: remember where the Inputs pill sits, so the remote's Input button can show its list in the
        // very same place over another app (InputsOverlayActivity).
        btnInputs.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, orr, ob) -> {
            int[] at = new int[2];
            v.getLocationOnScreen(at);
            if (v.getWidth() <= 0) return;
            InfoWidgets.prefs(this).edit().putInt(ForkConfig.INPUTS_LEFT, at[0])
                    .putInt(ForkConfig.INPUTS_BOTTOM, at[1] + v.getHeight())
                    .putInt(ForkConfig.INPUTS_WIDTH, v.getWidth()).apply();
        });
        View btnSettings = findViewById(R.id.btnSettings);
        btnInputs.setOnClickListener(v -> showInputs());
        btnWifi.setOnClickListener(v -> open(systemIntent(Settings.ACTION_WIFI_SETTINGS)));
        btnWifi.setOnLongClickListener(v -> {
            open(systemIntent(Settings.ACTION_WIFI_SETTINGS));
            return true;
        });
        launcherSettingsButton = cfg.launcherSettingsButton;
        if (launcherSettingsButton) {
            ((TextView) btnSettings).setText(R.string.launcher_settings);
            ((TextView) btnSettings).setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_home_settings_pill, 0, 0, 0);
        }
        btnSettings.setOnClickListener(v -> {
            // vPro: the on-screen gear opens Launcher settings, like the remote's gear button.
            if (fork != null && fork.homeDockOnly) {
                startActivityForResult(new Intent(this, LauncherSettingsActivity.class), LAUNCHER_SETTINGS_REQUEST);
                return;
            }
            if (launcherSettingsButton) startActivityForResult(
                    new Intent(this, LauncherSettingsActivity.class), LAUNCHER_SETTINGS_REQUEST);
            else open(systemIntent(Settings.ACTION_SETTINGS));
        });
        if (!cfg.showNetworkPill) btnWifi.setVisibility(View.GONE);
        showInputsPill = cfg.showInputsPill;
        btnLibraryTop = findViewById(R.id.btnLibraryTop);
        btnLibraryTop.setOnClickListener(v -> {
            onLibraryRequested();
            libraryFromTopBar = true;   // closing it goes back to this button, not to the pill after the last row
        });
        btnLibraryTop.setVisibility(cfg.libraryButton && cfg.appLibrary ? View.VISIBLE : View.GONE);
        // vPro, library_button_left: the library button goes first, left of Inputs.
        if (fork != null && fork.libraryButtonLeft && btnLibraryTop.getParent() instanceof ViewGroup) {
            ViewGroup bar = (ViewGroup) btnLibraryTop.getParent();
            if (bar.indexOfChild(btnLibraryTop) != 0) {
                bar.removeView(btnLibraryTop);
                bar.addView(btnLibraryTop, 0);
            }
        }
        btnVoice = findViewById(R.id.btnVoice);
        btnVoice.setOnClickListener(v -> openVoiceSearch());
        // Left of the App library button ("All apps"), wherever library_button_left put that.
        ViewGroup topBar = (ViewGroup) btnVoice.getParent();
        topBar.removeView(btnVoice);
        topBar.addView(btnVoice, topBar.indexOfChild(btnLibraryTop));
        addTopBarApps(cfg.topBarApps, cfg.launchActions);
        iconOnlyButtons = cfg.iconOnlyButtons;
        wifiIconOnly = iconOnlyButtons && cfg.wifiLabelWidth <= 0;
        if (cfg.wifiStatusColor) {
            wifiPlainTint = btnWifi.getCompoundDrawableTintList();
            wifiOnlineTint = getColorStateList(R.color.pill_icon_online);
            if (cfg.wifiStatusWarnings) {
                wifiNoInternetTint = getColorStateList(R.color.pill_icon_no_internet);
                wifiOfflineTint = getColorStateList(R.color.pill_icon_offline);
            }
        }
        if (iconOnlyButtons) {
            if (cfg.wifiLabelWidth > 0) btnWifi.setMaxWidth(Math.round(cfg.wifiLabelWidth * density));
            else makeIconOnly(btnWifi);
            makeIconOnly((TextView) btnSettings);
        }
        if (cfg.iconOnlyInputs) makeIconOnly((TextView) btnInputs);
        btnSettings.setOnLongClickListener(v -> {
            showSettingsMenu();
            return true;
        });

        // Keep focus on the top bar at its ends and at the top: Android's focus finder would otherwise
        // jump to a tile far below when there is nothing in that direction.
        View.OnKeyListener pillKeys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                if (miniPlayer != null) miniPlayer.focus();   // the mini player above, when something plays
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                // Back to the dock tile Up came from, not wherever the focus finder lands.
                focusTile(lastFocused % COLUMNS);
                return true;
            }
            int firstButton = btnInputs.getVisibility() == View.VISIBLE ? R.id.btnInputs : firstTopButton().getId();
            // vPro, library_button_left: the voice button, left of the library button, is the left end now, so Left
            // from Inputs must reach them instead of being swallowed as the end of the bar.
            if (fork != null && fork.libraryButtonLeft && btnLibraryTop.getVisibility() == View.VISIBLE) {
                firstButton = R.id.btnVoice;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return v.getId() == firstButton;
            return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v.getId() == R.id.btnSettings;
        };
        miniPlayer = new MiniPlayer(this, root, findViewById(R.id.topButtons));
        btnInputs.setOnKeyListener(pillKeys);
        btnWifi.setOnKeyListener(pillKeys);
        btnSettings.setOnKeyListener(pillKeys);
        btnLibraryTop.setOnKeyListener(pillKeys);
        btnVoice.setOnKeyListener(pillKeys);
        for (View button : topAppButtons) button.setOnKeyListener(pillKeys);
        if (advancedEffects) {
            List<View> pills = new ArrayList<>(Arrays.asList(btnInputs, btnWifi, btnLibraryTop, btnVoice, btnSettings));
            pills.addAll(topAppButtons);
            for (View button : pills) {
                button.setOnFocusChangeListener((view, focused) -> view.animate()
                        .scaleX(focused ? 1.04f : 1f).scaleY(focused ? 1.04f : 1f)
                        .setDuration(160).start());
            }
        }

        // Hide the glass when focus leaves the tiles.
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener((oldF, newF) -> {
            boolean onTile = newF != null && newF.getId() == R.id.card;
            if (!onTile) hideGlass();
        });

        packageFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packageFilter.addDataScheme("package");
        timeFilter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        // Registered for the activity's whole life, not only while it is started: an app installed or updated while
        // another app is in front must still mark the tiles stale, or Home would have to rebuild on every return.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(packageReceiver, packageFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(packageReceiver, packageFilter);
        }
        if (openOnStartOnWake) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(wakeReceiver, new IntentFilter(Intent.ACTION_SCREEN_ON), Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(wakeReceiver, new IntentFilter(Intent.ACTION_SCREEN_ON));
            }
        }

        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null) tim.registerCallback(inputCallback, handler);
        if (cm != null) cm.registerDefaultNetworkCallback(networkCallback);
        // Not after recreate(): the launch intent survives it and would open the settings again.
        if (getIntent() != null && getIntent().getBooleanExtra(EXTRA_LISTEN, false)) {
            setIntent(new Intent(Intent.ACTION_MAIN));
            listenOnResume = true;
        }
        if (savedInstanceState == null && !openLauncherSettingsIfAsked(getIntent()) && !openLibraryIfAsked(getIntent())) {
            openInputsIfAsked(getIntent());
        }
    }

    /**
     * ACTION_VIEW_INPUTS, sent by the "inputs" action word: open the Inputs menu once the pill is on screen. The intent
     * is replaced so a later recreate() does not open it again. Nothing to do when the TV has no inputs to list (the
     * pill is hidden then).
     */
    private boolean openInputsIfAsked(Intent intent) {
        if (intent == null || !ACTION_VIEW_INPUTS.equals(intent.getAction())) return false;
        setIntent(new Intent(Intent.ACTION_MAIN));
        View anchor = btnInputs;
        if (anchor.getVisibility() != View.VISIBLE) return true;
        if (anchor.isLaidOut()) {
            // Android 9 delivers a new intent to a paused activity with a brief resume and pause around it, and
            // onPause() closes popups, so open the menu once that has run (HiSmartTV A4, Android TV 9).
            anchor.post(() -> {
                if (isFinishing() || anchor.getVisibility() != View.VISIBLE) return;
                anchor.requestFocus();
                showInputs();
            });
            return true;
        }
        // Cold start: the popup anchors to the pill, so wait for the first layout.
        anchor.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                anchor.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                anchor.post(() -> {
                    if (isFinishing() || anchor.getVisibility() != View.VISIBLE) return;
                    anchor.requestFocus();
                    showInputs();
                });
            }
        });
        return true;
    }

    /**
     * ACTION_APP_LIBRARY, sent by the "library" action word: open the App library once Home is on screen. The intent
     * is replaced so a later recreate() does not open it again. With app_library off there is no library, so the
     * press only brings Home forward.
     */
    private boolean openLibraryIfAsked(Intent intent) {
        if (intent == null || !ACTION_APP_LIBRARY.equals(intent.getAction())) return false;
        setIntent(new Intent(Intent.ACTION_MAIN));
        if (!appLibraryEnabled) return true;
        getWindow().getDecorView().post(() -> {
            if (!isFinishing() && !isDestroyed()) onLibraryRequested();
        });
        return true;
    }

    private boolean openLauncherSettingsIfAsked(Intent intent) {
        if (intent == null || !ACTION_LAUNCHER_SETTINGS.equals(intent.getAction())) return false;
        setIntent(new Intent(Intent.ACTION_MAIN));
        startActivityForResult(new Intent(this, LauncherSettingsActivity.class), LAUNCHER_SETTINGS_REQUEST);
        return true;
    }

    /**
     * Home pressed while Home is already in front: singleTask delivers it here, then onResume runs.
     * Back to the top with the first dock tile focused, like the stock launcher. Back from an app
     * never comes this way, so returning to the app you left from is unchanged.
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (openLauncherSettingsIfAsked(intent)) return;
        if (openLibraryIfAsked(intent)) return;
        if (openInputsIfAsked(intent)) return;
        if (intent != null && intent.getBooleanExtra(EXTRA_LISTEN, false)) {
            setIntent(new Intent(Intent.ACTION_MAIN));
            listenOnResume = true;   // keep the tile focus as it was: only the voice button lights up
            return;
        }
        if (intent == null || !intent.hasCategory(Intent.CATEGORY_HOME)) return;
        // Home twice in a row, quickly: Running apps. The first press has already done its usual work (Home in front,
        // first tile), so a single Home is not delayed.
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastHomeIntent < DOUBLE_HOME_MS && ForkKeys.doubleHome(this)) {
            lastHomeIntent = 0;
            try {
                startActivity(new Intent(this, RunningAppsActivity.class));
            } catch (ActivityNotFoundException ignored) {
            }
            return;
        }
        lastHomeIntent = now;
        grid.scrollToPosition(0);
        lastFocused = 0;
        lastFocusedKey = adapter.getItemCount() > 0 ? adapter.keyAt(0) : null;
        if (stopped) {
            tileRefresh.onHomeAgain();
        } else {
            // Still on screen (the quick panel was over it, or Home was already in front): the tiles are current,
            // so only go back to the first one; rebuilding would replay the entrance animation for nothing.
            grid.post(() -> focusTile(0));
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        stopped = false;
        started = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(timeReceiver, timeFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(timeReceiver, timeFilter);
        }
        updateClockVisibility();
        clockRefresh.start();   // with timeReceiver: while Home is visible, paused or not
        tileRefresh.onStart();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        inFront = this;
        if (root != null) {
            root.removeCallbacks(infoTick);
            root.post(infoTick);
        }
        if (miniPlayer != null) miniPlayer.start();
        if (listenOnResume && root != null) {
            listenOnResume = false;
            // After the window has focus: the recognizer and the beep need Home really in front.
            root.postDelayed(() -> {
                if (resumed && !isFinishing()) openVoiceSearch();
            }, 300);
        }
        // Something changed in Launcher settings: rebuild with it, however the settings were left. Only Back
        // delivered their RESULT_OK; Home (the remote's button) clears them off this task and the result comes
        // back cancelled, so a new wallpaper or layout waited for the next process start.
        if (Config.settingsChanged(settingsRevision, settingsRevision())) {
            recreate();
            return;
        }
        if (grid.getAdapter() == null) grid.setAdapter(adapter);   // dropped by onTrimMemory
        switch (tileRefresh.onResume(packagesChangedSinceRebuild())) {
            case REBUILD:
                tilesReady = false;
                reloadApps(true);
                break;
            case REFOCUS:
                // The same tiles: only the focus goes back, as after a rebuild, without replaying the entrance.
                tilesReady = false;
                reloadApps(true, false, false);
                break;
            default:
                break;
        }
        if (openLiveTvAfterBoot()) return;
        updateGreeting();
        // The network and Inputs pills keep their state while Home is in the background, and callbacks update them
        // when the network or the inputs change, so this refresh only catches anything missed. Its binder calls
        // (about 7 ms on a TCL C755, most of it WifiManager.getConnectionInfo) would hold up the first frame back
        // on Home, so after the first resume it runs once that frame is drawn.
        if (pillsShown) {
            refreshPillsAfterNextDraw();
        } else {
            updateWifiLabel();
            updateInputsButton();
            pillsShown = true;
        }
        wallpaperPlayer.start();
        handler.postDelayed(minuteTick, MINUTE_MS);
    }

    /** Launcher settings' change count (Config.SETTINGS_REVISION_KEY); onResume rebuilds Home when it moved. */
    private long settingsRevision() {
        return getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE).getLong(Config.SETTINGS_REVISION_KEY, 0);
    }

    /**
     * Only "Reorder tiles" in Launcher settings still answers with a result: Home rebuilds (the saved choices are
     * reloaded) and then enters reorder mode. Every other change is picked up in onResume through the change count.
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == LAUNCHER_SETTINGS_REQUEST && resultCode == RESULT_OK
                && data != null && data.getBooleanExtra(LauncherSettingsActivity.EXTRA_REORDER, false)) {
            getIntent().putExtra(LauncherSettingsActivity.EXTRA_REORDER, true);
            // The old activity must not consume the pending action, or rebuild a second time in onResume.
            settingsRecreationPending = true;
            settingsRevision = settingsRevision();
            recreate();
        }
    }

    private void startPendingSettingsReorder() {
        if (settingsRecreationPending || !tilesReady || !hasWindowFocus() || isFinishing()
                || !getIntent().getBooleanExtra(LauncherSettingsActivity.EXTRA_REORDER, false)) return;
        getIntent().removeExtra(LauncherSettingsActivity.EXTRA_REORDER);
        toggleReorder();
    }

    /** Rebuilds the tiles, then puts focus back where it was. */
    private void reloadApps(boolean animate) {
        reloadApps(animate, true);
    }

    /** @param rebuild false keeps the tiles as they are and only puts focus back (TileRefresh.Action.REFOCUS). */
    private void reloadApps(boolean animate, boolean rebuild) {
        reloadApps(animate, rebuild, true);
    }

    /**
     * @param entrance false puts focus back as an animated reload would, without replaying the tiles' entrance:
     *                 on a return to the same tiles it would only mark a change that didn't happen.
     */
    private void reloadApps(boolean animate, boolean rebuild, boolean entrance) {
        cancelPendingFocusScroll();
        RecyclerView.ViewHolder footer = grid.findViewHolderForAdapterPosition(adapter.appCount());
        final boolean restoreLibrary = adapter.isLibraryAt(adapter.appCount()) && footer != null
                && footer.itemView.findViewById(R.id.btnLibrary) != null
                && footer.itemView.findViewById(R.id.btnLibrary).hasFocus();
        // Capture the app before rebinding can move focus and overwrite its key.
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        final boolean restoreFocus = appLibrary == null && (animate || grid.hasFocus());
        if (restoreFocus) {
            glassShown = false;
            glass.setAlpha(0f);
            heroShown = false;
            hero.setAlpha(0f);
        }
        if (rebuild) reloadTiles();
        if (animate && entrance && animations) {
            grid.scheduleLayoutAnimation();
            // A rebuild redraws the grid anyway; without one the entrance needs a draw to start.
            if (!rebuild) grid.invalidate();
        }

        if (restoreFocus) {
            // Keep a focused footer at the end even when the app count changes.
            if (restoreLibrary) grid.post(this::focusLibrary);
            else restoreFocus();
        } else {
            // A package change must not steal focus from a top button or the Inputs popup.
            int pos = adapter.positionOf(returnKey);
            lastFocused = Math.max(0, Math.min(pos >= 0 ? pos : returnSlot, adapter.appCount() - 1));
            lastFocusedKey = adapter.keyAt(lastFocused);
            if (pos < 0) hideHero();
            tilesReady = true;
            startPendingSettingsReorder();
        }
    }

    /** True when PackageManager reports apps changed since the last rebuild (one binder call). */
    private boolean packagesChangedSinceRebuild() {
        ChangedPackages changed = getPackageManager().getChangedPackages(packageSequence);
        return changed != null && !changed.getPackageNames().isEmpty();
    }

    private int currentPackageSequence(int since) {
        ChangedPackages changed = getPackageManager().getChangedPackages(since);
        return changed != null ? changed.getSequenceNumber() : since;
    }

    /** Rebuilds the tiles from the TV's leanback apps. */
    private void reloadTiles() {
        if (reordering) endReorder();   // an app installed or removed while reordering: keep the order so far
        // Read these before reload(): rebinding can move focus to the first tile and overwrite them.
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        final List<ResolveInfo> leanback = getPackageManager().queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0);
        adapter.reload(leanback);
        // vPro, home_dock_only: the dock holds as many apps as there are columns. When the owner lowers the
        // count, the apps past the end (the rightmost) leave the dock for the App library instead of a hidden row.
        if (fork != null && fork.homeDockOnly && dockRow && adapter.dockedCount() > COLUMNS) {
            boolean trimmed = false;
            for (int i = adapter.dockedCount() - 1; i >= COLUMNS; i--) {
                String pkg = adapter.packageAt(i);
                if (pkg != null && !pkg.equals(getPackageName()) && LibraryApps.save(this, pkg, false)) trimmed = true;
            }
            if (trimmed) adapter.reload(leanback);
        }
        lastFocusedKey = returnKey;
        lastFocused = returnSlot;
        packageSequence = currentPackageSequence(packageSequence);
    }

    /**
     * Return to the app we left from, or the same slot if that app is gone. Always refocus: after
     * reload() the view that holds focus may no longer show the app it had.
     */
    private void restoreFocus() {
        final boolean returnToLibrary = libraryFocusPending;
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        grid.post(() -> {
            int pos = adapter.positionOf(returnKey);
            if (returnToLibrary || libraryFocusPending) focusLibrary();
            else focusTile(pos >= 0 ? pos : returnSlot);
            tilesReady = true;
            startPendingSettingsReorder();
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        if (inFront == this) inFront = null;
        if (root != null) root.removeCallbacks(infoTick);
        if (miniPlayer != null) miniPlayer.stop();
        if (newsTicker != null) newsTicker.setRunning(false);
        // No UP reaches a paused Home, so a vendor-key hold it saw go down would still open its app later.
        // Only Home's own share is dropped: a key service that saw the same DOWN keeps its hold.
        RemoteKeys.release(this);
        cancelHoldOk();
        endReorder();   // Home or another app while reordering keeps the order so far
        if (voice != null) voice.cancel();
        wallpaperPlayer.stop();
        hero.animate().cancel();
        handler.removeCallbacks(minuteTick);
        handler.removeCallbacks(openWhenOnline);
        startPending = null;   // leaving Home before the network came back: the boot's app stays closed
        cancelPendingFocusScroll();
        // Nothing of ours stays on screen over another app.
        if (appLibrary != null) appLibrary.close();
        if (openPopup != null) openPopup.dismiss();
    }

    @Override
    protected void onStop() {
        super.onStop();
        started = false;
        unregisterReceiver(timeReceiver);
        clockRefresh.stop();
        handler.removeCallbacks(clockCheck);
        stopped = true;
        tileRefresh.onStop();
    }

    /**
     * Another app needs the memory while Home is in the background: let go of every tile's artwork, and
     * rebuild it when Home comes back. Not at UI_HIDDEN, the ordinary trip to an app, where keeping the
     * tiles is what makes the return instant.
     */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        // RUNNING_LOW and CRITICAL also arrive while Home is visible when an accessibility service keeps the
        // process in the foreground; never blank the screen the owner is looking at.
        if (!MemoryTrimPolicy.shouldRelease(level, started)) return;
        adapter.clear();
        // Detach the adapter too: with Home stopped no layout pass runs, so the tile views (and the bitmaps
        // they hold) would stay attached until Home comes back. setAdapter(null) recycles them now.
        grid.setAdapter(null);
        grid.getRecycledViewPool().clear();
        wallpaperPlayer.releaseForMemoryPressure();
        tileRefresh.invalidate();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            startPendingSettingsReorder();
            if (libraryFocusPending && appLibrary == null) focusLibrary();
        } else {
            cancelHoldOk();   // a dialog or another window took the remote while OK was held
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        RemoteKeys.release(this);
        cancelHoldOk();
        unregisterReceiver(packageReceiver);
        if (voice != null) voice.destroy();
        if (openOnStartOnWake) unregisterReceiver(wakeReceiver);
        wallpaperPlayer.close();
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null) tim.unregisterCallback(inputCallback);
        if (cm != null) cm.unregisterNetworkCallback(networkCallback);
    }

    // ---- reorder ---------------------------------------------------------------------------------

    /** Blue the glass turns while reorder mode is on. */
    private static final int REORDER_ACCENT = 0xFF6EC1FF;

    /**
     * Reorder mode. While it is on, every key comes here first:
     * - nothing carried: arrows move focus as usual; OK on a tile picks it up instead of opening the app;
     *   Back ends the mode and saves the order.
     * - a tile carried: arrows move it; Back (or OK) puts it down and the mode stays on for the next one.
     * Other keys (volume, Home) pass through; leaving the launcher ends the mode and saves.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // Vendor keys (TCL Netflix button, 4062) never reach the key services, only the focused window, so their
        // "hold:" entries run here too or nowhere. Only while resumed: onPause releases Home's holds, and a DOWN
        // taken after that would arm one nothing releases.
        if (resumed && event.getKeyCode() >= 1000 && RemoteKeys.handle(this, event, homeAppKeys.get(event.getKeyCode()),
                homeHoldAppKeys.get(event.getKeyCode()))) return true;
        if (holdOk(event)) return true;
        if (tileMenuEnabled && !reordering && openPopup == null
                && event.getKeyCode() == KeyEvent.KEYCODE_MENU) {
            int position = focusedTilePosition();
            if (position >= 0 && position < adapter.appCount()
                    && !getPackageName().equals(adapter.packageAt(position))) {
                if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                    onTileLongPress(position, getCurrentFocus());
                }
                return true;
            }
        }
        if (!reordering) return super.dispatchKeyEvent(event);
        if (dockEditing) return dispatchDockEdit(event);
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        int code = event.getKeyCode();
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        boolean back = code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE;
        if (movingKey != null) {
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (down) moveBy(code);
                    return true;
                default:
                    if (ok || back) {
                        if (!down) drop();
                        return true;
                    }
                    return super.dispatchKeyEvent(event);
            }
        }
        if (back) {
            if (!down) endReorder();
            return true;
        }
        int pos = focusedTilePosition();
        View focus = getCurrentFocus();
        if (ok && focus != null && focus.getId() == R.id.btnLibrary) {
            // The App library pill sits in the grid but is no tile: OK ends the mode (saving the order) and opens it.
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                endReorder();
                onLibraryRequested();
            }
            return true;
        }
        if (ok && grid.hasFocus()) {
            // RecyclerView can briefly report NO_POSITION during a move or rebind. Still consume
            // OK on the grid: falling through would let the focused card open its app in reorder mode.
            if (!down && pos >= 0) pickUp(pos);
            return true;
        }
        return super.dispatchKeyEvent(event);   // arrows navigate; OK on a top-bar pill works as usual
    }

    /** Adapter position of the focused tile, or -1 when focus is not on a tile. */
    private int focusedTilePosition() {
        View f = getCurrentFocus();
        if (f == null || f.getId() != R.id.card || !(f.getParent() instanceof View)) return -1;
        return grid.getChildAdapterPosition((View) f.getParent());
    }

    /** Reorder from Launcher settings or the Settings menu: turns the mode on (focus goes back to the last tile), or off again. */
    private void toggleReorder() {
        if (reordering) {
            endReorder();
            return;
        }
        if (!beginReorder()) return;
        focusTile(Math.max(0, Math.min(lastFocused, adapter.movableCount() - 1)));
        Toast.makeText(this, R.string.reorder_hint, Toast.LENGTH_LONG).show();
    }

    /** The start shared by reorder and edit-dock mode. False when there is nothing to move. */
    private boolean beginReorder() {
        if (adapter.movableCount() < 2) return false;
        // Before the reload below: once it has dropped the empty slots, the dock row also shows grid apps.
        dockInSession = new HashSet<>();
        dockedAtStart = adapter.dockFilled();
        for (int i = 0; i < Math.min(dockedAtStart, adapter.movableCount()); i++) dockInSession.add(adapter.keyAt(i));
        adapter.setReordering(true);
        if (adapter.hasEmptySlots()) reloadTiles();   // every position must hold a tile that can move
        reordering = true;
        orderAtStart = adapter.movableKeys();
        glass.setAccent(REORDER_ACCENT);
        return true;
    }

    /** Edit dock (Settings menu): focus goes to the first dock place, which OK then chooses. */
    private void startDockEdit() {
        if (reordering) endReorder();
        if (!beginReorder()) return;
        dockEditing = true;
        dockEditSlot = -1;
        focusTile(0);
        Toast.makeText(this, R.string.dock_edit_hint, Toast.LENGTH_LONG).show();
    }

    /**
     * Keys in edit-dock mode. First OK on a dock tile chooses that place (the other dock tiles dim) and focus moves
     * to the grid; then OK on any tile swaps that app into the place, saves the order and ends the mode. Back goes
     * one step back: from choosing the app to choosing the place, and from there out of the mode.
     */
    private boolean dispatchDockEdit(KeyEvent event) {
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        int code = event.getKeyCode();
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        boolean back = code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE;
        if (back) {
            if (!down) {
                if (dockEditAppKey != null) {
                    endReorder();   // "Add to dock" cancelled before a place was chosen
                } else if (dockEditSlot >= 0) {
                    int slot = dockEditSlot;
                    dockEditSlot = -1;
                    dimOthers();
                    focusTile(slot);
                } else {
                    endReorder();
                }
            }
            return true;
        }
        int pos = focusedTilePosition();
        if (ok && pos >= 0) {
            if (!down) {
                int n = adapter.movableCount();
                if (dockEditAppKey != null) {
                    if (pos < Math.min(COLUMNS, n)) {
                        int from = adapter.positionOf(dockEditAppKey);
                        if (from >= 0) swapIntoDock(pos, from);
                        else endReorder();
                    }
                } else if (dockEditSlot < 0) {
                    if (pos < Math.min(COLUMNS, n)) {
                        dockEditSlot = pos;
                        dimOthers();
                        focusTile(Math.min(COLUMNS, n - 1));
                        Toast.makeText(this, R.string.dock_edit_pick_app, Toast.LENGTH_LONG).show();
                    }
                } else if (pos < n) {
                    swapIntoDock(dockEditSlot, pos);
                }
            }
            return true;   // never opens the app while editing
        }
        return super.dispatchKeyEvent(event);   // arrows navigate as usual
    }

    /**
     * Edit-dock mode started from the tile menu. A dock app: its place is already chosen, so focus goes to the grid
     * for the app to put there. A grid app: it is already chosen, so focus goes to the dock for the place.
     */
    private void startDockEditFor(String key, boolean inDock) {
        if (reordering) endReorder();
        if (!beginReorder()) return;
        dockEditing = true;
        int pos = adapter.positionOf(key);   // beginReorder can rebuild the tiles
        int n = adapter.movableCount();
        if (pos < 0) {
            endReorder();
        } else if (inDock) {
            dockEditSlot = pos;
            dimOthers();
            focusTile(Math.min(COLUMNS, n - 1));
            Toast.makeText(this, R.string.dock_edit_pick_app, Toast.LENGTH_LONG).show();
        } else {
            dockEditAppKey = key;
            focusTile(0);
            Toast.makeText(this, R.string.dock_edit_pick_place, Toast.LENGTH_LONG).show();
        }
    }

    /** Puts the app at pos into dock place slot, and the app that was there where the chosen one was; then saves. */
    private void swapIntoDock(int slot, int pos) {
        String slotKey = adapter.keyAt(slot);
        if (pos != slot) {
            keepScroll(() -> {
                adapter.move(pos, slot);
                adapter.move(adapter.positionOf(slotKey), pos);
            });
            if (pos >= COLUMNS) dockInSession.remove(slotKey);
        }
        // Outside the check: with no empty slots shown, the chosen app may already sit in the free place.
        dockInSession.add(adapter.keyAt(slot));
        endReorder();
        refocusAfterLayout(slot);
    }

    private void pickUp(int pos) {
        if (pos >= adapter.movableCount()) return;   // the "Default home" tile stays put
        movingKey = adapter.keyAt(pos);
        dimOthers();
    }

    /** Puts the carried tile down where it is; the mode stays on. */
    private void drop() {
        movingKey = null;
        dimOthers();
    }

    /** While a tile is carried, every other tile is dimmed so the carried one stands out; otherwise all are full. */
    private void dimOthers() {
        for (int i = 0; i < grid.getChildCount(); i++) {
            View item = grid.getChildAt(i);
            item.setAlpha(tileAlpha(grid.getChildAdapterPosition(item)));
        }
    }

    /** A tile's alpha: dim unless it is the carried one, or, in edit-dock mode, a dock tile other than the chosen place. */
    private float tileAlpha(int pos) {
        if (movingKey != null) {
            return pos != RecyclerView.NO_POSITION && movingKey.equals(adapter.keyAt(pos)) ? 1f : 0.45f;
        }
        if (dockEditSlot >= 0 && pos >= 0 && pos < COLUMNS && pos != dockEditSlot) return 0.45f;
        return 1f;
    }

    /** Ends the mode and saves the order if it changed since the mode started. */
    private void endReorder() {
        if (!reordering) return;
        dockEditing = false;
        dockEditSlot = -1;
        dockEditAppKey = null;
        drop();
        reordering = false;
        adapter.setReordering(false);
        glass.setAccent(0);
        List<String> keys = adapter.movableKeys();
        List<String> before = orderAtStart;
        orderAtStart = null;
        // fill_dock false: the dock is what the owner put there, saved with its size, so the empty slots that
        // come back after the mode go after it. With fill_dock true every dock slot holds an app anyway.
        int docked = -1;
        if (fillDockLeavesSlots) {
            docked = DockSlots.dockCount(keys, COLUMNS, dockInSession);
            keys = DockSlots.dockFirst(keys, COLUMNS, dockInSession);
        }
        dockInSession = null;
        if (!keys.equals(before) || (fillDockLeavesSlots && docked != dockedAtStart)) {
            Order order = new Order(Order.dockKey(dockApps), keys, docked);
            if (Order.save(Order.file(this), order)) {
                adapter.setSavedOrder(Order.ranks(order, dockApps), Order.docked(order, dockApps));
            } else {
                Toast.makeText(this, R.string.reorder_not_saved, Toast.LENGTH_SHORT).show();
            }
        }
        if (fillDockLeavesSlots) reloadTiles();   // the empty dock slots left out while reordering come back
    }

    /**
     * Moves the carried tile one step. Left and Right wrap across rows, like a list; Up and Down move a whole row,
     * and Down into a short last row lands on its last tile. Nothing moves past either end or up into the top bar.
     */
    private void moveBy(int keyCode) {
        int from = adapter.positionOf(movingKey);
        int n = adapter.movableCount();
        if (from < 0 || from >= n) {
            drop();
            return;
        }
        int to;
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) to = from - 1;
        else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) to = from + 1;
        else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) to = from - COLUMNS;
        else if (from + COLUMNS < n) to = from + COLUMNS;
        else to = from / COLUMNS < (n - 1) / COLUMNS ? n - 1 : from;
        if (to < 0 || to >= n || to == from) return;
        keepScroll(() -> adapter.move(from, to));
        // The owner carries this tile: where it lands decides whether it is in the dock, not dock_apps.
        if (to < COLUMNS) dockInSession.add(movingKey);
        else dockInSession.remove(movingKey);
        refocusAfterLayout(to);
    }

    /**
     * Applies an adapter change without the grid jumping. After a move the layout manager anchors on the focused
     * tile, keeping it at its old place on screen and shifting every other row; pinning the first visible row at its
     * current offset as the next layout's anchor keeps the scroll where it was. onTileFocused still scrolls
     * afterwards if the moved tile itself ends up off screen.
     */
    private void keepScroll(Runnable change) {
        GridLayoutManager lm = (GridLayoutManager) grid.getLayoutManager();
        int first = lm.findFirstVisibleItemPosition();
        View firstView = first != RecyclerView.NO_POSITION ? lm.findViewByPosition(first) : null;
        int offset = firstView == null ? 0 : lm.getDecoratedTop(firstView) - grid.getPaddingTop();
        change.run();
        if (firstView != null) lm.scrollToPositionWithOffset(first, offset);
    }

    /**
     * Once the grid has applied the change, put the glass (and focus, if the move dropped it) on the tile at pos.
     * The grid has a fixed size, so it applies adapter changes on the next animation frame rather than in a layout
     * pass; until then it cannot find the tile by position. Wait frame by frame (at most 10) for that. No focus
     * change fires for a moved tile, so focusTile's already-focused path calls onTileFocused itself.
     */
    private void refocusAfterLayout(int pos) {
        grid.postOnAnimation(new Runnable() {
            int frames;

            @Override
            public void run() {
                if ((grid.hasPendingAdapterUpdates() || grid.isLayoutRequested()) && ++frames < 10) {
                    grid.postOnAnimation(this);
                    return;
                }
                focusTile(pos);
                dimOthers();   // views that came into view or were rebound during the move
            }
        });
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        // Home screen: Back goes nowhere.
    }

    // ---- glass highlight -------------------------------------------------------------------------

    @Override
    public void onLibraryRequested() {
        if (appLibrary != null) appLibrary.close();
        if (openPopup != null) openPopup.dismiss();
        libraryFocusPending = false;
        libraryFromTopBar = false;
        AppLibraryDialog.forkDockHasRoom = fork != null && fork.homeDockOnly
                ? () -> adapter != null && adapter.dockedCount() < COLUMNS : null;   // vPro
        appLibrary = new AppLibraryDialog(this, animations, appLibrarySelection, libraryOkOpens, adapter::containsPackage, () -> reloadApps(false), () -> {
            appLibrary = null;
            openPopup = null;
            if (libraryFromTopBar) {
                libraryFromTopBar = false;
                btnLibraryTop.requestFocus();
                return;
            }
            libraryFocusPending = true;
            grid.post(() -> {
                if (libraryFocusPending && appLibrary == null && hasWindowFocus() && !isFinishing()) focusLibrary();
            });
        }, libraryExcludedApps);
        openPopup = appLibrary.popup;
        appLibrary.show();
    }

    @Override
    public void onTileFocused(int position, View card) {
        lastFocused = position;
        lastFocusedKey = adapter.keyAt(position);
        // Focus restored before the first layout (cold start): the tile has no size yet, and the glass
        // would be sized to 2*pad. Run again once the tile is laid out.
        if (card.getWidth() == 0) {
            card.post(() -> {
                if (card.isFocused() && card.getWidth() > 0) onTileFocused(position, card);
            });
            return;
        }
        // Tile rect in root coordinates, using layout positions (ignores the scale animation).
        Rect r = tileRect;
        r.set(0, 0, card.getWidth(), card.getHeight());
        root.offsetDescendantRectToMyCoords(card, r);
        float pad = 6f * density;        // matches GlassView's glow inset
        float scale = adapter.focusScale();
        float w = card.getWidth() * scale + 2 * pad;
        float h = card.getHeight() * scale + 2 * pad;
        float x = r.exactCenterX() - w / 2f;
        // advanced_effects lifts a focused grid tile by 4dp and the glass follows it; dock tiles stay put.
        boolean lifted = advancedEffects && !DockSlots.inDockRow(dockRow, position, COLUMNS);
        float y = r.exactCenterY() - h / 2f - (lifted ? 4f * density : 0f);

        ViewGroup.LayoutParams lp = glass.getLayoutParams();
        if (lp.width != (int) w || lp.height != (int) h || card.getWidth() != glassCardW) {
            lp.width = (int) w;
            lp.height = (int) h;
            glass.setLayoutParams(lp);
            // GlassView draws its slab inset by pad, i.e. exactly over the zoomed tile: same corners as the tile.
            glass.setRadius(AppAdapter.cornerRadius(card.getWidth(), density) * scale);
        }
        glassCardW = card.getWidth();
        glassCardRect.set(r);
        glassTargetX = x;
        glassTargetY = y;
        if (!glassShown) {
            springX.cancel();
            springY.cancel();
            glass.setTranslationX(x);
            glass.setTranslationY(y);
            if (animations) glass.animate().alpha(1f).setDuration(180).start();
            else glass.setAlpha(1f);
            glassShown = true;
        } else if (!animations) {
            springX.cancel();
            springY.cancel();
            glass.setTranslationX(x);
            glass.setTranslationY(y);
        } else {
            springX.animateToFinalPosition(x);
            springY.animateToFinalPosition(y - glassScrollShift);
        }
        View item = (View) card.getParent();
        if (dockRow && position < COLUMNS) {
            // Dock: park it at the bottom of the screen and show the top shelf for this app.
            int decoratedTop = item.getTop() - (int) (6 * density);
            int dy = decoratedTop - grid.getPaddingTop();
            // A 1px mismatch used to start a scroll on every dock move. Only scroll when the row is actually off.
            boolean scroll = Math.abs(dy) > (int) (8 * density);
            if (scroll) {
                if (!animations) grid.scrollBy(0, dy);
                else grid.smoothScrollBy(0, dy);
            }
            showHero(position, scroll ? r.top - dy : r.top);
        } else {
            // Below the dock (or in a plain grid) there is no big name: every grid tile has its name underneath.
            hero.animate().cancel();
            hero.setAlpha(0f);
            heroShown = false;
            // Scroll just enough so the whole glass (not only the tile) is visible.
            revealRect.set(0, -(int) pad * 2, item.getWidth(), item.getHeight() + (int) pad);
            grid.requestChildRectangleOnScreen(item, revealRect, false);
        }
    }

    /** Refits the glass to the focused holder once the grid layout and tile dimensions settle. */
    private void refitGlass() {
        if (isDestroyed() || adapter.appCount() == 0 || libraryFocusPending) return;
        int pos = Math.max(0, Math.min(lastFocused, adapter.appCount() - 1));
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(pos);
        View card = vh == null ? null : vh.itemView.findViewById(R.id.card);
        if (card == null || card.getWidth() <= 0 || !card.isFocused()) return;
        float pad = 6f * density;
        float scale = adapter.focusScale();
        int w = (int) (card.getWidth() * scale + 2 * pad);
        int h = (int) (card.getHeight() * scale + 2 * pad);
        // The tile's rect, not only its width: the grid's top padding lands after the tile has its width, so a
        // cold start can leave the glass at the right size and the wrong place. Comparing the rect catches that.
        Rect r = tileRect;
        r.set(0, 0, card.getWidth(), card.getHeight());
        root.offsetDescendantRectToMyCoords(card, r);
        if (!glassShown || card.getWidth() != glassCardW || glass.getWidth() != w || glass.getHeight() != h
                || !glassCardRect.equals(r)) {
            onTileFocused(pos, card);
        }
    }

    // ---- top shelf -------------------------------------------------------------------------------

    private void showHero(int position, float dockTopInRoot) {
        String label = adapter.labelAt(position);
        float y = dockTopInRoot - 14 * density - 26 * density - hero.getHeight();
        boolean changed = !label.contentEquals(heroName.getText());
        if (!animations) {
            hero.animate().cancel();
            heroName.setText(label);
            hero.setY(y);
            hero.setTranslationX(0f);
            hero.setAlpha(1f);
            heroShown = true;
            return;
        }
        if (!heroShown) {
            hero.animate().cancel();
            heroName.setText(label);
            hero.setY(y);
            hero.setTranslationX(0f);
            hero.animate().alpha(1f).setDuration(260).start();
            heroShown = true;
        } else if (changed) {
            if (advancedEffects) hero.animate().cancel();
            hero.animate().alpha(0f).translationX(-12 * density).setDuration(120).withEndAction(() -> {
                heroName.setText(label);
                hero.setY(y);
                hero.setTranslationX(12 * density);
                hero.animate().alpha(1f).translationX(0f).setDuration(200).start();
            }).start();
        } else {
            hero.setY(y);
            // onPause cancels the fade-in, and a second Home during one lands on the same tile, which starts none:
            // finish it, or the shelf stays dim until focus moves to another app.
            if (hero.getAlpha() < 1f) hero.animate().alpha(1f).setDuration(260).start();
        }
    }

    private void hideHero() {
        if (!heroShown) return;
        hero.animate().cancel();
        heroShown = false;
        if (animations) hero.animate().alpha(0f).setDuration(160).start();
        else hero.setAlpha(0f);
    }

    private void hideGlass() {
        if (!glassShown) return;
        glassShown = false;
        glass.animate().cancel();
        if (animations) glass.animate().alpha(0f).setDuration(160).start();
        else glass.setAlpha(0f);
    }

    // ---- navigation ------------------------------------------------------------------------------

    @Override
    public boolean onTileVerticalKey(int position, int keyCode) {
        int count = adapter.appCount();
        int target;
        // Horizontal: swallow the key at a row's ends so focus never wraps to the next row or
        // escapes up into the top bar. Mid-row moves fall through to the default focus search.
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            boolean forward = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
            if (forward && position == adapter.focusablePosition(count - 1) && adapter.isLibraryAt(count)) {
                focusLibrary();
                return true;
            }
            // In the dock, the last app is the row's end: the empty slots after it take no focus.
            int rowCount = position < COLUMNS ? Math.min(count, adapter.dockFilled()) : count;
            return GridNavigation.atHorizontalEdge(position, rowCount, COLUMNS, keyCode == KeyEvent.KEYCODE_DPAD_LEFT);
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            if (position < COLUMNS) return false;          // dock row: let focus move to the top bar
            target = position - COLUMNS;
        } else {
            int lastRowStart = ((count - 1) / COLUMNS) * COLUMNS;
            if (position >= lastRowStart) {
                if (adapter.isLibraryAt(count)) focusLibrary();
                return true;
            }
            target = Math.min(position + COLUMNS, count - 1);
        }
        focusTile(target);
        // The default focus search clicks for Left/Right; Up/Down are handled here, so click for them too.
        grid.playSoundEffect(keyCode == KeyEvent.KEYCODE_DPAD_UP
                ? SoundEffectConstants.NAVIGATION_UP : SoundEffectConstants.NAVIGATION_DOWN);
        return true;
    }

    @Override public void onLibraryFocused() {
        libraryFocusPending = false;
        hideGlass();
        hideHero();
    }

    /**
     * date_below_clock: the date column moves to its own line under the clock, in the vertical LinearLayout that holds
     * the clock's row, and the line between them goes.
     */
    private void moveDateBelowClock() {
        View column = (View) findViewById(R.id.date).getParent();
        View clockRow = (View) findViewById(R.id.clock).getParent();
        LinearLayout clockColumn = (LinearLayout) clockRow.getParent();
        ((ViewGroup) column.getParent()).removeView(column);
        findViewById(R.id.clockDivider).setVisibility(View.GONE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Math.round(4 * density);
        clockColumn.addView(column, clockColumn.indexOfChild(clockRow) + 1, lp);
        // The column now wraps its texts, so they must wrap too: a match_parent TextView does not ask for a new
        // layout when its text gets longer, and the column kept the width of the day before ("TUESDAY 27 OCTOB…").
        ViewGroup texts = (ViewGroup) column;
        for (int i = 0; i < texts.getChildCount(); i++) {
            View text = texts.getChildAt(i);
            LinearLayout.LayoutParams textLp = (LinearLayout.LayoutParams) text.getLayoutParams();
            textLp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            // Keep each line where its own gravity put it now that it no longer fills the column.
            textLp.gravity = ((TextView) text).getGravity() & Gravity.HORIZONTAL_GRAVITY_MASK;
            text.setLayoutParams(textLp);
        }
    }

    /** Centre the one-row header when the greeting and extra date lines are absent. */
    private void centerCompactHeader() {
        LinearLayout clockRow = (LinearLayout) findViewById(R.id.clock).getParent();
        clockRow.setGravity(Gravity.CENTER_VERTICAL);
        clockRow.setBaselineAligned(false);
        View[] columns = {findViewById(R.id.clockDivider), (View) findViewById(R.id.date).getParent()};
        for (View column : columns) {
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) column.getLayoutParams();
            lp.gravity = Gravity.CENTER_VERTICAL;
            lp.bottomMargin = 0;
            column.setLayoutParams(lp);
        }
        LinearLayout buttons = findViewById(R.id.topButtons);
        RelativeLayout.LayoutParams lp = (RelativeLayout.LayoutParams) buttons.getLayoutParams();
        lp.addRule(RelativeLayout.ALIGN_TOP, R.id.leftBlock);
        lp.bottomMargin = 0;
        buttons.setLayoutParams(lp);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
    }

    /** With the date below, keep the icons beside the clock rather than beside the last date line. */
    private void alignDateBelowIconsWithClock() {
        View clock = findViewById(R.id.clock);
        View clockRow = (View) clock.getParent();
        LinearLayout buttons = findViewById(R.id.topButtons);
        RelativeLayout.LayoutParams lp = (RelativeLayout.LayoutParams) buttons.getLayoutParams();
        lp.addRule(RelativeLayout.ALIGN_TOP, R.id.leftBlock);
        lp.removeRule(RelativeLayout.ALIGN_BOTTOM);
        lp.topMargin = 0;
        lp.bottomMargin = 0;
        buttons.setLayoutParams(lp);
        // Either side can be laid out first; update after both without requesting another layout mid-pass.
        Runnable align = () -> buttons.setTranslationY(clockRow.getTop() + clock.getTop()
                + (clock.getHeight() - buttons.getHeight()) / 2f);
        clockRow.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> align.run());
        buttons.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> align.run());
    }

    // ---- vPro: weather and NBG rates (InfoWidgets) -----------------------------------------

    private TextView infoText(float sp, int color, int fontRes, float spacing) {
        TextView t = new TextView(this);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(android.graphics.Typeface.create(getString(fontRes), android.graphics.Typeface.NORMAL));
        t.setLetterSpacing(spacing);
        t.setIncludeFontPadding(false);
        t.setFocusable(false);
        t.setVisibility(View.GONE);
        return t;
    }

    /** Puts the rates next to the greeting and the weather under the clock and date; both stay gone until on. */
    private void setupInfoWidgets() {
        if (greeting != null && greeting.getParent() instanceof LinearLayout) {
            LinearLayout block = (LinearLayout) greeting.getParent();
            int index = block.indexOfChild(greeting);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            // Four rates (one of them "1000 AMD") may not fit beside the greeting within the clock's column, which ends where the top
            // buttons begin. They sit above those buttons, so the row may run on towards the screen's edge.
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    getResources().getDisplayMetrics().widthPixels, ViewGroup.LayoutParams.WRAP_CONTENT);
            block.setClipChildren(false);
            if (block.getParent() instanceof ViewGroup) ((ViewGroup) block.getParent()).setClipChildren(false);
            ViewGroup.LayoutParams old = greeting.getLayoutParams();
            if (old instanceof ViewGroup.MarginLayoutParams) {
                rowParams.setMarginStart(((ViewGroup.MarginLayoutParams) old).getMarginStart());
            }
            block.removeView(greeting);
            LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            row.addView(greeting, gp);
            ratesView = infoText(12, 0xD9FFFFFF, R.string.font_medium, 0.08f);
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rp.setMarginStart((int) (28 * getResources().getDisplayMetrics().density));
            row.addView(ratesView, rp);
            block.addView(row, index, rowParams);
        }
        View left = findViewById(R.id.leftBlock);
        if (left != null && left.getParent() instanceof RelativeLayout) {
            RelativeLayout header = (RelativeLayout) left.getParent();
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            RelativeLayout.LayoutParams bp = new RelativeLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bp.addRule(RelativeLayout.BELOW, R.id.leftBlock);
            bp.addRule(RelativeLayout.ALIGN_LEFT, R.id.leftBlock);
            float d = getResources().getDisplayMetrics().density;
            bp.topMargin = (int) (8 * d);
            bp.leftMargin = (int) (4 * d);
            weatherNow = infoText(18, 0xE6FFFFFF, R.string.font_regular, 0.02f);
            weatherDays = infoText(14, 0xB3FFFFFF, R.string.font_regular, 0.04f);
            LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dp.topMargin = (int) (8 * d);
            box.addView(weatherNow);
            box.addView(weatherDays, dp);
            header.addView(box, bp);
        }
        // The news ticker: one line under the dock, scrolling while Home is on screen.
        if (root instanceof FrameLayout) {
            float d = getResources().getDisplayMetrics().density;
            newsTicker = new NewsTicker(this, android.graphics.Typeface.create(getString(R.string.font_regular),
                    android.graphics.Typeface.NORMAL));
            newsTicker.setVisibility(View.GONE);
            FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM);
            tp.leftMargin = tp.rightMargin = (int) (57 * d);   // lined up with the dock band
            tp.bottomMargin = (int) (5 * d);
            ((FrameLayout) root).addView(newsTicker, tp);
        }
        // vPro: the focused dock app's name a little smaller (48sp in the stock shelf).
        if (fork != null && fork.homeDockOnly && heroName != null) {
            heroName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 38f);
        }
        updateInfoWidgets();
    }

    /** Shows what InfoWidgets has kept; switched-off parts go away. */
    private void updateInfoWidgets() {
        if (fork == null) return;
        if (ratesView != null) {
            CharSequence line = InfoWidgets.ratesOn(this, fork) ? InfoWidgets.ratesLine(this) : null;
            ratesView.setText(line);
            ratesView.setVisibility(line == null ? View.GONE : View.VISIBLE);
        }
        if (weatherNow != null) {
            String[] lines = InfoWidgets.weatherOn(this, fork) ? InfoWidgets.weatherLines(this) : null;
            weatherNow.setText(lines == null ? null : lines[0]);
            weatherNow.setVisibility(lines == null ? View.GONE : View.VISIBLE);
            boolean days = lines != null && lines[1] != null && !lines[1].isEmpty();
            weatherDays.setText(days ? lines[1] : null);
            weatherDays.setVisibility(days ? View.VISIBLE : View.GONE);
        }
        if (newsTicker != null) {
            String news = InfoWidgets.newsOn(this, fork) ? InfoWidgets.newsLine(this) : null;
            if (news == null) {
                newsTicker.setVisibility(View.GONE);
                newsTicker.setRunning(false);
            } else {
                android.content.SharedPreferences np = InfoWidgets.prefs(this);
                newsTicker.setTextSizeSp(np.getInt(InfoWidgets.KEY_NEWS_SIZE, InfoWidgets.NEWS_SIZE_DEFAULT));
                newsTicker.setSpeed(np.getInt(InfoWidgets.KEY_NEWS_SPEED, InfoWidgets.NEWS_SPEED_DEFAULT));
                newsTicker.setBandTransparency(np.getInt(InfoWidgets.KEY_NEWS_BG, InfoWidgets.NEWS_BG_DEFAULT));
                newsTicker.setText(news);
                newsTicker.setVisibility(View.VISIBLE);
                newsTicker.setRunning(resumed);   // animates only while Home is on screen
            }
        }
    }

    private void refreshInfoWidgets() {
        updateInfoWidgets();
        if (fork != null) InfoWidgets.refresh(this, fork, () -> {
            if (!isFinishing() && !isDestroyed()) updateInfoWidgets();
        });
    }

    /**
     * vPro: "Close" in the tile menu, a real force-stop through the TV's own ADB (LocalAdb), as
     * TvQuickActions does. Needs USB/network debugging on and this launcher's key allowed once on the TV's
     * prompt; without ADB it opens App info, where Force stop is one press away.
     */
    private void closeApp(String pkg) {
        String label = pkg;
        try {
            label = String.valueOf(getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0)));
        } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {
        }
        final String name = label;
        new Thread(() -> {
            LocalAdb.Result r = LocalAdb.forceStop(getApplicationContext(), pkg);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                switch (r) {
                    case STOPPED:
                        Toast.makeText(this, getString(R.string.fork_tile_closed, name), Toast.LENGTH_SHORT).show();
                        break;
                    case STILL_RUNNING:
                        Toast.makeText(this, getString(R.string.fork_tile_still_running, name), Toast.LENGTH_LONG).show();
                        break;
                    case NOT_ALLOWED:
                        Toast.makeText(this, R.string.fork_tile_allow_adb, Toast.LENGTH_LONG).show();
                        break;
                    default: {
                        Intent info = SystemSettings.appInfo(this, pkg);
                        Toast.makeText(this, R.string.fork_tile_no_adb, Toast.LENGTH_LONG).show();
                        if (info != null) open(info);
                    }
                }
            });
        }, "close-app").start();
    }

    /** vPro: free half-columns on each side of a dock with fewer apps than columns. */
    private int dockSpareHalves() {
        if (adapter == null) return 0;
        return Math.max(0, COLUMNS - Math.min(COLUMNS, adapter.dockedCount()));
    }

    /** The first top-bar button after Inputs that is shown, in bar order (top_bar_apps, the network pill, ...); Settings is always there. */
    private View firstTopButton() {
        ViewGroup bar = findViewById(R.id.topButtons);
        for (int k = 0; k < bar.getChildCount(); k++) {
            View button = bar.getChildAt(k);
            if (button != btnInputs && button.getVisibility() == View.VISIBLE) return button;
        }
        return findViewById(R.id.btnSettings);
    }

    /** top_bar_apps buttons, in bar order. */
    private final List<View> topAppButtons = new ArrayList<>();

    /**
     * Puts each top_bar_apps app in the top bar as a round pill with the app's own logo, left of the network pill.
     * The logo keeps its colours: the pill style's tint, which turns the other icons white, is cleared.
     */
    private void addTopBarApps(List<String> packages, Map<String, String> launchActions) {
        ViewGroup bar = findViewById(R.id.topButtons);
        PackageManager pm = getPackageManager();
        int size = Math.round(22 * density);
        for (String pkg : packages) {
            Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (launch == null) launch = pm.getLaunchIntentForPackage(pkg);
            if (launch == null) continue;   // not installed, or disabled
            // Use the same configured launch_actions as tiles; retain the normal entry if unavailable.
            launch = LaunchActions.select(pkg, launchActions, launch, (targetPackage, action) -> {
                Intent custom = LaunchActions.intent(targetPackage, action);
                return pm.resolveActivity(custom, 0) != null ? custom : null;
            });
            Drawable logo;
            CharSequence label;
            try {
                ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
                logo = info.loadIcon(pm);
                label = info.loadLabel(pm);
            } catch (PackageManager.NameNotFoundException e) {
                continue;
            }
            TextView button = (TextView) getLayoutInflater().inflate(R.layout.top_app_button, bar, false);
            // pillKeys finds the bar's ends by id: without one of its own, every app button would be an end.
            button.setId(View.generateViewId());
            logo.setBounds(0, 0, size, size);
            button.setCompoundDrawablesRelative(logo, null, null, null);
            button.setCompoundDrawableTintList(null);
            button.setContentDescription(label);
            Intent target = launch;
            button.setOnClickListener(v -> open(new Intent(target)));
            // Left of the network pill.
            bar.addView(button, bar.indexOfChild(btnWifi));
            topAppButtons.add(button);
        }
    }

    @Override public void onLibraryUp() {
        if (adapter.appCount() == 0) { firstTopButton().requestFocus(); return; }
        int lastRow = ((adapter.appCount() - 1) / COLUMNS) * COLUMNS;
        focusTile(Math.min(lastRow + lastFocused % COLUMNS, adapter.appCount() - 1));
    }

    private void focusLibrary() {
        cancelPendingFocusScroll();
        int position = adapter.appCount();
        if (!adapter.isLibraryAt(position)) return;
        RecyclerView.ViewHolder holder = grid.findViewHolderForAdapterPosition(position);
        if (holder != null) {
            View button = holder.itemView.findViewById(R.id.btnLibrary);
            Rect visible = new Rect();
            if (button.getGlobalVisibleRect(visible) && visible.height() >= button.getHeight()) {
                if (button.requestFocus()) libraryFocusPending = false;
                return;
            }
        }
        if (!animations) {
            grid.scrollToPosition(position);
            grid.post(() -> {
                if (appLibrary != null || !hasWindowFocus()) return;
                RecyclerView.ViewHolder footer = grid.findViewHolderForAdapterPosition(adapter.appCount());
                if (footer != null && adapter.isLibraryAt(adapter.appCount())
                        && footer.itemView.findViewById(R.id.btnLibrary).requestFocus()) libraryFocusPending = false;
            });
            return;
        }
        pendingFocusScroll = new RecyclerView.OnScrollListener() {
            @Override public void onScrollStateChanged(@NonNull RecyclerView rv, int state) {
                if (state != RecyclerView.SCROLL_STATE_IDLE) return;
                rv.removeOnScrollListener(this);
                if (pendingFocusScroll != this) return;
                pendingFocusScroll = null;
                RecyclerView.ViewHolder footer = rv.findViewHolderForAdapterPosition(position);
                if (footer != null && footer.itemView.findViewById(R.id.btnLibrary).requestFocus())
                    libraryFocusPending = false;
            }
        };
        grid.addOnScrollListener(pendingFocusScroll);
        grid.smoothScrollToPosition(position);
    }

    private void cancelPendingFocusScroll() {
        if (pendingFocusScroll != null) {
            grid.removeOnScrollListener(pendingFocusScroll);
            pendingFocusScroll = null;
            grid.stopScroll();
        }
    }

    private void focusTile(int position) {
        libraryFocusPending = false;
        cancelPendingFocusScroll();
        if (adapter.appCount() == 0) {
            if (adapter.isLibraryAt(0)) focusLibrary();
            else firstTopButton().requestFocus();
            return;
        }
        final int pos = adapter.focusablePosition(Math.max(0, Math.min(position, adapter.appCount() - 1)));
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(pos);
        if (vh != null) {
            View card = vh.itemView.findViewById(R.id.card);
            // Already focused (e.g. back from an app): no focus change fires, so restore the glass by hand.
            if (card.isFocused()) onTileFocused(pos, card);
            else card.requestFocus();
            return;
        }
        // Not laid out yet: scroll there, then focus once the scroll settles.
        if (!animations) {
            grid.scrollToPosition(pos);
            grid.post(() -> {
                RecyclerView.ViewHolder h = grid.findViewHolderForAdapterPosition(pos);
                if (h != null) h.itemView.findViewById(R.id.card).requestFocus();
            });
            return;
        }
        pendingFocusScroll = new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return;
                rv.removeOnScrollListener(this);
                if (pendingFocusScroll != this) return;
                pendingFocusScroll = null;
                RecyclerView.ViewHolder h = rv.findViewHolderForAdapterPosition(pos);
                View target = h != null ? h.itemView.findViewById(R.id.card) : rv.getChildAt(0);
                if (target != null) target.requestFocus();
            }
        };
        grid.addOnScrollListener(pendingFocusScroll);
        grid.smoothScrollToPosition(pos);
    }

    // ---- top bar ---------------------------------------------------------------------------------

    /** Some TVs set the network time without ACTION_TIME_CHANGED, so keep checking until it is valid. */
    private final Runnable clockCheck = this::updateClockVisibility;

    private void updateClockVisibility() {
        boolean valid = System.currentTimeMillis() >= TIME_VALID_AFTER_MS;
        clockBlock.setVisibility(valid ? View.VISIBLE : View.INVISIBLE);
        handler.removeCallbacks(clockCheck);
        if (valid) {
            updateGreeting();
        } else {
            handler.postDelayed(clockCheck, 1_000L);
        }
    }

    private void updateGreeting() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        int res;
        if (hour < 5) res = R.string.good_night;
        else if (hour < 12) res = R.string.good_morning;
        else if (hour < 17) res = R.string.good_afternoon;
        else if (hour < 22) res = R.string.good_evening;
        else res = R.string.good_night;
        greeting.setText(greetingName.isEmpty() ? getString(res)
                : getString(R.string.greeting_with_name, getString(res), greetingName));
        greeting.setTextColor(greetingColor);
    }

    private void refreshPillsAfterNextDraw() {
        root.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                root.getViewTreeObserver().removeOnPreDrawListener(this);
                // Posted from inside the frame's traversal, so it runs after that frame is drawn. A post straight
                // from onResume would run first: the frame is only scheduled after onResume returns.
                handler.post(() -> {
                    if (isDestroyed()) return;
                    updateWifiLabel();
                    updateInputsButton();
                });
                return true;
            }
        });
        root.invalidate();   // a resume that changes nothing on screen still draws once, so the refresh runs
    }

    /** Shows the Wi-Fi network name on the button when we can read it, otherwise the connection type. */
    @SuppressWarnings("deprecation")
    private void updateWifiLabel() {
        String text = getString(R.string.wifi);
        boolean vpn = false, online = false, validated = false;
        int icon = R.drawable.ic_wifi;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            boolean wifi = false, ethernet = false;
            // All networks, not just the active one: with a VPN up the active network is the VPN itself.
            for (Network net : cm != null ? cm.getAllNetworks() : new Network[0]) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                if (caps == null) continue;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    vpn = true;
                    continue;
                }
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
                online = true;
                // Android reached the internet through it; a captive portal stays unvalidated until login.
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) validated = true;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) ethernet = true;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) wifi = true;
            }
            if (!online) {
                text = getString(R.string.offline);
                vpn = false;
            } else if (ethernet) {
                text = getString(R.string.ethernet);
                icon = R.drawable.ic_ethernet;
            } else if (wifi) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
                String ssid = info != null ? info.getSSID() : null;
                if (ssid != null) {
                    ssid = ssid.replace("\"", "").trim();
                    if (!ssid.isEmpty() && !ssid.contains("unknown") && !ssid.equals("0x")) text = ssid;
                }
            }
        } catch (Exception ignored) {
        }
        if (vpn) {
            // Network name, then the green lock-and-VPN capsule drawn in place of the word.
            String badge = getString(R.string.vpn);
            VpnBadge span = new VpnBadge(getDrawable(R.drawable.ic_lock), density);
            // The pill has a maximum width and ellipsizes at the end, which would cut the badge off after
            // a long network name, so shorten the name to whatever room the badge leaves. The Wi-Fi icon and its
            // gap are counted by hand: until the pill has a resolved layout direction (the first call, from
            // onResume, runs before that) getTotalPaddingStart/End leave the compound drawables out, which
            // left the name about 31dp too much room and the badge cut off.
            TextPaint paint = btnWifi.getPaint();
            Drawable[] compound = btnWifi.getCompoundDrawablesRelative();
            int iconRoom = compound[0] != null ? compound[0].getIntrinsicWidth() + btnWifi.getCompoundDrawablePadding() : 0;
            float room = btnWifi.getMaxWidth() - btnWifi.getPaddingStart() - btnWifi.getPaddingEnd() - iconRoom
                    - span.getSize(paint, badge, 0, badge.length(), null) - paint.measureText(" ") - 2 * density;
            String name = TextUtils.ellipsize(text, paint, Math.max(0, room), TextUtils.TruncateAt.END).toString();
            // A long network name can leave room for a stub like "MT…", which says nothing and, after rounding,
            // can push the badge itself out of the pill. Show the badge alone then. On Ethernet the
            // icon already names the link, and "Ethernet" cut to "Ethe…" or not depended on the font size,
            // so Ethernet always shows the icon and the badge alone.
            // The space stays even then: a line holding only the span gets no font metrics and collapses.
            boolean stub = name.length() < text.length() && name.length() < 5;
            text = stub || icon == R.drawable.ic_ethernet ? "" : name;
            SpannableString label = new SpannableString(text + " " + badge);
            label.setSpan(span, text.length() + 1, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (!TextUtils.equals(btnWifi.getText(), label)) btnWifi.setText(label);
        } else if (!TextUtils.equals(btnWifi.getText(), text)) {
            btnWifi.setText(text);
        }
        if (wifiIconOnly) {
            btnWifi.setContentDescription(btnWifi.getText());
            btnWifi.setText(null);
        }
        {
            if (icon != wifiIcon) {
                wifiIcon = icon;
                btnWifi.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);
            }
            ColorStateList tint = online ? wifiOnlineTint : wifiPlainTint;
            // wifi_status_warnings: orange while connected but not validated (a captive portal), red while offline.
            if (wifiNoInternetTint != null) tint = !online ? wifiOfflineTint : validated ? wifiOnlineTint : wifiNoInternetTint;
            if (wifiOnlineTint != null && btnWifi.getCompoundDrawableTintList() != tint) {
                btnWifi.setCompoundDrawableTintList(tint);
            }
        }
    }

    /** config.xml icon_only_buttons. */
    private boolean iconOnlyButtons;
    private boolean launcherSettingsButton;
    /** icon_only_buttons without wifi_label_width: the Wi-Fi pill shows no network name. */
    private boolean wifiIconOnly;
    /** config.xml wifi_status_color: the Wi-Fi icon's green while online and its usual pill colours; null while off. */
    private ColorStateList wifiOnlineTint, wifiPlainTint;
    /** wifi_status_warnings (with wifi_status_color): orange without internet, red offline; null while off. */
    private ColorStateList wifiNoInternetTint, wifiOfflineTint;
    /** The app library button in the top bar (library_button). */
    private View btnLibraryTop;
    /** Voice search, right of the App library button. */
    private View btnVoice;
    /** The open library came from that button: closing it returns there instead of scrolling to the grid's end. */
    private boolean libraryFromTopBar;

    /** Turns a labelled top pill into the round TopButtonIcon shape: no text, the icon centred by even padding. */
    private void makeIconOnly(TextView pill) {
        pill.setContentDescription(pill.getText());
        pill.setText(null);
        pill.setMaxWidth(Integer.MAX_VALUE);
        int pad = Math.round(10 * density);
        pill.setPaddingRelative(pad, 0, pad, 0);
        pill.setCompoundDrawablePadding(0);
        pill.getLayoutParams().width = Math.round(42 * density);
    }

    /** The TV's settings screen for this action (SystemSettings). */
    private Intent systemIntent(String standardAction) {
        return SystemSettings.intent(this, standardAction);
    }

    /** Hides the Inputs pill when it would have nothing to list: streaming boxes have no HDMI-in, AV or tuner. */
    private void updateInputsButton() {
        if (!showInputsPill) {
            btnInputs.setVisibility(View.GONE);
            return;
        }
        boolean any = liveTvIntent() != null;
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (!any && tim != null) {
            for (TvInputInfo info : tim.getTvInputList()) {
                // Inputs hidden in Settings > Inputs are left out of the menu, so they don't count here either.
                if (info.isPassthroughInput() && !info.isHidden(this)) {
                    any = true;
                    break;
                }
            }
        }
        btnInputs.setVisibility(any ? View.VISIBLE : View.GONE);
    }

    /** The Open on start app: the first time Home comes up after the TV starts, open that app instead. Once per boot, keyed on Settings.Global.BOOT_COUNT and committed to disk before the app starts: on a
     *  low-memory TV the app can push the launcher out of memory, and an apply() still in flight was lost then, so
     *  every Home press opened the app again. */
    private boolean openLiveTvAfterBoot() {
        String app = openOnStartChoice ? getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE)
                .getString(OpenOnStart.KEY, "") : "";
        if (app.isEmpty()) return false;
        int boot = OpenOnStart.boot(this);
        SharedPreferences prefs = getSharedPreferences(OpenOnStart.BOOT_PREFS, MODE_PRIVATE);
        if (!OpenOnStart.due(boot, prefs.getInt(OpenOnStart.BOOT_KEY, -1), app, getPackageName())) return false;
        if (!prefs.edit().putInt(OpenOnStart.BOOT_KEY, boot).commit()) return false;
        Intent i = OpenOnStart.intent(getPackageManager(), app);
        if (i == null) return false;
        return openWhenReady(i);
    }

    /** open_on_start_on_wake: the remote's power button is a light sleep on some sets, with no boot for
     *  openLiveTvAfterBoot to catch. */
    private void openStartAppOnWake() {
        String app = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE).getString(OpenOnStart.KEY, "");
        if (app.isEmpty() || app.equals(getPackageName())) return;
        Intent i = OpenOnStart.intent(getPackageManager(), app);
        if (i != null) openWhenReady(i);
    }

    private boolean openWhenReady(Intent i) {
        // open_on_start_network_wait: Home stays on screen until the network is back or the wait is over.
        if (openOnStartWaitMs > 0 && !OpenOnStart.online(this)) {
            startPending = i;
            startDeadline = SystemClock.elapsedRealtime() + openOnStartWaitMs;
            handler.postDelayed(openWhenOnline, openOnStartWaitMs);
            // Validated between the check above and startPending: networkCallback may have skipped it.
            if (OpenOnStart.online(this)) handler.post(openWhenOnline);
            return false;
        }
        open(i);
        return true;
    }

    /** The Live TV app's launch intent (its leanback entry point), or null when it is not installed. */
    private Intent liveTvIntent() {
        if (liveTvPkg.isEmpty()) return null;
        return getPackageManager().getLeanbackLaunchIntentForPackage(liveTvPkg);
    }

    private void open(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Switches to a passthrough input (HDMI, AV): ACTION_VIEW on its channel URI, which the Live TV app handles. Aimed
     * at the TV app first: apps installed later (a streaming app, a file manager) can claim the same channel URI too,
     * and with two or more handlers Android shows a chooser instead of the input.
     */
    private void openInput(String inputId) {
        Intent view = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(TvContract.buildChannelUriForPassthroughInput(inputId), "vnd.android.cursor.item/channel")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (!liveTvPkg.isEmpty()) {
            try {
                startActivity(new Intent(view).setPackage(liveTvPkg));
                return;
            } catch (ActivityNotFoundException | SecurityException ignored) {
            }
        }
        open(view);
    }

    /** Dropdown menu under the Inputs pill: HDMI ports (with connection state), AV and Live TV. */
    private void showInputs() {
        View anchor = btnInputs;
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);

        // Compact: as wide as the Inputs pill (a touch wider so "HDMI 1" fits), hanging right below it.
        int width = Math.max(anchor.getWidth(), (int) (150 * density));
        final PopupWindow popup = glassPopup(panel, width);

        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        // inputs_connected_only: ports without a connected device and the AV jacks fold behind a "More inputs" row.
        boolean connectedOnly = inputsConnectedOnly;
        List<View> folded = new ArrayList<>();
        boolean sawNonHdmi = false;
        String tunerId = null;
        boolean tunerIsHw = false;
        boolean tunerIsAnalog = false;
        if (tim != null) {
            List<TvInputInfo> inputs = new ArrayList<>(tim.getTvInputList());
            // HDMI ports first in port order, then AV and the rest.
            Collections.sort(inputs, (a, b) -> {
                boolean ha = a.getType() == TvInputInfo.TYPE_HDMI, hb = b.getType() == TvInputInfo.TYPE_HDMI;
                if (ha != hb) return ha ? -1 : 1;
                return String.valueOf(a.loadLabel(this)).compareToIgnoreCase(String.valueOf(b.loadLabel(this)));
            });
            // An HDMI-CEC device (an Apple TV, a soundbar) gets an input of its own whose parent is the
            // port it sits on, so the port and the device would each get a row (Vestel MT7332: "HDMI 1"
            // and "HDMI Apple TV"). The device takes its port's row instead, and a bare port is listed
            // only when nothing on it speaks CEC.
            Set<String> inputIds = new HashSet<>();
            for (TvInputInfo info : inputs) inputIds.add(info.getId());
            Map<String, List<TvInputInfo>> cecDevices = new HashMap<>();
            for (TvInputInfo info : inputs) {
                String parent = info.getParentId();
                if (parent == null || !inputIds.contains(parent)) continue;
                if (!info.isPassthroughInput() || info.isHidden(this)) continue;
                List<TvInputInfo> onPort = cecDevices.get(parent);
                if (onPort == null) cecDevices.put(parent, onPort = new ArrayList<>());
                onPort.add(info);
            }
            // Some TVs (Sony) report every HDMI port as connected whether or not anything is plugged in,
            // so drop the HDMI status then. A TV with every port really in use loses it too.
            int hdmiPorts = 0, hdmiConnected = 0;
            for (TvInputInfo info : inputs) {
                if (!info.isPassthroughInput() || info.getType() != TvInputInfo.TYPE_HDMI) continue;
                if (info.getParentId() != null && inputIds.contains(info.getParentId())) continue;
                hdmiPorts++;
                if (tim.getInputState(info.getId()) == TvInputManager.INPUT_STATE_CONNECTED) hdmiConnected++;
            }
            // inputs_cec_state_only: the TV puts port states on the wrong port (TCL UnionTV, Android 9), so
            // ports show none, and a CEC device row shows its own state, which the CEC bus keeps right.
            boolean hdmiStateBogus = inputsCecStateOnly || (hdmiPorts > 1 && hdmiConnected == hdmiPorts);
            for (TvInputInfo info : inputs) {
                if (info.getType() == TvInputInfo.TYPE_TUNER
                        && (info.getServiceInfo().applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                    // Prefer a hardware tuner: the framework names those ".../HW<n>". System apps such as
                    // AirPlay also register software inputs of type TUNER and may sort first (seen on TCL G05).
                    // Among tuners of the same kind, skip an analog input when another is present.
                    // MediaTek names them AnalogInputService and TunerInputService; alphabetical order
                    // would pick the analog one, and Live TV then opens a dead ATV source.
                    boolean hw = info.getId().matches(".*/HW\\d+$");
                    boolean analog = info.getId().toLowerCase(Locale.ROOT).contains("analog");
                    boolean better = tunerId == null
                            || (hw && !tunerIsHw)
                            || (hw == tunerIsHw && tunerIsAnalog && !analog)
                            || (hw == tunerIsHw && analog == tunerIsAnalog
                                    && info.getId().compareTo(tunerId) < 0);
                    if (better) {
                        tunerId = info.getId();
                        tunerIsHw = hw;
                        tunerIsAnalog = analog;
                    }
                }
                // Inputs hidden in the TV's Settings > Inputs stay out of the menu too.
                if (!info.isPassthroughInput() || info.isHidden(this)) continue;
                // A CEC device's row is added with its port, below.
                if (info.getParentId() != null && inputIds.contains(info.getParentId())) continue;
                CharSequence l = info.loadLabel(this);
                String label = l == null ? info.getId() : l.toString();
                boolean hdmi = info.getType() == TvInputInfo.TYPE_HDMI;
                if (hdmi && !label.toUpperCase(Locale.ROOT).contains("HDMI")) label = "HDMI " + label;
                CharSequence custom = info.loadCustomLabel(this);
                boolean named = custom != null && custom.length() > 0 && !custom.toString().equals(label);
                if (!hdmi && rows.getChildCount() > 0 && !sawNonHdmi) {
                    View line = addSeparator(rows);
                    if (connectedOnly) fold(line, folded);
                }
                if (!hdmi) sawNonHdmi = true;
                List<TvInputInfo> devices = cecDevices.get(info.getId());
                if (devices != null) {
                    // "Apple TV · HDMI 1": the device's own name (or the one given to it or to its port in
                    // Settings > Inputs), then the port. Opening the device's input also makes it the CEC
                    // active source, which wakes it.
                    for (TvInputInfo device : devices) {
                        CharSequence name = device.loadCustomLabel(this);
                        if (name == null || name.length() == 0) name = named ? custom : device.loadLabel(this);
                        String deviceLabel = name == null || name.length() == 0 ? label : name + " \u00B7 " + label;
                        final String deviceId = device.getId();
                        int state = hdmiStateBogus && !inputsCecStateOnly ? -1 : tim.getInputState(deviceId);
                        View row = addInputRow(rows, popup, R.drawable.ic_input, deviceLabel, state, () -> openInput(deviceId));
                        if (connectedOnly && InputFold.folds(true, state)) fold(row, folded);
                    }
                    continue;
                }
                // The name given in Settings > Inputs (e.g. "MacBook Pro") goes first, the port after it.
                if (named) label = custom + " \u00B7 " + label;
                final String id = info.getId();
                // Analog AV jacks have no hotplug detection, and some TVs report them as always connected
                // (TCL Smart TV Pro G03 says "Connected" with nothing plugged in), so AV rows show no state.
                int state = !hdmi || hdmiStateBogus ? -1 : tim.getInputState(id);
                View row = addInputRow(rows, popup, hdmi ? R.drawable.ic_input : R.drawable.ic_av, label, state,
                        () -> openInput(id));
                if (connectedOnly && InputFold.folds(hdmi, state)) fold(row, folded);
            }
        }
        if (!folded.isEmpty()) {
            View more = getLayoutInflater().inflate(R.layout.item_input, rows, false);
            ((ImageView) more.findViewById(R.id.icon)).setImageResource(R.drawable.ic_input);
            ((TextView) more.findViewById(R.id.title)).setText(R.string.more_inputs);
            more.findViewById(R.id.status).setVisibility(View.GONE);
            more.findViewById(R.id.dot).setVisibility(View.GONE);
            more.setOnClickListener(v -> {
                for (View f : folded) f.setVisibility(View.VISIBLE);
                rows.removeView(more);
                for (View f : folded) {
                    if (f.isFocusable()) { f.requestFocus(); break; }
                }
            });
            rows.addView(more);
        }
        Intent liveTv = liveTvIntent();
        if (liveTv != null && tunerId != null) {
            // Tune that input only when the Live TV package itself handles the URI; otherwise keep the leanback
            // activity, the same path as the Live TV tile. Do not drop the package restriction.
            Intent tuned = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(TvContract.buildChannelUriForPassthroughInput(tunerId),
                            TvContract.Channels.CONTENT_ITEM_TYPE)
                    .setPackage(liveTvPkg);
            if (tuned.resolveActivity(getPackageManager()) != null) liveTv = tuned;
        }
        if (liveTv != null) {
            if (rows.getChildCount() > 0) addSeparator(rows);
            final Intent liveTvIntent = liveTv;
            addInputRow(rows, popup, R.drawable.ic_livetv, getString(R.string.live_tv), -1, () -> open(liveTvIntent));
        }
        if (rows.getChildCount() == 0) {
            Toast.makeText(this, R.string.no_inputs, Toast.LENGTH_SHORT).show();
            return;
        }

        // Fit the widest row: TVs that report a status on every port (Sony) would otherwise squeeze the names out.
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        popup.setWidth(Math.max(width, Math.min(panel.getMeasuredWidth(), (int) (360 * density))));

        dropIn(popup, panel, anchor, Gravity.START);
        for (int i = 0; i < rows.getChildCount(); i++) {
            View child = rows.getChildAt(i);
            if (child.getVisibility() == View.VISIBLE && child.isFocusable()) { child.requestFocus(); break; }
        }
    }

    private static void fold(View view, List<View> folded) {
        view.setVisibility(View.GONE);
        folded.add(view);
    }

    private PopupWindow glassPopup(View panel, int width) {
        if (fork != null) ForkConfig.applyOpacity(panel, fork.menuOpacity);
        final PopupWindow popup = new PopupWindow(panel, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // needed for Back to dismiss
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setElevation(0f);
        popup.setOnDismissListener(() -> {
            if (openPopup == popup) openPopup = null;
        });
        openPopup = popup;
        return popup;
    }

    /** Drop in: fade and unfold from the top edge, hanging under the anchor pill. */
    private void dropIn(PopupWindow popup, View panel, View anchor, int gravity) {
        panel.setAlpha(0f);
        panel.setPivotY(0f);
        panel.setScaleY(0.92f);
        panel.setTranslationY(-8 * density);
        popup.showAsDropDown(anchor, 0, (int) (10 * density), gravity);
        panel.animate().alpha(1f).scaleY(1f).translationY(0f).setDuration(220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
    }

    private View addSeparator(LinearLayout rows) {
        View line = new View(this);
        line.setBackgroundColor(0x1FFFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) density);
        int m = (int) (6 * density), h = (int) (12 * density);
        lp.setMargins(h, m, h, m);
        rows.addView(line, lp);
        return line;
    }

    private View addInputRow(LinearLayout rows, PopupWindow popup, int iconRes, String title, int state, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        TextView status = row.findViewById(R.id.status);
        View dot = row.findViewById(R.id.dot);
        // This TV reports "standby" for every port whether or not something is plugged in, so only
        // definite states are shown.
        if (state == TvInputManager.INPUT_STATE_CONNECTED) {
            status.setText(R.string.connected);
            dot.setVisibility(View.VISIBLE);
        } else if (state == TvInputManager.INPUT_STATE_DISCONNECTED) {
            status.setText(R.string.not_connected);
            dot.setVisibility(View.GONE);
        } else {
            status.setVisibility(View.GONE);
            dot.setVisibility(View.GONE);
        }
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        rows.addView(row);
        return row;
    }

    // ---- launcher settings -------------------------------------------------------------------------

    /** The header opens launcher settings directly; hold OK keeps the extra home actions available. */
    private void showSettingsMenu() {
        View anchor = findViewById(R.id.btnSettings);
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        PopupWindow popup = glassPopup(panel, (int) (200 * density));
        if (!launcherSettingsButton) addSettingsMenuRow(rows, popup, R.string.launcher_settings, () -> startActivityForResult(
                new Intent(this, LauncherSettingsActivity.class), LAUNCHER_SETTINGS_REQUEST));
        if (dockRow) addSettingsMenuRow(rows, popup, R.string.dock_edit, this::startDockEdit);
        addSettingsMenuRow(rows, popup, R.string.reorder, this::toggleReorder);
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        popup.setWidth(Math.max((int) (200 * density), panel.getMeasuredWidth()));
        dropIn(popup, panel, anchor, Gravity.END);
        rows.getChildAt(0).requestFocus();
    }

    /** A tile configured with launch_actions "input:<id>" (config.xml): switch to that TV input directly,
     *  the same path the Inputs menu's own rows use, instead of opening the input's owning app normally. */
    @Override
    public void onOpenInput(String inputId) {
        openInput(inputId);
    }

    /**
     * Hold OK on a tile: a glass menu next to it. Move starts reorder mode with this tile already picked up;
     * Remove from Home takes the shortcut off (only with the app library on, where one press adds it back);
     * App info opens the TV's page for the app, which is what holding OK used to do.
     */
    // ---- hold OK ---------------------------------------------------------------------------------
    // View's own long click misses on some remotes: an IR remote that sends its repeat frames as separate
    // presses, or a Bluetooth one whose repeats land after a tile rebind, never holds one DOWN long enough.
    // So the launcher times the hold itself: OK down on a tile, still down (or still repeating) after
    // HOLD_OK_MS opens the tile menu, and the UP that follows is eaten so it doesn't also open the app.

    private static final long HOLD_OK_MS = 550;
    private long holdOkDown = -1;
    private int holdOkPosition = -1;
    private String holdOkKey;    // the app under OK when it went down; a tile rebuild can move another app to that slot
    private boolean holdOkFired;
    private final Runnable holdOkTimer = this::fireHoldOk;

    private static boolean isOkKey(int code) {
        return code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_BUTTON_A;
    }

    /** True when the event belongs to a hold that opened the tile menu and must not reach the tile. */
    private boolean holdOk(KeyEvent event) {
        if (!tileMenuEnabled || !isOkKey(event.getKeyCode())) return false;
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() == 0) {
                holdOkFired = false;
                handler.removeCallbacks(holdOkTimer);
                holdOkPosition = reordering || openPopup != null ? -1 : focusedTilePosition();
                holdOkKey = holdOkPosition < 0 || holdOkPosition >= adapter.appCount() ? null : adapter.keyAt(holdOkPosition);
                if (holdOkKey == null) {
                    holdOkDown = -1;
                    return false;
                }
                holdOkDown = event.getDownTime();
                handler.postDelayed(holdOkTimer, HOLD_OK_MS);
                return false;
            }
            if (holdOkFired) return true;
            if (holdOkDown >= 0 && event.getEventTime() - holdOkDown >= HOLD_OK_MS) {
                fireHoldOk();
                return true;
            }
            return false;
        }
        // ACTION_UP
        handler.removeCallbacks(holdOkTimer);
        holdOkDown = -1;
        holdOkKey = null;
        if (holdOkFired) {
            holdOkFired = false;
            View f = getCurrentFocus();
            if (f != null) f.setPressed(false);
            return true;
        }
        return false;
    }

    /**
     * Stops timing a held OK when the launcher is paused, loses its window or goes away, so the menu can't open
     * behind another screen. An interrupted press still counts as handled: its UP, if it comes back here, is eaten
     * rather than opening the tile.
     */
    // ---- voice search ---------------------------------------------------------------------------------

    /** Home while it is in front, for voice search from the key service or the microphone; null otherwise. */
    private static MainActivity inFront;

    /** Home brought to the front by voice search from another app, to listen there. */
    static final String EXTRA_LISTEN = "com.muzdai.launcher.LISTEN";

    /**
     * Voice search (the microphone, or a remote button set to it): Home in front listens in place. From another app Home comes to the front and
     * listens the same way, a beep and the lit voice button, no window.
     */
    static void voiceKey(android.content.Context ctx) {
        voiceKey(ctx, InputKeyService.frontPackage());
    }

    /** onScreen: the app in front when the key (or the remote's mic, VoiceAssistActivity) was pressed. */
    static void voiceKey(android.content.Context ctx, String onScreen) {
        // What "close" closes: the app on screen when the key was pressed (Home may come forward over it).
        frontWhenAsked = onScreen == null || onScreen.equals(ctx.getPackageName()) ? null : onScreen;
        if (inFront != null && !inFront.isFinishing()) {
            inFront.openVoiceSearch();
            return;
        }
        // Over a player the player stays: listening in the background, the result looked up in it.
        if (VoiceActions.isPlayer(onScreen) && VoiceOverPlayer.listen(ctx, onScreen)) return;
        try {
            ctx.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .setClass(ctx, MainActivity.class).putExtra(EXTRA_LISTEN, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
        } catch (ActivityNotFoundException ignored) {
        }
    }

    /** The app in front when voice search was started, for "close"; null when it was Home. */
    private static String frontWhenAsked;

    /** When the last Home intent came, for Home twice (ForkKeys.doubleHome). */
    private long lastHomeIntent;
    private static final long DOUBLE_HOME_MS = 600;

    /** Set by an EXTRA_LISTEN start; onResume listens once Home is on screen. */
    private boolean listenOnResume;

    /** Listens in place: a beep and the voice button lit red, no window; again while listening stops. */
    private void openVoiceSearch() {
        if (openPopup != null) openPopup.dismiss();
        if (voice == null) voice = new VoiceInput(this, voiceCallback);
        if (voice.listening()) {
            voice.cancel();
            return;
        }
        if (!VoiceInput.permitted(this)) {
            // The panel asks for the microphone and listens itself.
            open(new Intent(this, VoiceSearchActivity.class));
            return;
        }
        if (!voice.start()) Toast.makeText(this, R.string.voice_no_recognizer, Toast.LENGTH_SHORT).show();
    }

    private VoiceInput voice;
    private final VoiceInput.Callback voiceCallback = new VoiceInput.Callback() {
        @Override public void onListening(boolean on) {
            btnVoice.setActivated(on);
            if (!on) {
                btnVoice.setScaleX(1f);
                btnVoice.setScaleY(1f);
            }
        }
        @Override public void onLevel(float level) {
            btnVoice.setScaleX(1f + level / 30f);
            btnVoice.setScaleY(1f + level / 30f);
        }
        @Override public void onResult(String text) {
            if (isFinishing() || isDestroyed()) return;
            Toast.makeText(MainActivity.this, "«" + text + "»", Toast.LENGTH_SHORT).show();
            // The AI reads the words off the main thread; the button stays lit meanwhile.
            btnVoice.setActivated(true);
            VoiceActions actions = new VoiceActions(MainActivity.this);
            String front = frontWhenAsked;
            frontWhenAsked = null;
            Context app = getApplicationContext();
            new Thread(() -> {
                // A player playing behind Home (minimised) is where the request goes when it names no app.
                actions.inApp(MediaListener.playingApp(app)).front(front);
                VoiceActions.Plan p = actions.planWithAi(text);
                runOnUiThread(() -> {
                    if (voice == null || !voice.listening()) btnVoice.setActivated(false);
                    if (isFinishing() || isDestroyed()) return;
                    if (p.kind == VoiceActions.Plan.Kind.START) {
                        open(p.intent);
                    } else if (p.kind == VoiceActions.Plan.Kind.PLAY) {
                        VoiceRun.play(MainActivity.this, p.card);
                    } else if (p.kind == VoiceActions.Plan.Kind.CLOSE) {
                        VoiceRun.close(MainActivity.this, p.pkg);
                    } else {
                        // A choice to make: where to search, or an app that is not installed.
                        startActivity(new Intent(MainActivity.this, VoiceSearchActivity.class)
                                .putExtra(VoiceSearchActivity.EXTRA_QUERY, text)
                                .putExtra(VoiceSearchActivity.EXTRA_PLAN, VoiceSearchActivity.pack(p)));
                    }
                });
            }, "voice-ai").start();
        }
        @Override public void onError(int messageRes, int code) {
            Toast.makeText(MainActivity.this, getString(messageRes, code), Toast.LENGTH_SHORT).show();
        }
    };

    private void cancelHoldOk() {
        handler.removeCallbacks(holdOkTimer);
        if (holdOkDown >= 0) holdOkFired = true;
        holdOkDown = -1;
        holdOkPosition = -1;
        holdOkKey = null;
    }

    private void fireHoldOk() {
        handler.removeCallbacks(holdOkTimer);
        if (holdOkDown < 0 || holdOkFired) return;
        holdOkFired = true;
        if (openPopup != null || reordering) return;   // View's own long click got there first
        int position = holdOkPosition;
        if (position < 0 || position >= adapter.appCount() || position != focusedTilePosition()) return;
        // Same slot is not enough: tiles may have been rebuilt during the hold, so the app must match too.
        if (!holdOkKey.equals(adapter.keyAt(position))) return;
        View card = getCurrentFocus();
        if (card != null) card.setPressed(false);
        onTileLongPress(position, card);
    }

    @Override
    public void onTileLongPress(int position, View card) {
        if (reordering || openPopup != null) return;
        String key = adapter.keyAt(position);
        String pkg = adapter.packageAt(position);
        // Empty dock slots and the Default-home tile carry the launcher's own package: no menu for them.
        if (key == null || pkg == null || pkg.equals(getPackageName())) return;
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        PopupWindow popup = glassPopup(panel, (int) (200 * density));
        // vPro, tile_menu_close: Move, Close, Remove, App info - no "Replace with another app".
        boolean forkMenu = fork != null && fork.tileMenuClose;
        if (!forkMenu && dockRow && position < adapter.movableCount()) {
            boolean inDock = position < COLUMNS;
            addSettingsMenuRow(rows, popup, inDock ? R.string.tile_replace_in_dock : R.string.tile_add_to_dock,
                    () -> startDockEditFor(key, inDock));
        }
        if (position < adapter.movableCount()) addSettingsMenuRow(rows, popup, R.string.tile_move, () -> {
            if (!beginReorder()) return;
            int at = adapter.positionOf(key);   // beginReorder can rebuild the tiles
            if (at < 0) return;
            focusTile(at);
            pickUp(at);
            Toast.makeText(this, R.string.reorder_hint, Toast.LENGTH_LONG).show();
        });
        if (forkMenu) addSettingsMenuRow(rows, popup, R.string.fork_tile_close, () -> closeApp(pkg));
        if (appLibraryEnabled) addSettingsMenuRow(rows, popup,
                forkMenu ? R.string.fork_tile_remove : R.string.library_remove_shortcut, () -> {
            if (LibraryApps.save(this, pkg, false)) reloadApps(false);
            else Toast.makeText(this, R.string.library_save_failed, Toast.LENGTH_LONG).show();
        });
        // Some firmware has no App info page at all; a row that only toasts "can't open" helps nobody.
        Intent info = SystemSettings.appInfo(this, pkg);
        if (info != null) addSettingsMenuRow(rows, popup, R.string.library_app_info, () -> open(info));
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        popup.setWidth(Math.max((int) (200 * density), panel.getMeasuredWidth()));
        // Dock tiles sit near the bottom of the screen: open the menu above the tile when it would not fit below.
        int[] at = new int[2];
        card.getLocationInWindow(at);
        int gap = (int) (10 * density), height = panel.getMeasuredHeight();
        if (at[1] + card.getHeight() + gap + height <= root.getHeight()) {
            dropIn(popup, panel, card, Gravity.START);
        } else {
            panel.setAlpha(0f);
            panel.setPivotY(height);
            panel.setScaleY(0.92f);
            panel.setTranslationY(8 * density);
            popup.showAtLocation(root, Gravity.NO_GRAVITY, at[0], Math.max(0, at[1] - gap - height));
            panel.animate().alpha(1f).scaleY(1f).translationY(0f).setDuration(220)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
        }
        rows.getChildAt(0).requestFocus();
    }

    private void addSettingsMenuRow(LinearLayout rows, PopupWindow popup, int title, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        row.findViewById(R.id.icon).setVisibility(View.GONE);
        row.findViewById(R.id.dot).setVisibility(View.GONE);
        row.findViewById(R.id.status).setVisibility(View.GONE);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        // The OK-up after opening the menu must not activate its first row.
        final boolean[] pressed = {false};
        row.setOnKeyListener((v, code, event) -> {
            if (code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) pressed[0] = true;
                return true;
            }
            boolean activate = pressed[0];
            pressed[0] = false;
            if (activate) {
                popup.dismiss();
                action.run();
            }
            return true;
        });
        rows.addView(row);
    }
}
