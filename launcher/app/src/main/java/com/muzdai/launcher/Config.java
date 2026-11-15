package com.muzdai.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Color;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The launcher's settings: the built-in values in res/values/config.xml and fork_defaults.xml, with the
 * owner's choices from Launcher settings on top. Wallpapers are read from the app's external files directory
 * ({@code /sdcard/Android/data/com.muzdai.launcher/files/wallpapers}).
 *
 * Read once in {@link MainActivity#onCreate}; nothing here touches the network.
 */
final class Config {
    static final String WALLPAPER_NAME = "wallpaper.jpg";
    static final String SETTINGS_NAME = "launcher_settings";
    static final String ROWS_BELOW_DOCK_KEY = "rows_below_dock";
    static final String WALLPAPER_CHOICE_KEY = "wallpaper_choice";
    static final String DOCK_OPACITY_KEY = "dock_opacity";
    static final String CLOCK_FORMAT_KEY = "clock_format";
    /**
     * Counts the changes made in Launcher settings. Home remembers the count it was built with and rebuilds on
     * resume when it differs, however the settings were left (see MainActivity#onResume).
     */
    static final String SETTINGS_REVISION_KEY = "settings_revision";

    static final String GLASS_DEFAULT = "glass", GLASS_RIM = "rim", GLASS_HALO = "halo";

    /** True when Launcher settings changed something since Home was built at revision {@code seen}. */
    static boolean settingsChanged(long seen, long current) {
        return seen != current;
    }

    final List<String> dockApps;
    /** Grid apps fill the dock slots dock_apps leaves free (config.xml, fill_dock); false leaves the slots empty. */
    final boolean fillDock;
    final List<String> hiddenApps;
    /** Packages pre-seeded into the App library's "added" set (config.xml, library_apps); empty by default. */
    final List<String> libraryApps;
    /** Packages the App library catalogue never offers, even with app_library on (config.xml, library_excluded_apps). */
    final List<String> libraryExcludedApps;
    final String liveTvPackage;
    /** Package to the action its tile sends instead of opening the start screen (config.xml, launch_actions). */
    final Map<String, String> launchActions;
    /** Package to the name its tile shows instead of the app's label (config.xml, app_labels). */
    final Map<String, String> appLabels;
    /** Package to a bundled banner_* drawable its tile shows instead of the app's art (config.xml, app_banners). */
    final Map<String, String> appBanners;
    final int columns;
    final boolean animations;
    final boolean advancedEffects;
    final int rowsBelowDock;
    /** The date on its own line under the clock instead of beside it (config.xml, date_below_clock). */
    final boolean dateBelowClock;
    /** Name after the greeting ("Good morning, Anna"); empty for the greeting alone. */
    final String greetingName;
    /** Show the Open on start row in Launcher settings (config.xml, open_on_start_choice). */
    final boolean openOnStartChoice;
    /** Seconds the boot's app waits for a validated network; 0 opens it at once (open_on_start_network_wait). */
    final int openOnStartNetworkWait;
    /** The Open on start app also opens when the screen comes back on (config.xml, open_on_start_on_wake). */
    final boolean openOnStartOnWake;
    /** The focused dock app's name in large type above the dock (config.xml, dock_title). */
    final boolean dockTitle;
    /** The dock as its own row: band, no names, parked low (config.xml dock_row). false: one plain grid. */
    final boolean dockRow;
    /** Direct launcher-settings header action; off by default (launcher_settings_button). */
    final boolean launcherSettingsButton;
    /** Names under every tile, dimmed until focused (config.xml always_show_names); the dock band grows for them. */
    final boolean alwaysShowNames;
    /** Show the Wi-Fi/Ethernet pill in the top bar (config.xml, show_network_pill). */
    final boolean showNetworkPill;
    /** Show the Inputs pill when the TV has inputs to list (config.xml, show_inputs_pill). */
    final boolean showInputsPill;
    /** Wi-Fi and Settings pills as round icon buttons, Inputs keeps its label (config.xml icon_only_buttons). */
    final boolean iconOnlyButtons;
    /** The Inputs pill as a round icon button too (config.xml icon_only_inputs). */
    final boolean iconOnlyInputs;
    /** App library as a round button in the top bar (config.xml library_button); needs app_library. */
    final boolean libraryButton;
    /** The App library pill after the last Home row; false hides it while libraryButton is on (show_library_pill). */
    final boolean showLibraryPill;
    /** With icon_only_buttons, the Wi-Fi pill keeps the network name up to this width in dp (0: icon only). */
    final int wifiLabelWidth;
    /** Decode wallpapers in 32-bit colour instead of RGB_565 (config.xml wallpaper_true_color). */
    final boolean wallpaperTrueColor;
    final boolean showHomeSettingsShortcut;
    /** App library pill after the last Home row (config.xml app_library). */
    final boolean appLibrary;
    /** OK directly toggles Home membership in the library (app_library_selection). */
    final boolean appLibrarySelection;
    /** OK opens every app in the library; Add to Home stays in the hold menu (library_ok_opens). */
    final boolean libraryOkOpens;
    /** Background colour behind everything, #RRGGBB; fork_background_color. */
    final int backgroundColor;
    /** Extra keycodes that open the input switcher in InputKeyService (config.xml input_key_keycodes). */
    final int[] inputKeycodes;
    /** Remote buttons that open an app, "keycode:package" or "KEYCODE_NAME:package" (config.xml app_keys). */
    final List<String> appKeys;
    /** Whether InputKeyService requests key filtering (config.xml); false keeps key
     *  auto-repeat working on firmware whose AccessibilityInputFilter drops repeats (TCL Android 12). */
    final boolean keyFilter;
    /** The Inputs menu lists only inputs reporting connected; the rest fold behind a More inputs row (config.xml). */
    final boolean inputsConnectedOnly;
    /** Only CEC device rows in the Inputs menu show a connection state, never a bare HDMI port (config.xml). */
    final boolean inputsCecStateOnly;
    /** "12", "24" or "system" (config.xml, or Launcher settings' Clock row); see ClockFormat. */
    final String clockFormat;
    /** Whether Home shows a wallpaper (fork_wallpaper). */
    final boolean wallpaperEnabled;
    /** Full-screen picture behind the launcher, or null for the plain colour. */
    final File wallpaper;
    /** Rotating wallpaper images, or the single wallpaper when no collection is configured. */
    final List<File> wallpapers;
    final int wallpaperRotationMinutes;
    final int wallpaperDim;
    /** The dock band's opacity in percent (30-100): below 100 the wallpaper shows through. */
    final int dockOpacity;
    /**
     * Playback order for a wallpaper collection: {@code sequential} or {@code shuffle}. Missing or
     * invalid values keep the historical shuffle so the collection rotates as before.
     */
    final String wallpaperOrder;
    /** The dock band as light frosted glass like the pills (config.xml, dock_glass); false keeps the solid band. */
    final boolean dockGlass;
    /** The dock band's padding equals the gap between its tiles (config.xml, dock_even_padding); false keeps the fixed padding. */
    final boolean dockEvenPadding;
    /** The dock band and its edge draw (dock_band); false leaves the tiles floating on the wallpaper. */
    final boolean dockBand;
    /** The Wi-Fi pill's icon green while the TV is online (config.xml, wifi_status_color); false keeps it white. */
    final boolean wifiStatusColor;
    /** With wifi_status_color, the icon orange while connected without internet (a captive portal) and red while offline (config.xml, wifi_status_warnings). */
    final boolean wifiStatusWarnings;
    /** A rotating collection's groups by time of day, as file names (config.xml, wallpaper_moods); empty rotates through all of it. */
    final Map<String, Set<String>> wallpaperMoods;
    /** The focus highlight's look (config.xml, glass_style): {@link #GLASS_DEFAULT}, {@link #GLASS_RIM} or {@link #GLASS_HALO}. */
    final String glassStyle;
    /** Show each rotating wallpaper's name from wallpapers/captions.txt (config.xml). */
    final boolean wallpaperCaptions;
    /** Hold OK on a tile opens a menu (Move, Remove from Home, App info) instead of App info (config.xml). */
    final boolean tileMenu;
    /** Apps shown as round logo buttons in the top bar, left of the network pill, and no longer as tiles (config.xml, top_bar_apps). */
    final List<String> topBarApps;
    /** The greeting above the clock (Good evening); false hides it (config.xml, show_greeting). */
    final boolean showGreeting;
    /** The date and the line between it and the clock in the clock's full white instead of dimmed (config.xml, header_white). */
    final boolean headerWhite;
    /** The clock screensaver over Home's wallpaper instead of the black glow (config.xml, clock_wallpaper). */
    final boolean clockWallpaper;
    /** How dark the clock screensaver's wallpaper is, 0 to 90 % (config.xml, clock_wallpaper_dim). */
    final int clockWallpaperDim;
    /** The clock screensaver's coloured glow; false leaves the clock on plain black, e.g. for an OLED (config.xml, clock_glow). */
    final boolean clockGlow;

    private Config(Context ctx) {
        Resources res = ctx.getResources();
        SharedPreferences choices = ctx.getSharedPreferences(SETTINGS_NAME, Context.MODE_PRIVATE);
        dockApps = list(res, R.array.dock_apps);
        fillDock = res.getBoolean(R.bool.fill_dock);
        topBarApps = list(res, R.array.top_bar_apps);
        // A top-bar app is moved there, not copied: it leaves the grid.
        List<String> hidden = new ArrayList<>(Arrays.asList(res.getStringArray(R.array.hidden_apps)));
        for (String pkg : topBarApps) if (!hidden.contains(pkg)) hidden.add(pkg);
        hiddenApps = Collections.unmodifiableList(hidden);
        libraryApps = list(res, R.array.library_apps);
        libraryExcludedApps = list(res, R.array.library_excluded_apps);
        liveTvPackage = res.getString(R.string.live_tv_package).trim();
        launchActions = Collections.unmodifiableMap(packageMap(Arrays.asList(res.getStringArray(R.array.launch_actions))));
        appLabels = Collections.unmodifiableMap(packageMap(Arrays.asList(res.getStringArray(R.array.app_labels))));
        appBanners = Collections.unmodifiableMap(packageMap(Arrays.asList(res.getStringArray(R.array.app_banners))));

        int chosenColumns = choices.getInt("columns", res.getInteger(R.integer.columns));
        columns = chosenColumns >= 4 && chosenColumns <= 8 ? chosenColumns : Math.max(1, res.getInteger(R.integer.columns));
        int chosenRows = choices.getInt(ROWS_BELOW_DOCK_KEY, res.getInteger(R.integer.rows_below_dock));
        rowsBelowDock = chosenRows >= 0 && chosenRows <= 3 ? chosenRows : Math.max(0, res.getInteger(R.integer.rows_below_dock));
        animations = choices.getBoolean("animations", res.getBoolean(R.bool.animations));
        advancedEffects = choices.getBoolean("advanced_effects", res.getBoolean(R.bool.advanced_effects));

        dateBelowClock = res.getBoolean(R.bool.date_below_clock);
        greetingName = res.getString(R.string.greeting_name).trim();
        openOnStartChoice = res.getBoolean(R.bool.open_on_start_choice);
        openOnStartNetworkWait = Math.max(0, Math.min(600, res.getInteger(R.integer.open_on_start_network_wait)));
        openOnStartOnWake = res.getBoolean(R.bool.open_on_start_on_wake);
        dockTitle = res.getBoolean(R.bool.dock_title);
        dockRow = res.getBoolean(R.bool.dock_row);
        launcherSettingsButton = res.getBoolean(R.bool.launcher_settings_button);
        alwaysShowNames = res.getBoolean(R.bool.always_show_names);
        showNetworkPill = res.getBoolean(R.bool.show_network_pill);
        showInputsPill = res.getBoolean(R.bool.show_inputs_pill);
        iconOnlyButtons = res.getBoolean(R.bool.icon_only_buttons);
        iconOnlyInputs = res.getBoolean(R.bool.icon_only_inputs);
        libraryButton = res.getBoolean(R.bool.library_button);
        showLibraryPill = res.getBoolean(R.bool.show_library_pill);
        wifiLabelWidth = res.getInteger(R.integer.wifi_label_width);
        wallpaperTrueColor = res.getBoolean(R.bool.wallpaper_true_color);
        showHomeSettingsShortcut = res.getBoolean(R.bool.show_home_settings_shortcut);
        appLibrary = res.getBoolean(R.bool.app_library);
        appLibrarySelection = res.getBoolean(R.bool.app_library_selection);
        libraryOkOpens = res.getBoolean(R.bool.library_ok_opens);
        backgroundColor = Color.parseColor(res.getString(R.string.fork_background_color));
        inputKeycodes = res.getIntArray(R.array.input_key_keycodes);
        appKeys = list(res, R.array.app_keys);
        keyFilter = res.getBoolean(R.bool.key_filter);
        inputsConnectedOnly = res.getBoolean(R.bool.inputs_connected_only);
        inputsCecStateOnly = res.getBoolean(R.bool.inputs_cec_state_only);
        clockFormat = choices.getString(CLOCK_FORMAT_KEY, res.getString(R.string.clock_format));
        dockGlass = res.getBoolean(R.bool.dock_glass);
        dockEvenPadding = res.getBoolean(R.bool.dock_even_padding);
        dockBand = res.getBoolean(R.bool.dock_band);
        wifiStatusColor = res.getBoolean(R.bool.wifi_status_color);
        wifiStatusWarnings = res.getBoolean(R.bool.wifi_status_warnings);
        wallpaperMoods = moods(res.getString(R.string.wallpaper_moods));
        glassStyle = glassStyle(res.getString(R.string.glass_style));
        wallpaperCaptions = res.getBoolean(R.bool.wallpaper_captions);
        tileMenu = res.getBoolean(R.bool.tile_menu);
        showGreeting = res.getBoolean(R.bool.show_greeting);
        headerWhite = res.getBoolean(R.bool.header_white);
        clockWallpaper = res.getBoolean(R.bool.clock_wallpaper);
        clockWallpaperDim = Math.max(0, Math.min(90, res.getInteger(R.integer.clock_wallpaper_dim)));
        clockGlow = res.getBoolean(R.bool.clock_glow);
        dockOpacity = chosenDockOpacity(choices.getInt(DOCK_OPACITY_KEY, -1), res.getInteger(R.integer.fork_dock_opacity));

        File dir = dir(ctx);
        File single = dir != null ? new File(dir, WALLPAPER_NAME) : null;
        if (single != null && !(single.isFile() && single.length() > 0)) single = null;
        boolean enabled = res.getBoolean(R.bool.fork_wallpaper);
        if (!enabled) single = null;
        wallpaperRotationMinutes = Math.max(0, Math.min(1440, res.getInteger(R.integer.fork_wallpaper_rotation_minutes)));
        wallpaperDim = Math.max(0, Math.min(90, res.getInteger(R.integer.fork_wallpaper_dim)));
        String order = res.getString(R.string.fork_wallpaper_order);
        wallpaperOrder = WallpaperRotation.validOrder(order) ? order : WallpaperRotation.ORDER_SHUFFLE;
        List<File> collection = dir == null ? Collections.emptyList() : WallpaperRotation.images(new File(dir, "wallpapers"));
        List<File> pictures = new ArrayList<>();
        if (wallpaperRotationMinutes > 0 && enabled) pictures.addAll(collection);
        if (pictures.isEmpty() && single != null) pictures.add(single);
        // An on-TV wallpaper pick: a filename from the wallpapers/ collection, or "none". Unset rotates them all.
        // "group:<id>" rotates only that group's pictures (named "<id>-..."), "group:*" all of them; see
        // ForkConfig.wallpaperGroups. A group with no pictures left falls back to the whole collection.
        String wallpaperChoice = choices.getString(WALLPAPER_CHOICE_KEY, null);
        List<File> chosen = ForkConfig.groupChoice(collection, wallpaperChoice);
        if (chosen == null) chosen = WallpaperRotation.chosen(collection, single, wallpaperChoice);
        if (chosen != null) {
            pictures.clear();
            pictures.addAll(chosen);
            single = chosen.isEmpty() ? null : chosen.get(0);
            enabled = !chosen.isEmpty();
        }
        wallpaperEnabled = enabled;
        wallpaper = single;
        wallpapers = Collections.unmodifiableList(pictures);
    }

    static Config load(Context ctx) {
        return new Config(ctx);
    }

    private static List<String> list(Resources res, int id) {
        return Collections.unmodifiableList(new ArrayList<>(Arrays.asList(res.getStringArray(id))));
    }

    /** A known glass_style, or glass (the default) for anything else. */
    static String glassStyle(String value) {
        if (value == null) return GLASS_DEFAULT;
        String v = value.trim();
        if (GLASS_RIM.equalsIgnoreCase(v)) return GLASS_RIM;
        return GLASS_HALO.equalsIgnoreCase(v) ? GLASS_HALO : GLASS_DEFAULT;
    }

    /** The app's external files folder (wallpapers/ lives here); null when external storage is unavailable. */
    static File dir(Context ctx) {
        return ctx.getExternalFilesDir(null);
    }

    /** The dock band opacity chosen in Launcher settings, or the built-in one when none (or an out-of-range one) is saved. */
    static int chosenDockOpacity(int chosen, int fallback) {
        return chosen >= 30 && chosen <= 100 ? chosen : fallback;
    }

    /** wallpaper_moods as JSON text (config.xml); empty or unreadable is no groups. */
    private static Map<String, Set<String>> moods(String text) {
        if (text.trim().isEmpty()) return Collections.emptyMap();
        try {
            JSONObject o = new JSONObject(text);
            Map<String, Set<String>> groups = new HashMap<>();
            for (String mood : WallpaperMood.ALL) {
                JSONArray names = o.optJSONArray(mood);
                if (names == null) continue;
                Set<String> group = new HashSet<>();
                for (int i = 0; i < names.length(); i++) {
                    String name = names.optString(i, "").trim();
                    if (WallpaperRotation.collectionImage(name)) group.add(name);
                }
                if (!group.isEmpty()) groups.put(mood, Collections.unmodifiableSet(group));
            }
            return Collections.unmodifiableMap(groups);
        } catch (JSONException e) {
            return Collections.emptyMap();
        }
    }

    /** "package=value" entries; an entry without both halves is skipped, and a later entry for a package wins. */
    static Map<String, String> packageMap(List<String> entries) {
        Map<String, String> out = new HashMap<>();
        for (String entry : entries) {
            int eq = entry.indexOf('=');
            if (eq <= 0) continue;
            String pkg = entry.substring(0, eq).trim(), value = entry.substring(eq + 1).trim();
            if (!pkg.isEmpty() && !value.isEmpty()) out.put(pkg, value);
        }
        return out;
    }
}
