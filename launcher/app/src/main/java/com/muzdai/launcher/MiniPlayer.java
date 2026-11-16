package com.muzdai.launcher;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Home's mini player: while an app plays music behind Home, a slab above the top bar's round buttons, as tall as they
 * are and spanning the first to the last, with the same round ends. Inside: the cover as a turning record (when the
 * track has one), "Artist — Title" as a marquee, and two buttons: back to the app's full screen, and stop and close the
 * app. Sessions come from MediaSessionManager through MediaListener; com.tcl.tv's session (always "playing" for the
 * Assistant) is left out.
 */
final class MiniPlayer {
    private static final long SPIN_MS = 9000;

    private final MainActivity act;
    private final FrameLayout root;
    private final ViewGroup bar;
    private final float density;
    private final Handler main = new Handler(Looper.getMainLooper());

    private final LinearLayout slab;
    private final DiscView disc;
    private final TextView text;
    private final ImageButton expand, close;
    private final ObjectAnimator spin;

    private MediaSessionManager sessions;
    private final List<MediaController> watched = new ArrayList<>();
    private MediaController current;
    private String artKey;
    private boolean running;

    private final MediaSessionManager.OnActiveSessionsChangedListener changed = list -> watch(list);
    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            refresh();
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            refresh();
        }

        @Override
        public void onSessionDestroyed() {
            refresh();
        }
    };
    private final View.OnLayoutChangeListener barMoved = (v, l, t, r, b, ol, ot, or, ob) -> place();

    MiniPlayer(MainActivity act, FrameLayout root, ViewGroup bar) {
        this.act = act;
        this.root = root;
        this.bar = bar;
        density = act.getResources().getDisplayMetrics().density;

        slab = new LinearLayout(act);
        slab.setOrientation(LinearLayout.HORIZONTAL);
        slab.setGravity(Gravity.CENTER_VERTICAL);
        slab.setBackgroundResource(R.drawable.mini_player_bg);
        slab.setVisibility(View.GONE);
        slab.setClipChildren(false);

        disc = new DiscView(act);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(34), dp(34));
        dl.leftMargin = dp(4);
        slab.addView(disc, dl);

        text = new TextView(act);
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        text.setMarqueeRepeatLimit(-1);
        text.setHorizontalFadingEdgeEnabled(true);
        text.setTextColor(0xFFFFFFFF);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        text.setTypeface(UiFont.get(act, R.string.font_medium));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tl.leftMargin = dp(10);
        tl.rightMargin = dp(6);
        slab.addView(text, tl);

        expand = button(R.drawable.ic_mp_expand, R.string.mini_player_expand);
        close = button(R.drawable.ic_mp_close, R.string.mini_player_close);
        ((LinearLayout.LayoutParams) close.getLayoutParams()).rightMargin = dp(4);
        expand.setOnClickListener(v -> restore());
        close.setOnClickListener(v -> stopAndClose());
        // Down goes back to the round buttons below; the slab's ends keep focus.
        View.OnKeyListener keys = (v, keyCode, e) -> {
            if (e.getAction() != android.view.KeyEvent.ACTION_DOWN) return false;
            switch (keyCode) {
                case android.view.KeyEvent.KEYCODE_DPAD_UP:
                    return true;
                case android.view.KeyEvent.KEYCODE_DPAD_LEFT:
                    return v == expand;
                case android.view.KeyEvent.KEYCODE_DPAD_RIGHT:
                    return v == close;
                case android.view.KeyEvent.KEYCODE_DPAD_DOWN: {
                    View below = lastButton();
                    if (below != null) below.requestFocus();
                    return true;
                }
                default:
                    return false;
            }
        };
        expand.setOnKeyListener(keys);
        close.setOnKeyListener(keys);

        root.addView(slab, new FrameLayout.LayoutParams(0, dp(42)));
        spin = ObjectAnimator.ofFloat(disc, View.ROTATION, 0f, 360f);
        spin.setDuration(SPIN_MS);
        spin.setRepeatCount(ValueAnimator.INFINITE);
        spin.setInterpolator(new LinearInterpolator());
    }

    private ImageButton button(int icon, int label) {
        ImageButton b = new ImageButton(act);
        b.setImageResource(icon);
        // pill_text's colours: dark on the white of focus, white otherwise.
        b.setImageTintList(new android.content.res.ColorStateList(
                new int[][]{{android.R.attr.state_focused}, {}}, new int[]{0xFF111111, 0xFFFFFFFF}));
        b.setBackgroundResource(R.drawable.mini_button_bg);
        b.setContentDescription(act.getString(label));
        b.setFocusable(true);
        b.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(34), dp(34));
        lp.leftMargin = dp(4);
        slab.addView(b, lp);
        return b;
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    // ---- lifecycle -------------------------------------------------------------------------------------

    void start() {
        if (running) return;
        running = true;
        bar.addOnLayoutChangeListener(barMoved);
        new Thread(() -> {
            boolean on = MediaListener.ensureEnabled(act.getApplicationContext());
            main.post(() -> {
                if (!running || !on) return;
                sessions = act.getSystemService(MediaSessionManager.class);
                try {
                    sessions.addOnActiveSessionsChangedListener(changed, MediaListener.component(act), main);
                    watch(sessions.getActiveSessions(MediaListener.component(act)));
                } catch (SecurityException e) {
                    sessions = null;   // the listener is not on (yet)
                }
            });
        }, "mini-player").start();
    }

    void stop() {
        running = false;
        bar.removeOnLayoutChangeListener(barMoved);
        if (sessions != null) sessions.removeOnActiveSessionsChangedListener(changed);
        sessions = null;
        for (MediaController c : watched) c.unregisterCallback(callback);
        watched.clear();
        current = null;
        spin.cancel();
        slab.setVisibility(View.GONE);
    }

    boolean isShown() {
        return slab.getVisibility() == View.VISIBLE;
    }

    /** Up from the round buttons lands here. */
    boolean focus() {
        return isShown() && expand.requestFocus();
    }

    private void watch(List<MediaController> list) {
        for (MediaController c : watched) c.unregisterCallback(callback);
        watched.clear();
        if (list != null) {
            for (MediaController c : list) {
                if (VoiceActions.TV_APP.equals(c.getPackageName())) continue;
                c.registerCallback(callback, main);
                watched.add(c);
            }
        }
        refresh();
    }

    // ---- what is playing -------------------------------------------------------------------------------

    private void refresh() {
        if (!running) return;
        MediaController playing = null;
        for (MediaController c : watched) {
            PlaybackState s = c.getPlaybackState();
            if (s != null && s.getState() == PlaybackState.STATE_PLAYING && title(c) != null) {
                playing = c;
                break;
            }
        }
        current = playing;
        if (playing == null) {
            if (slab.hasFocus()) {
                View below = lastButton();
                if (below != null) below.requestFocus();
            }
            spin.cancel();
            slab.setVisibility(View.GONE);
            return;
        }
        MediaMetadata m = playing.getMetadata();
        String artist = m == null ? null : first(m, MediaMetadata.METADATA_KEY_ARTIST,
                MediaMetadata.METADATA_KEY_ALBUM_ARTIST, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
        String line = TextUtils.isEmpty(artist) ? title(playing) : artist + "  —  " + title(playing);
        if (!line.contentEquals(text.getText())) text.setText(line);
        text.setSelected(true);   // the marquee runs only while selected
        showArt(playing.getPackageName(), m);
        if (slab.getVisibility() != View.VISIBLE) {
            slab.setVisibility(View.VISIBLE);
            place();
        }
    }

    private static String title(MediaController c) {
        MediaMetadata m = c.getMetadata();
        return m == null ? null : first(m, MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
    }

    private static String first(MediaMetadata m, String... keys) {
        for (String k : keys) {
            CharSequence v = m.getText(k);
            if (v != null && v.toString().trim().length() > 0) return v.toString().trim();
        }
        return null;
    }

    /** The cover as a turning record, or no record when the track has no cover. */
    private void showArt(String pkg, MediaMetadata m) {
        Bitmap b = null;
        String uri = null;
        if (m != null) {
            b = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (b == null) b = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (b == null) b = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            if (b == null) {
                uri = first(m, MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_ART_URI,
                        MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI);
            }
        }
        String key = b != null ? "bmp:" + System.identityHashCode(b) : uri != null ? uri : "";
        if (key.equals(artKey)) return;
        artKey = key;
        if (b != null) {
            setArt(b);
        } else if (uri != null) {
            setArt(null);
            String u = uri;
            new Thread(() -> {
                Bitmap loaded = load(u);
                main.post(() -> {
                    if (u.equals(artKey)) setArt(loaded);
                });
            }, "mini-player-art").start();
        } else {
            setArt(null);
        }
    }

    private void setArt(Bitmap b) {
        disc.setBitmap(b);
        disc.setVisibility(b != null ? View.VISIBLE : View.GONE);
        if (b != null && !spin.isStarted()) spin.start();
        if (b == null) spin.cancel();
    }

    private Bitmap load(String uri) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        try {
            if (uri.startsWith("http")) {
                HttpURLConnection c = (HttpURLConnection) new URL(uri).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(10000);
                try (InputStream in = c.getInputStream()) {
                    return BitmapFactory.decodeStream(in, null, o);
                } finally {
                    c.disconnect();
                }
            }
            try (InputStream in = act.getContentResolver().openInputStream(Uri.parse(uri))) {
                return in == null ? null : BitmapFactory.decodeStream(in, null, o);
            }
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    // ---- layout ----------------------------------------------------------------------------------------

    /** Above the round buttons: their height, from the first to the last, a small gap over them. */
    private void place() {
        View first = firstButton(), last = lastButton();
        if (first == null || last == null || slab.getVisibility() != View.VISIBLE) return;
        int[] r = new int[2], a = new int[2], b = new int[2];
        root.getLocationInWindow(r);
        first.getLocationInWindow(a);
        last.getLocationInWindow(b);
        // getLocationInWindow includes a focus scale; the layout box is what the slab lines up with.
        int left = a[0] - r[0] - Math.round((first.getWidth() * first.getScaleX() - first.getWidth()) / 2f);
        int right = b[0] - r[0] + Math.round(last.getWidth() * last.getScaleX()
                - (last.getWidth() * last.getScaleX() - last.getWidth()) / 2f);
        int height = first.getHeight();
        int top = a[1] - r[1] - Math.round((first.getHeight() * first.getScaleY() - first.getHeight()) / 2f)
                - height - dp(10);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) slab.getLayoutParams();
        if (lp.leftMargin == left && lp.topMargin == Math.max(0, top) && lp.width == right - left && lp.height == height) {
            return;
        }
        lp.leftMargin = left;
        lp.topMargin = Math.max(0, top);
        lp.width = right - left;
        lp.height = height;
        slab.setLayoutParams(lp);
    }

    private View firstButton() {
        for (int i = 0; i < bar.getChildCount(); i++) {
            if (bar.getChildAt(i).getVisibility() == View.VISIBLE) return bar.getChildAt(i);
        }
        return null;
    }

    private View lastButton() {
        for (int i = bar.getChildCount() - 1; i >= 0; i--) {
            if (bar.getChildAt(i).getVisibility() == View.VISIBLE) return bar.getChildAt(i);
        }
        return null;
    }

    // ---- buttons ---------------------------------------------------------------------------------------

    /** The app's own screen, from its session; Qobuz then goes on to its full screen player. */
    private void restore() {
        MediaController c = current;
        if (c == null) return;
        String pkg = c.getPackageName();
        boolean opened = false;
        PendingIntent pi = c.getSessionActivity();
        if (pi != null) {
            try {
                // Android 14 starts the app's activity from a PendingIntent only when the sender (Home, in front)
                // lends it its right to start activities.
                android.os.Bundle options = null;
                if (android.os.Build.VERSION.SDK_INT >= 34) {
                    options = android.app.ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(
                            android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle();
                }
                pi.send(act, 0, null, null, null, null, options);
                opened = true;
            } catch (PendingIntent.CanceledException ignored) {
            }
        }
        if (!opened) {
            Intent launch = act.getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
            if (launch == null) launch = act.getPackageManager().getLaunchIntentForPackage(pkg);
            if (launch == null) return;
            try {
                act.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException e) {
                return;
            }
        }
        if (VoiceActions.QOBUZ.equals(pkg)) InputKeyService.press(pkg, InputKeyService.BY_ID + "player-card", 8000);
    }

    /** Stops the music, then closes the app through the TV's own ADB (as the tile menu's Close does). */
    private void stopAndClose() {
        MediaController c = current;
        if (c == null) return;
        String pkg = c.getPackageName();
        try {
            c.getTransportControls().pause();
            c.getTransportControls().stop();
        } catch (RuntimeException ignored) {
        }
        View below = lastButton();
        if (below != null) below.requestFocus();
        current = null;
        spin.cancel();
        slab.setVisibility(View.GONE);
        Context app = act.getApplicationContext();
        new Thread(() -> LocalAdb.forceStop(app, pkg), "mini-player-close").start();
    }
}
