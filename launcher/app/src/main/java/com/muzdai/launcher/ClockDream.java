package com.muzdai.launcher;

import android.graphics.Bitmap;
import android.graphics.Outline;
import android.service.dreams.DreamService;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Screensaver: the launcher's thin clock and date on black, over a slow ambient glow (ClockScene). While Roon plays
 * (RoonNow), a large cover with the title and artist in place of the clock, moved a little every minute.
 */
public class ClockDream extends DreamService implements RoonNow.Listener {

    private static final long MOVE_MS = 60_000;

    private ClockScene scene;
    private View clock;
    private LinearLayout music;
    private ImageView cover;
    private TextView title, artist;
    private String coverKey;
    private boolean listening;
    private final java.util.Random random = new java.util.Random();
    private final Runnable move = new Runnable() {
        @Override public void run() {
            if (music == null) return;
            int range = dp(70);
            music.animate().translationX(random.nextInt(2 * range + 1) - range)
                    .translationY(random.nextInt(range + 1) - range / 2f).setDuration(3000).start();
            music.postDelayed(this, MOVE_MS);
        }
    };

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);   // any key wakes the TV back to where it was
        setFullscreen(true);
        setScreenBright(false);
        setContentView(R.layout.dream_clock);
        ClockFormat.apply(getWindow().getDecorView(), Config.load(this).clockFormat);
        scene = new ClockScene(getWindow().getDecorView());
        scene.useHomeWallpaper(this);
        clock = findViewById(R.id.dreamContent);
        if (RoonApi.configured(this)) addMusic((ViewGroup) clock.getParent());
    }

    /** The cover and its words, centred, hidden until Roon plays. */
    private void addMusic(ViewGroup root) {
        music = new LinearLayout(this);
        music.setOrientation(LinearLayout.HORIZONTAL);
        music.setGravity(Gravity.CENTER_VERTICAL);
        music.setVisibility(View.GONE);
        cover = new ImageView(this);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(16));
            }
        });
        cover.setClipToOutline(true);
        music.addView(cover, new LinearLayout.LayoutParams(dp(360), dp(360)));
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(dp(420), ViewGroup.LayoutParams.WRAP_CONTENT);
        wl.leftMargin = dp(48);
        music.addView(words, wl);
        title = new TextView(this);
        title.setTextColor(0xE6FFFFFF);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 40);
        title.setTypeface(UiFont.get(this, R.string.font_light));
        title.setMaxLines(3);
        title.setEllipsize(TextUtils.TruncateAt.END);
        words.addView(title);
        artist = new TextView(this);
        artist.setTextColor(0x99FFFFFF);
        artist.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        artist.setTypeface(UiFont.get(this, R.string.font_regular));
        artist.setMaxLines(2);
        artist.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        al.topMargin = dp(12);
        words.addView(artist, al);
        root.addView(music, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        scene.start();
        if (music != null) {
            listening = true;
            RoonNow.listen(this, this);
            music.postDelayed(move, MOVE_MS);
        }
    }

    @Override
    public void onRoon(RoonNow.Snapshot s) {
        if (music == null) return;
        boolean on = s != null && s.playing();
        music.setVisibility(on ? View.VISIBLE : View.GONE);
        clock.setVisibility(on ? View.INVISIBLE : View.VISIBLE);
        if (!on) return;
        if (!s.title.contentEquals(title.getText())) title.setText(s.title);
        if (!s.artist.contentEquals(artist.getText())) artist.setText(s.artist);
        String key = s.imageKey;
        if (key == null ? coverKey == null : key.equals(coverKey)) return;
        coverKey = key;
        String url = RoonNow.imageUrl(this, key, 720);
        new Thread(() -> {
            Bitmap b = RoonNowPlayingActivity.load(url);
            music.post(() -> {
                if (key == null ? coverKey == null : key.equals(coverKey)) cover.setImageBitmap(b);
            });
        }, "dream-cover").start();
    }

    private void stopMusic() {
        if (listening) RoonNow.unlisten(this);
        listening = false;
        if (music != null) music.removeCallbacks(move);
    }

    @Override
    public void onDreamingStopped() {
        stopMusic();
        scene.stop();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        stopMusic();
        if (scene != null) scene.stop();
        super.onDetachedFromWindow();
    }
}
