package com.muzdai.launcher;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Outline;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Roon's now playing on the TV's screen: the cover over a blurred copy of itself, the title, artist and album, the
 * track's progress, and buttons for a remote without media keys (previous, play/pause, next, Roon Radio, quieter,
 * louder, with the volume on the 0–100 scale). The remote's media keys work here too. Opened from the mini player.
 */
public class RoonNowPlayingActivity extends Activity implements RoonNow.Listener {

    /** True while on screen: volume changes show here, not as a toast. */
    static volatile boolean shown;

    static void open(Context ctx) {
        ctx.startActivity(new Intent(ctx, RoonNowPlayingActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private ImageView backdrop, cover;
    private TextView title, artist, album, elapsed, length, volume;
    private ProgressBar progress;
    private ImageButton playPause;
    private RoonNow.Snapshot now;
    private String coverKey;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        backdrop = new ImageView(this);
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            backdrop.setRenderEffect(RenderEffect.createBlurEffect(dp(40), dp(40), Shader.TileMode.CLAMP));
        }
        root.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));
        View shade = new View(this);
        shade.setBackgroundColor(0xB0000000);
        root.addView(shade, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(72), dp(48), dp(72), dp(48));
        root.addView(row, new FrameLayout.LayoutParams(-1, -1));

        cover = new ImageView(this);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setBackgroundColor(0x33FFFFFF);
        cover.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(14));
            }
        });
        cover.setClipToOutline(true);
        cover.setElevation(dp(12));
        row.addView(cover, new LinearLayout.LayoutParams(dp(300), dp(300)));

        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        sl.leftMargin = dp(48);
        row.addView(side, sl);

        title = text(side, 34, 0xFFFFFFFF, R.string.font_medium, 2);
        artist = text(side, 22, 0xE6FFFFFF, R.string.font_regular, 1);
        ((LinearLayout.LayoutParams) artist.getLayoutParams()).topMargin = dp(8);
        album = text(side, 17, 0x99FFFFFF, R.string.font_regular, 1);
        ((LinearLayout.LayoutParams) album.getLayoutParams()).topMargin = dp(4);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
        progress.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0x40FFFFFF));
        progress.setFocusable(false);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-1, dp(6));
        pl.topMargin = dp(36);
        side.addView(progress, pl);
        LinearLayout times = new LinearLayout(this);
        side.addView(times, new LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT));
        elapsed = new TextView(this);
        length = new TextView(this);
        for (TextView t : new TextView[]{elapsed, length}) {
            t.setTextColor(0x99FFFFFF);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        }
        times.addView(elapsed, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        length.setGravity(Gravity.END);
        times.addView(length, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        bl.topMargin = dp(28);
        side.addView(buttons, bl);
        button(buttons, R.drawable.ic_rn_prev, R.string.roon_np_previous, 48, () -> send(RoonCommands.Kind.PREVIOUS));
        playPause = button(buttons, R.drawable.ic_rn_play, R.string.roon_np_play, 64, this::playOrPause);
        button(buttons, R.drawable.ic_rn_next, R.string.roon_np_next, 48, () -> send(RoonCommands.Kind.NEXT));
        button(buttons, R.drawable.ic_rn_radio, R.string.roon_np_radio, 48, () -> send(RoonCommands.Kind.RADIO));
        View gap = new View(this);
        buttons.addView(gap, new LinearLayout.LayoutParams(dp(18), 1));
        button(buttons, R.drawable.ic_rn_vol_down, R.string.roon_np_quieter, 48, () -> send(RoonCommands.Kind.QUIETER));
        volume = new TextView(this);
        volume.setTextColor(0xFFFFFFFF);
        volume.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        volume.setGravity(Gravity.CENTER);
        volume.setMinWidth(dp(44));
        volume.setPadding(0, 0, dp(8), 0);
        buttons.addView(volume, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        button(buttons, R.drawable.ic_rn_vol_up, R.string.roon_np_louder, 48, () -> send(RoonCommands.Kind.LOUDER));

        setContentView(root);
        playPause.requestFocus();
        show(RoonNow.last());
    }

    private TextView text(LinearLayout parent, int sp, int color, int font, int lines) {
        TextView t = new TextView(this);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(UiFont.get(this, font));
        t.setMaxLines(lines);
        t.setEllipsize(TextUtils.TruncateAt.END);
        parent.addView(t, new LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT));
        return t;
    }

    private ImageButton button(LinearLayout parent, int icon, int label, int size, Runnable action) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(icon);
        b.setImageTintList(android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
        b.setBackgroundResource(R.drawable.mini_button_bg);
        b.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int pad = dp(size * 0.22f);
        b.setPadding(pad, pad, pad, pad);
        b.setContentDescription(getString(label));
        b.setFocusable(true);
        b.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(size), dp(size));
        lp.rightMargin = dp(8);
        parent.addView(b, lp);
        return b;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onStart() {
        super.onStart();
        shown = true;
        RoonNow.listen(this, this);
    }

    @Override
    protected void onStop() {
        shown = false;
        RoonNow.unlisten(this);
        super.onStop();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (event.getRepeatCount() == 0) {
                    if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY) send(RoonCommands.Kind.PLAY);
                    else if (keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE) send(RoonCommands.Kind.PAUSE);
                    else playOrPause();
                }
                return true;
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                if (event.getRepeatCount() == 0) send(RoonCommands.Kind.NEXT);
                return true;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                if (event.getRepeatCount() == 0) send(RoonCommands.Kind.PREVIOUS);
                return true;
            case KeyEvent.KEYCODE_MEDIA_STOP:
                send(RoonCommands.Kind.STOP);
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    private void playOrPause() {
        send(now != null && now.playing() ? RoonCommands.Kind.PAUSE : RoonCommands.Kind.PLAY);
    }

    private void send(RoonCommands.Kind kind) {
        RoonPlay.button(this, kind);
        if (kind == RoonCommands.Kind.PAUSE && now != null) {   // show it at once; the next reading confirms
            now.state = "paused";
            show(now);
        }
        RoonNow.soon();
    }

    @Override
    public void onRoon(RoonNow.Snapshot s) {
        show(s);
    }

    private void show(RoonNow.Snapshot s) {
        now = s;
        if (s == null || !s.active()) {
            title.setText(s == null ? R.string.roon_unreachable : R.string.roon_plays_nothing);
            artist.setText("");
            album.setText("");
            progress.setProgress(0);
            elapsed.setText("");
            length.setText("");
        } else {
            set(title, s.title);
            set(artist, s.artist);
            set(album, s.album);
            progress.setProgress(s.length > 0 ? (int) Math.min(1000, s.seek * 1000 / s.length) : 0);
            elapsed.setText(RoonNow.time(s.seek));
            length.setText(s.length > 0 ? RoonNow.time(s.length) : "");
        }
        boolean playing = s != null && s.playing();
        playPause.setImageResource(playing ? R.drawable.ic_rn_pause : R.drawable.ic_rn_play);
        playPause.setContentDescription(getString(playing ? R.string.roon_np_pause : R.string.roon_np_play));
        volume.setText(s == null || s.volume == null ? "" : RoonCommands.say(Math.floor(RoonCommands.toScale(
                s.volume.optDouble("value"), s.volume.optDouble("min"), s.volume.optDouble("max")) * 2) / 2));
        loadCover(s == null ? null : s.imageKey);
    }

    private static void set(TextView t, String v) {
        if (!v.contentEquals(t.getText())) t.setText(v);
    }

    private void loadCover(String key) {
        if (key == null ? coverKey == null : key.equals(coverKey)) return;
        coverKey = key;
        if (key == null) {
            cover.setImageDrawable(null);
            backdrop.setImageDrawable(null);
            return;
        }
        String url = RoonNow.imageUrl(this, key, 720);
        new Thread(() -> {
            Bitmap b = load(url);
            runOnUiThread(() -> {
                if (isDestroyed() || !key.equals(coverKey)) return;
                cover.setImageBitmap(b);
                backdrop.setImageBitmap(b);
            });
        }, "roon-cover").start();
    }

    static Bitmap load(String url) {
        if (url == null) return null;
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(4000);
            c.setReadTimeout(8000);
            try (InputStream in = c.getInputStream()) {
                return BitmapFactory.decodeStream(in);
            } finally {
                c.disconnect();
            }
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }
}
