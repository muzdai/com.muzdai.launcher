package com.muzdai.launcher;

import android.app.SearchManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.provider.MediaStore;
import android.provider.Settings;

import java.io.IOException;
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
    static final String QOBUZ = "com.qobuz.music";
    /** Where "search in …" rows are offered for a plain search, in this order, when installed. */
    static final String[] SEARCH_APPS = {YOUTUBE, ALPAC, EMBY, SPOTIFY, DEEZER, TV_APP};
    /** The music apps offered as the default one in settings, when installed. */
    static final String[] MUSIC_APPS = {YOUTUBE, YOUTUBE_MUSIC, SPOTIFY, DEEZER, QOBUZ};
    /** Settings (VoiceAi.PREFS): where music goes when no app is named. */
    static final String KEY_MUSIC = "music_app";

    /** The outcome of a request. */
    static final class Plan {
        enum Kind { START, PLAY, CARDS, CHOICES, MISSING }

        final Kind kind;
        /** START: what to start. */
        final Intent intent;
        /** PLAY: the content to open and start (VoiceRun). */
        ContentSearch.Card card;
        /** CARDS: what was found, grouped by source in order; query holds the words. */
        List<ContentSearch.Card> cards;
        /** CHOICES: what to look for, and a short phrase that may be an app the TV lacks (or null). */
        final String query, maybeApp;
        /** MISSING: the package (null when it has no Play Store listing) and the name to show. */
        final String pkg, name;

        static Plan of(Kind kind, Intent intent, String query, String maybeApp, String pkg, String name) {
            return new Plan(kind, intent, query, maybeApp, pkg, name);
        }

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

    /**
     * The plan for a request, read by the AI when it is on and has a key; the plain rules otherwise, or when the AI
     * fails (no network, a bad key, no answer in time). Blocking: off the main thread only.
     */
    Plan planWithAi(String text) {
        if (VoiceAi.enabled(ctx)) {
            SharedPreferences p = VoiceAi.prefs(ctx);
            try {
                String key = VoiceAi.key(ctx);
                String model = p.getString(VoiceAi.KEY_MODEL, null);
                if (model == null) {
                    model = VoiceAi.check(key);
                    p.edit().putString(VoiceAi.KEY_MODEL, model).apply();
                }
                Plan ai = fromAnswer(VoiceAi.understand(key, model, text, labels, musicApp()), text);
                if (ai != null) return ai;
            } catch (IOException e) {
                // A model that went away (404): pick another on the next request.
                if (String.valueOf(e.getMessage()).contains("404")) p.edit().remove(VoiceAi.KEY_MODEL).apply();
            }
        }
        return plan(text);
    }

    /** The music app chosen in settings, if still installed; else the first installed of MUSIC_APPS. */
    String musicApp() {
        String chosen = VoiceAi.prefs(ctx).getString(KEY_MUSIC, null);
        if (installed(chosen)) return chosen;
        for (String pkg : MUSIC_APPS) if (installed(pkg)) return pkg;
        return YOUTUBE;
    }

    /** A plan from the AI's answer, or null when it is of no use (the rules then decide). */
    Plan fromAnswer(VoiceAi.Answer a, String spoken) {
        String q = a.query.isEmpty() ? spoken : a.query;
        switch (a.action) {
            case "tv":
                return installed(TV_APP) ? start(tvIntent(q)) : new Plan(Plan.Kind.CHOICES, null, q, null, null, null);
            case "open":
                if (installed(a.app)) return start(openIntent(a.app));
                if (!a.name.isEmpty()) {
                    String notOnPlay = AppNames.notOnPlay(a.name);
                    if (notOnPlay != null) return new Plan(Plan.Kind.MISSING, null, null, null, null, notOnPlay);
                    String pkg = !a.app.isEmpty() ? a.app : a.guess.isEmpty() ? null : a.guess;
                    String name = pkg != null && AppNames.name(pkg) != null ? AppNames.name(pkg) : a.name;
                    return new Plan(Plan.Kind.MISSING, null, null, null, pkg, name);
                }
                return null;
            default: {   // play, search
                if (!a.app.isEmpty() && !installed(a.app) && AppNames.name(a.app) != null) {
                    return missing(a.app, AppNames.name(a.app));
                }
                String app = installed(a.app) ? a.app : "";
                if ("play".equals(a.action)) {
                    ContentSearch.Card c = first(app, a.kind, q);
                    if (c != null) return play(c);
                } else {
                    List<ContentSearch.Card> found = cards(app, a.kind, q);
                    if (!found.isEmpty()) {
                        Plan p = new Plan(Plan.Kind.CARDS, null, q, null, null, null);
                        p.cards = found;
                        return p;
                    }
                }
                if (!app.isEmpty()) return start(searchIntent(app, q));
                return new Plan(Plan.Kind.CHOICES, null, q, null, null, null);
            }
        }
    }

    private static Plan play(ContentSearch.Card c) {
        Plan p = new Plan(Plan.Kind.PLAY, null, null, null, null, null);
        p.card = c;
        return p;
    }

    /**
     * The one thing "play X" means: in the named app, or, with none named, films and series from the owner's Emby
     * first and ALPAC after it, music in the default music app, the rest on YouTube. Null when nothing is found: the
     * app's own search then opens. Qobuz is searched inside the app itself (ContentSearch.qobuz).
     */
    private ContentSearch.Card first(String app, String kind, String q) {
        if (app.isEmpty()) {
            if ("movie".equals(kind) || "series".equals(kind)) {
                ContentSearch.Card c = one(EMBY, q);
                if (c != null) return c;
                // The library may name it in another language: find it on TMDB and look it up in Emby by that id.
                ContentSearch.Card tmdb = one(ALPAC, q);
                ContentSearch.Card owned = tmdb == null ? null : embyByTmdb(ContentSearch.tmdbId(tmdb));
                return owned != null ? owned : tmdb;
            }
            app = isMusic(kind) ? musicApp() : YOUTUBE;
        }
        if ("album".equals(kind)) {
            ContentSearch.Card a = album(app, q);
            if (a != null) return a;
        }
        return one(app, q);
    }

    /** Cards for "find X": the named app's, or by kind from the sources that fit, each source's best few. */
    private List<ContentSearch.Card> cards(String app, String kind, String q) {
        String[] sources;
        if (!app.isEmpty()) sources = new String[]{app};
        else if (isMusic(kind)) sources = new String[]{musicApp(), SPOTIFY, DEEZER, YOUTUBE};
        else if ("movie".equals(kind) || "series".equals(kind)) sources = new String[]{EMBY, ALPAC, YOUTUBE};
        else if ("video".equals(kind)) sources = new String[]{YOUTUBE};
        else sources = new String[]{YOUTUBE, EMBY, ALPAC};
        List<ContentSearch.Card> out = new ArrayList<>();
        List<String> done = new ArrayList<>();
        for (String s : sources) {
            String src = YOUTUBE_MUSIC.equals(s) ? YOUTUBE : s;
            if (done.contains(src)) continue;
            done.add(src);
            out.addAll(search(src, q, 8));
        }
        return out;
    }

    private static boolean isMusic(String kind) {
        return "music".equals(kind) || "album".equals(kind);
    }

    /** A whole album, started from its first track: Qobuz, Spotify and Deezer; null elsewhere or when not found. */
    private ContentSearch.Card album(String app, String q) {
        try {
            VoiceKeys keys = VoiceKeys.load(ctx);
            List<ContentSearch.Card> r = null;
            if (QOBUZ.equals(app) && installed(QOBUZ)) return ContentSearch.qobuz(q, true);
            if (SPOTIFY.equals(app) && installed(SPOTIFY) && keys.has(VoiceKeys.SPOTIFY_ID, VoiceKeys.SPOTIFY_SECRET)) {
                r = ContentSearch.spotifyAlbums(keys.get(VoiceKeys.SPOTIFY_ID), keys.get(VoiceKeys.SPOTIFY_SECRET), q, 1);
            } else if (DEEZER.equals(app) && installed(DEEZER)) {
                r = ContentSearch.deezerAlbums(q, 1);
            }
            return r == null || r.isEmpty() ? null : r.get(0);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private ContentSearch.Card embyByTmdb(String tmdbId) {
        VoiceKeys keys = VoiceKeys.load(ctx);
        if (tmdbId == null || !installed(EMBY) || !keys.has(VoiceKeys.EMBY_URL, VoiceKeys.EMBY_KEY)) return null;
        try {
            List<ContentSearch.Card> r = ContentSearch.embyByTmdb(keys.get(VoiceKeys.EMBY_URL), keys.get(VoiceKeys.EMBY_KEY), tmdbId);
            return r.isEmpty() ? null : r.get(0);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private ContentSearch.Card one(String app, String q) {
        if (QOBUZ.equals(app)) return installed(QOBUZ) ? ContentSearch.qobuz(q, false) : null;
        List<ContentSearch.Card> r = search(app, q, 1);
        return r.isEmpty() ? null : r.get(0);
    }

    /** One source's results; none when it is not installed, has no keys, or fails. */
    List<ContentSearch.Card> search(String app, String q, int max) {
        try {
            VoiceKeys keys = VoiceKeys.load(ctx);
            switch (app) {
                case YOUTUBE:
                case YOUTUBE_MUSIC:
                    if (installed(YOUTUBE)) return ContentSearch.youtube(q, max);
                    break;
                case SPOTIFY:
                    if (installed(SPOTIFY) && keys.has(VoiceKeys.SPOTIFY_ID, VoiceKeys.SPOTIFY_SECRET)) {
                        return ContentSearch.spotify(keys.get(VoiceKeys.SPOTIFY_ID), keys.get(VoiceKeys.SPOTIFY_SECRET), q, max);
                    }
                    break;
                case DEEZER:
                    if (installed(DEEZER)) return ContentSearch.deezer(q, max);
                    break;
                case EMBY:
                    if (installed(EMBY) && keys.has(VoiceKeys.EMBY_URL, VoiceKeys.EMBY_KEY)) {
                        return ContentSearch.emby(keys.get(VoiceKeys.EMBY_URL), keys.get(VoiceKeys.EMBY_KEY), q, max);
                    }
                    break;
                case ALPAC:
                    if (installed(ALPAC) && keys.has(VoiceKeys.TMDB_KEY)) {
                        return ContentSearch.tmdb(keys.get(VoiceKeys.TMDB_KEY), q, max);
                    }
                    break;
                default:
                    break;
            }
        } catch (IOException | RuntimeException ignored) {
            // this source is out; the others still count
        }
        return new ArrayList<>();
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
