package com.muzdai.launcher;

import android.app.SearchManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.provider.MediaStore;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What a spoken request leads to: an app or an app's search to start right away, or something to choose on the voice
 * panel (where to search, or an app that is not installed). Shared by Home, which listens in place and only opens the
 * panel when there is a choice to make, and by the panel itself.
 */
final class VoiceActions {

    static final String TV_APP = "com.tcl.tv";
    static final String YOUTUBE = "com.google.android.youtube.tv";
    static final String YOUTUBE_MUSIC = "com.google.android.youtube.tvmusic";
    static final String SPOTIFY = "com.spotify.tv.android";
    static final String ALPAC = "cc.alcopa.tv";
    static final String EMBY = "tv.emby.embyatv";
    static final String DEEZER = "deezer.android.app";
    /** Where "search in …" rows are offered for a plain search, in this order, when installed. */
    static final String[] SEARCH_APPS = {YOUTUBE, ALPAC, EMBY, SPOTIFY, DEEZER, TV_APP};

    /** The outcome of a request. */
    static final class Plan {
        enum Kind { START, CHOICES, MISSING }

        final Kind kind;
        /** START: what to start. */
        final Intent intent;
        /** CHOICES: what to look for, and a short phrase that may be an app the TV lacks (or null). */
        final String query, maybeApp;
        /** MISSING: the package (null when it has no Play Store listing) and the name to show. */
        final String pkg, name;

        private Plan(Kind kind, Intent intent, String query, String maybeApp, String pkg, String name) {
            this.kind = kind;
            this.intent = intent;
            this.query = query;
            this.maybeApp = maybeApp;
            this.pkg = pkg;
            this.name = name;
        }
    }

    private final Context ctx;
    /** Launchable apps on the TV: label to package. */
    private final Map<String, String> labels = new HashMap<>();

    VoiceActions(Context ctx) {
        this.ctx = ctx;
        PackageManager pm = ctx.getPackageManager();
        List<ResolveInfo> found = new ArrayList<>(pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0));
        found.addAll(pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0));
        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null || ctx.getPackageName().equals(ri.activityInfo.packageName)) continue;
            labels.put(String.valueOf(ri.loadLabel(pm)), ri.activityInfo.packageName);
        }
    }

    Plan plan(String text) {
        VoiceCommand c = VoiceCommand.parse(text, s -> AppNames.find(s, labels) != null);
        switch (c.kind) {
            case OPEN: {
                String pkg = AppNames.find(c.app, labels);
                return installed(pkg) ? start(openIntent(pkg)) : missing(pkg, c.app);
            }
            case IN_APP: {
                String pkg = AppNames.find(c.app, labels);
                return installed(pkg) ? start(searchIntent(pkg, c.query)) : missing(pkg, c.app);
            }
            case TV:
                if (installed(TV_APP)) return start(tvIntent(c.query));
                return new Plan(Plan.Kind.CHOICES, null, c.query, null, null, null);
            default:
                String notOnPlay = c.maybeApp == null ? null : AppNames.notOnPlay(c.maybeApp);
                if (notOnPlay != null) return new Plan(Plan.Kind.MISSING, null, null, null, null, notOnPlay);
                return new Plan(Plan.Kind.CHOICES, null, c.query, c.maybeApp, null, null);
        }
    }

    private static Plan start(Intent i) {
        return new Plan(Plan.Kind.START, i, null, null, null, null);
    }

    private static Plan missing(String pkg, String spoken) {
        String name = pkg != null && AppNames.name(pkg) != null ? AppNames.name(pkg) : capitalize(spoken);
        return new Plan(Plan.Kind.MISSING, null, null, null, pkg, name);
    }

    Intent openIntent(String pkg) {
        if (AppNames.SETTINGS.equals(pkg)) return new Intent(Settings.ACTION_SETTINGS);
        PackageManager pm = ctx.getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        return i != null ? i : pm.getLaunchIntentForPackage(pkg);
    }

    /** The app's own search with this query; an app with no way in for a query just opens. */
    Intent searchIntent(String pkg, String q) {
        String enc = Uri.encode(q);
        switch (pkg) {
            case YOUTUBE:
            case YOUTUBE_MUSIC:   // a wrapper over YouTube that ignores links
                return new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + enc))
                        .setPackage(YOUTUBE);
            case SPOTIFY:
                return new Intent(Intent.ACTION_VIEW, Uri.parse("https://open.spotify.com/search/" + enc)).setPackage(SPOTIFY);
            case ALPAC:
                return new Intent(Intent.ACTION_SEARCH).setComponent(new ComponentName(ALPAC, ALPAC + ".SearchActivity"))
                        .putExtra(SearchManager.QUERY, q);
            case EMBY:
                return new Intent(Intent.ACTION_SEARCH)
                        .setComponent(new ComponentName(EMBY, EMBY + ".startup.StartupActivity"))
                        .putExtra(SearchManager.QUERY, q);
            case TV_APP:
                return tvIntent(q);
            default:
                return openIntent(pkg);
        }
    }

    /** com.tcl.tv answers a channel name by switching to it and anything else with its programme search. */
    static Intent tvIntent(String q) {
        return new Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).setPackage(TV_APP)
                .putExtra(SearchManager.QUERY, q);
    }

    static Intent marketDetails(String pkg) {
        return new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + pkg));
    }

    static Intent marketSearch(String name) {
        return new Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=" + Uri.encode(name) + "&c=apps"));
    }

    boolean installed(String pkg) {
        if (pkg == null) return false;
        if (AppNames.SETTINGS.equals(pkg)) return true;
        return openIntent(pkg) != null;
    }

    String label(String pkg) {
        try {
            PackageManager pm = ctx.getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (PackageManager.NameNotFoundException e) {
            return AppNames.name(pkg) != null ? AppNames.name(pkg) : pkg;
        }
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return "";
        return s.substring(0, 1).toUpperCase(java.util.Locale.getDefault()) + s.substring(1);
    }
}
