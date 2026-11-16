package com.muzdai.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * The launcher's own settings, laid out like the TV's settings: a list of categories on the left, a preview of
 * the focused one on the right. OK or Right opens a category, Back or Left goes up a level, Left and Right change
 * a slider. Choices are saved at once.
 */
public final class LauncherSettingsActivity extends Activity {
    static final String EXTRA_REORDER = "com.muzdai.launcher.extra.REORDER_FROM_SETTINGS";

    // Google TV settings palette (sampled from the TV's own Settings).
    private static final int BG = 0xFF1E232C, CARD = 0xFFE8EAED, TEXT = 0xFFE8EAED, TEXT_DIM = 0xFF9AA0A6,
            TEXT_ON_CARD = 0xFF202124, SUB_ON_CARD = 0xFF5F6368, CHIP = 0xFF263041, CHIP_ON_CARD = 0xFFD3E0F5,
            BLUE = 0xFF1A73E8, LIGHT_BLUE = 0xFF8AB4F8, TRACK = 0xFF5F6368;

    private static final String[] CLOCK_FORMATS = {"system", "12", "24"};

    private SharedPreferences choices;
    private Config config;
    private ForkConfig fork;

    private LinearLayout list, preview;
    private TextView pageTitle, previewTitle;
    private ScrollView listScroll;

    /** The open pages, root first; each rebuilds its rows from the saved state when shown. */
    private final List<Supplier<Page>> stack = new ArrayList<>();
    private final List<Integer> focusStack = new ArrayList<>();
    private Page page;
    private List<String[]> tvApps;

    // ---- model ------------------------------------------------------------------------------------------

    private enum Kind { HEADER, NAV, ACTION, TOGGLE, SLIDER, RADIO, INFO }

    private static final class Page {
        final String title;
        final List<Item> items = new ArrayList<>();
        Page(String title) { this.title = title; }
        Page add(Item item) { if (item != null) items.add(item); return this; }
    }

    private static final class Item {
        Kind kind;
        int icon;
        String title, description;
        Supplier<String> summary;
        Supplier<Page> target;
        Runnable action;
        BooleanSupplier on, enabled;
        java.util.function.Consumer<Boolean> setOn;
        int min, max, step;
        IntSupplier value;
        IntConsumer setValue;
        IntFunction<String> format;
    }

    private Item item(Kind kind, int icon, String title) {
        Item i = new Item();
        i.kind = kind;
        i.icon = icon;
        i.title = title;
        return i;
    }

    private Item header(int title) { return item(Kind.HEADER, 0, getString(title)); }

    private Item nav(int icon, String title, Supplier<String> summary, Supplier<Page> target) {
        Item i = item(Kind.NAV, icon, title);
        i.summary = summary;
        i.target = target;
        return i;
    }

    private Item action(int icon, String title, Supplier<String> summary, Runnable action) {
        Item i = item(Kind.ACTION, icon, title);
        i.summary = summary;
        i.action = action;
        return i;
    }

    private Item info(String title, String summary) {
        Item i = item(Kind.INFO, 0, title);
        i.summary = () -> summary;
        return i;
    }

    private Item toggle(String title, String description, BooleanSupplier on, java.util.function.Consumer<Boolean> set) {
        Item i = item(Kind.TOGGLE, 0, title);
        i.description = description;
        i.on = on;
        i.setOn = set;
        return i;
    }

    private Item slider(String title, String description, int min, int max, int step, IntSupplier value,
                        IntConsumer set, IntFunction<String> format) {
        Item i = item(Kind.SLIDER, 0, title);
        i.description = description == null ? getString(R.string.s_slider_hint)
                : description + "\n\n" + getString(R.string.s_slider_hint);
        i.min = min;
        i.max = max;
        i.step = step;
        i.value = value;
        i.setValue = set;
        i.format = format;
        i.summary = () -> format.apply(value.getAsInt());
        return i;
    }

    /** A row whose page is a list of radio options; picking one saves it and goes back. */
    private Item choice(int icon, String title, Supplier<String[]> labels, IntSupplier current, IntConsumer pick) {
        Item i = nav(icon, title, () -> {
            String[] l = labels.get();
            int c = current.getAsInt();
            return c >= 0 && c < l.length ? l[c] : "";
        }, null);
        i.target = () -> {
            Page p = new Page(title);
            String[] l = labels.get();
            int c = current.getAsInt();
            for (int k = 0; k < l.length; k++) {
                final int index = k;
                Item r = item(Kind.RADIO, 0, l[k]);
                r.on = () -> index == c;
                r.action = () -> {
                    pick.accept(index);
                    back();
                };
                p.add(r);
            }
            return p;
        };
        return i;
    }

    // ---- activity ---------------------------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        config = Config.load(this);
        fork = ForkConfig.load(this);
        setContentView(buildFrame());
        stack.add(this::rootPage);
        focusStack.add(-1);
        show(-1);
    }

    private View buildFrame() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(BG);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setPadding(dp(100), dp(56), dp(20), 0);
        pageTitle = text(32, TEXT, false);   // the TV's own settings: 32sp title, 14sp rows, 12sp summaries
        pageTitle.setSingleLine(true);
        pageTitle.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, -2);
        tp.setMarginStart(dp(29));
        tp.bottomMargin = dp(20);
        left.addView(pageTitle, tp);
        listScroll = new ScrollView(this);
        listScroll.setVerticalScrollBarEnabled(false);
        listScroll.setClipToPadding(false);
        listScroll.setPadding(0, 0, 0, dp(40));
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setClipChildren(false);
        listScroll.addView(list, new FrameLayout.LayoutParams(-1, -2));
        left.addView(listScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(left, new LinearLayout.LayoutParams(0, -1, 1));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setPadding(dp(130), dp(56), dp(40), 0);
        previewTitle = text(32, TEXT_DIM, false);
        previewTitle.setMaxLines(2);
        previewTitle.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-1, -2);
        pp.bottomMargin = dp(20);
        right.addView(previewTitle, pp);
        preview = new LinearLayout(this);
        preview.setOrientation(LinearLayout.VERTICAL);
        right.addView(preview, new LinearLayout.LayoutParams(-1, -2));
        root.addView(right, new LinearLayout.LayoutParams(0, -1, 1));
        return root;
    }

    /** Builds the top page and shows it, focusing row {@code focus} (or the first focusable one). */
    private void show(int focus) {
        page = stack.get(stack.size() - 1).get();
        pageTitle.setText(page.title);
        list.removeAllViews();
        View first = null, wanted = null;
        for (int k = 0; k < page.items.size(); k++) {
            Item it = page.items.get(k);
            View row = row(it, false);
            list.addView(row);
            if (row.isFocusable()) {
                if (first == null) first = row;
                if (k == focus) wanted = row;
            }
        }
        View target = wanted != null ? wanted : first;
        if (target != null) {
            target.requestFocus();
            if (wanted == null) listScroll.scrollTo(0, 0);
        } else {
            updatePreview(null);
        }
    }

    /** Rebuilds the open page in place, keeping the focused row. */
    private void refresh() {
        View focused = getCurrentFocus();
        int index = focused == null ? -1 : list.indexOfChild(focused);
        int scroll = listScroll.getScrollY();
        show(index);
        listScroll.scrollTo(0, scroll);
    }

    private void open(Item it) {
        View focused = getCurrentFocus();
        focusStack.set(focusStack.size() - 1, focused == null ? -1 : list.indexOfChild(focused));
        stack.add(it.target);
        focusStack.add(-1);
        show(-1);
    }

    private void back() {
        if (stack.size() <= 1) {
            finish();
            return;
        }
        stack.remove(stack.size() - 1);
        focusStack.remove(focusStack.size() - 1);
        show(focusStack.get(focusStack.size() - 1));
    }

    @Override
    public void onBackPressed() {
        back();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            View focused = getCurrentFocus();
            Item it = focused == null ? null : (Item) focused.getTag();
            int key = event.getKeyCode();
            boolean rtl = getWindow().getDecorView().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            boolean forward = key == (rtl ? KeyEvent.KEYCODE_DPAD_LEFT : KeyEvent.KEYCODE_DPAD_RIGHT);
            boolean backward = key == (rtl ? KeyEvent.KEYCODE_DPAD_RIGHT : KeyEvent.KEYCODE_DPAD_LEFT);
            if (it != null && it.kind == Kind.SLIDER && (forward || backward)) {
                int v = it.value.getAsInt() + (forward ? it.step : -it.step);
                v = Math.max(it.min, Math.min(it.max, v));
                if (v != it.value.getAsInt()) {
                    it.setValue.accept(v);
                    bindRow(focused, it, true);
                    updatePreview(it);
                }
                return true;
            }
            if (forward) {
                if (it != null && it.kind == Kind.NAV && enabled(it)) open(it);
                return true;
            }
            if (backward) {
                back();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private boolean enabled(Item it) {
        return it.enabled == null || it.enabled.getAsBoolean();
    }

    private void click(Item it) {
        if (!enabled(it)) return;
        switch (it.kind) {
            case NAV: open(it); break;
            case TOGGLE:
                it.setOn.accept(!it.on.getAsBoolean());
                refresh();
                break;
            case ACTION:
            case RADIO:
                if (it.action != null) it.action.run();
                break;
            default: break;
        }
    }

    // ---- rows -------------------------------------------------------------------------------------------

    private View row(Item it, boolean inPreview) {
        if (it.kind == Kind.HEADER) {
            TextView h = text(9, TEXT_DIM, true);
            h.setAllCaps(true);
            h.setLetterSpacing(0.04f);
            h.setText(it.title);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMarginStart(dp(inPreview ? 0 : 29));
            lp.topMargin = dp(list.getChildCount() == 0 && !inPreview ? 4 : 18);
            lp.bottomMargin = dp(8);
            h.setLayoutParams(lp);
            return h;
        }
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(inPreview ? 56 : 64));
        row.setPadding(dp(inPreview ? 0 : 29), dp(10), dp(inPreview ? 0 : 24), dp(10));
        row.setTag(it);

        FrameLayout chip = new FrameLayout(this);
        chip.setId(R.id.icon);
        if (it.icon != 0) {
            ImageView icon = new ImageView(this);
            icon.setImageResource(it.icon);
            chip.addView(icon, new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER));
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(dp(40), dp(40));
            cp.setMarginEnd(dp(12));
            row.addView(chip, cp);
        }

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(14, TEXT, false);
        title.setId(R.id.title);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(title);
        TextView summary = text(12, TEXT_DIM, false);
        summary.setId(R.id.status);
        summary.setMaxLines(3);
        summary.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.topMargin = dp(3);
        texts.addView(summary, sp);
        if (it.kind == Kind.SLIDER) {
            FrameLayout bar = new FrameLayout(this);
            bar.setId(R.id.rows);
            View fill = new View(this);
            bar.addView(fill, new FrameLayout.LayoutParams(0, -1));
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, dp(4));
            bp.topMargin = dp(8);
            texts.addView(bar, bp);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));

        if (it.kind == Kind.TOGGLE || it.kind == Kind.RADIO) {
            FrameLayout control = new FrameLayout(this);
            control.setId(R.id.dot);
            LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(dp(it.kind == Kind.TOGGLE ? 40 : 22), dp(22));
            wp.setMarginStart(dp(16));
            row.addView(control, wp);
        }

        if (!inPreview && it.kind != Kind.INFO) {
            row.setFocusable(true);
            row.setClickable(true);
            row.setOnClickListener(v -> click(it));
            row.setOnFocusChangeListener((v, has) -> {
                bindRow(v, it, has);
                v.animate().scaleX(has ? 1.03f : 1f).scaleY(has ? 1.03f : 1f).setDuration(120).start();
                if (has) updatePreview(it);
            });
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(inPreview ? 0 : 2);
        row.setLayoutParams(lp);
        bindRow(row, it, false);
        if (inPreview) row.setAlpha(enabled(it) ? 1f : 0.5f);
        return row;
    }

    /** Puts an item's current text and state into its row, coloured for focus. */
    private void bindRow(View row, Item it, boolean focused) {
        boolean on = enabled(it);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        bg.setColor(focused ? CARD : Color.TRANSPARENT);
        row.setBackground(bg);
        if (!focused) row.setAlpha(on ? 1f : 0.5f);
        else row.setAlpha(1f);

        TextView title = row.findViewById(R.id.title);
        title.setText(it.title);
        title.setTextColor(focused ? TEXT_ON_CARD : it.kind == Kind.INFO ? TEXT_DIM : TEXT);
        TextView summary = row.findViewById(R.id.status);
        String s = it.summary == null ? null : it.summary.get();
        summary.setText(s);
        summary.setVisibility(TextUtils.isEmpty(s) ? View.GONE : View.VISIBLE);
        summary.setTextColor(focused ? SUB_ON_CARD : TEXT_DIM);

        View chip = row.findViewById(R.id.icon);
        if (chip instanceof FrameLayout && ((FrameLayout) chip).getChildCount() > 0) {
            GradientDrawable c = new GradientDrawable();
            c.setShape(GradientDrawable.OVAL);
            c.setColor(focused ? CHIP_ON_CARD : CHIP);
            chip.setBackground(c);
            ((ImageView) ((FrameLayout) chip).getChildAt(0)).setColorFilter(focused ? BLUE : TEXT);
        }

        FrameLayout bar = row.findViewById(R.id.rows);
        if (bar != null && it.kind == Kind.SLIDER) {
            GradientDrawable track = new GradientDrawable();
            track.setCornerRadius(dp(2));
            track.setColor(focused ? 0xFFBDC1C6 : TRACK);
            bar.setBackground(track);
            View fill = bar.getChildAt(0);
            GradientDrawable f = new GradientDrawable();
            f.setCornerRadius(dp(2));
            f.setColor(focused ? BLUE : LIGHT_BLUE);
            fill.setBackground(f);
            float part = it.max == it.min ? 0f : (it.value.getAsInt() - it.min) / (float) (it.max - it.min);
            bar.post(() -> {
                ViewGroup.LayoutParams lp = fill.getLayoutParams();
                lp.width = Math.max(dp(4), Math.round(bar.getWidth() * part));
                fill.setLayoutParams(lp);
            });
        }

        FrameLayout control = row.findViewById(R.id.dot);
        if (control != null) {
            control.removeAllViews();
            boolean checked = it.on != null && it.on.getAsBoolean();
            if (it.kind == Kind.TOGGLE) {
                View track = new View(this);
                GradientDrawable t = new GradientDrawable();
                t.setCornerRadius(dp(7));
                t.setColor(checked ? (focused ? 0xFFAECBFA : 0x998AB4F8) : (focused ? 0xFF9AA0A6 : TRACK));
                track.setBackground(t);
                control.addView(track, new FrameLayout.LayoutParams(dp(34), dp(14), Gravity.CENTER));
                View thumb = new View(this);
                GradientDrawable th = new GradientDrawable();
                th.setShape(GradientDrawable.OVAL);
                th.setColor(checked ? (focused ? BLUE : LIGHT_BLUE) : (focused ? 0xFFF1F3F4 : 0xFFBDC1C6));
                thumb.setBackground(th);
                thumb.setElevation(dp(1));
                control.addView(thumb, new FrameLayout.LayoutParams(dp(20), dp(20),
                        Gravity.CENTER_VERTICAL | (checked ? Gravity.END : Gravity.START)));
            } else {
                View ring = new View(this);
                GradientDrawable r = new GradientDrawable();
                r.setShape(GradientDrawable.OVAL);
                int color = checked ? (focused ? BLUE : LIGHT_BLUE) : (focused ? SUB_ON_CARD : TEXT_DIM);
                r.setStroke(dp(2), color);
                ring.setBackground(r);
                control.addView(ring, new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER));
                if (checked) {
                    View dot = new View(this);
                    GradientDrawable d = new GradientDrawable();
                    d.setShape(GradientDrawable.OVAL);
                    d.setColor(color);
                    dot.setBackground(d);
                    control.addView(dot, new FrameLayout.LayoutParams(dp(10), dp(10), Gravity.CENTER));
                }
            }
        }
    }

    /** The right half: what the focused category holds, or what the focused setting does. */
    private void updatePreview(Item it) {
        preview.removeAllViews();
        previewTitle.setText(null);
        if (it == null) return;
        if (it.kind == Kind.NAV && it.target != null && enabled(it)) {
            Page p = it.target.get();
            previewTitle.setText(p.title);
            int shown = 0;
            for (Item child : p.items) {
                if (shown++ >= 9) break;
                preview.addView(row(child, true));
            }
            preview.setAlpha(0.6f);
            return;
        }
        preview.setAlpha(1f);
        if (it.description == null) return;
        previewTitle.setText(it.title);
        TextView d = text(14, TEXT_DIM, false);
        d.setText(it.description);
        d.setLineSpacing(0, 1.2f);
        preview.addView(d);
    }

    // ---- pages ------------------------------------------------------------------------------------------

    private Page rootPage() {
        Page p = new Page(getString(R.string.s_title));
        p.add(header(R.string.s_group_system));
        p.add(action(R.drawable.ic_s_tv, getString(R.string.tv_settings), () -> getString(R.string.s_tv_settings_summary),
                () -> start(SystemSettings.intent(this, Settings.ACTION_SETTINGS))));
        p.add(header(R.string.s_group_home));
        p.add(nav(R.drawable.ic_s_home, getString(R.string.s_home), () -> getString(R.string.s_home_summary), this::homePage));
        p.add(wallpaperChoice());
        p.add(nav(R.drawable.ic_s_cloud, getString(R.string.s_info), () -> getString(R.string.s_info_summary), this::infoPage));
        p.add(nav(R.drawable.ic_s_remote, getString(R.string.fork_keys_title), () -> getString(R.string.s_keys_summary), this::keysPage));
        p.add(nav(R.drawable.ic_mic, getString(R.string.voice_search), () -> getString(VoiceAi.enabled(this)
                ? R.string.s_voice_summary_ai : R.string.s_voice_summary_plain), this::voicePage));
        p.add(nav(R.drawable.ic_s_motion, getString(R.string.s_motion), () -> getString(animations()
                ? R.string.on : R.string.off), this::motionPage));
        Item reset = nav(R.drawable.ic_s_restore, getString(R.string.s_reset), () -> getString(R.string.s_reset_summary),
                this::resetPage);
        p.add(reset);
        p.add(nav(R.drawable.ic_s_info, getString(R.string.s_about), this::version, this::aboutPage));
        return p;
    }

    private Page homePage() {
        Page p = new Page(getString(R.string.s_home));
        if (config.launcherSettingsButton) {
            p.add(action(0, getString(R.string.reorder), null, () -> {
                setResult(RESULT_OK, new Intent().putExtra(EXTRA_REORDER, true));
                finish();
            }));
        }
        p.add(slider(getString(R.string.columns_label), getString(R.string.s_columns_desc), 4, 8, 1,
                () -> choices.getInt("columns", config.columns), v -> {
                    choices.edit().putInt("columns", v).apply();
                    changed();
                }, String::valueOf));
        if (!fork.homeDockOnly) {
            String[] rows = {"0", "1", "2", "3"};
            p.add(choice(0, getString(R.string.rows_below_dock_label), () -> rows,
                    () -> choices.getInt(Config.ROWS_BELOW_DOCK_KEY, config.rowsBelowDock), v -> {
                        choices.edit().putInt(Config.ROWS_BELOW_DOCK_KEY, v).apply();
                        changed();
                    }));
        }
        p.add(slider(getString(R.string.fork_dock_transparency), getString(R.string.s_dock_desc), 0, 70, 5,
                () -> 100 - Config.chosenDockOpacity(choices.getInt(Config.DOCK_OPACITY_KEY, -1), config.dockOpacity), v -> {
                    choices.edit().putInt(Config.DOCK_OPACITY_KEY, 100 - v).apply();
                    changed();
                }, v -> v + "%"));
        String[] clock = {getString(R.string.clock_format_system), getString(R.string.clock_format_12),
                getString(R.string.clock_format_24)};
        p.add(choice(0, getString(R.string.s_clock_format), () -> clock,
                () -> Math.max(0, Arrays.asList(CLOCK_FORMATS).indexOf(choices.getString(Config.CLOCK_FORMAT_KEY, config.clockFormat))), v -> {
                    choices.edit().putString(Config.CLOCK_FORMAT_KEY, CLOCK_FORMATS[v]).apply();
                    changed();
                }));
        if (config.openOnStartChoice) p.add(openOnStartChoice());
        return p;
    }

    private Item wallpaperChoice() {
        List<String> values = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        if (!fork.wallpaperGroups.isEmpty()) {
            values.add(ForkConfig.GROUP_PREFIX + ForkConfig.GROUP_ALL);
            labels.add(getString(R.string.fork_wallpaper_random));
            for (String[] g : fork.wallpaperGroups) {
                values.add(ForkConfig.GROUP_PREFIX + g[0]);
                labels.add(g[1]);
            }
        } else {
            File dir = Config.dir(this);
            values.add(null);
            labels.add(getString(R.string.fork_wallpaper_random));
            for (File f : WallpaperRotation.images(dir == null ? null : new File(dir, "wallpapers"))) {
                values.add(f.getName());
                labels.add(f.getName());
            }
        }
        values.add("none");
        labels.add(getString(R.string.wallpaper_none));
        String[] l = labels.toArray(new String[0]);
        return choice(R.drawable.ic_s_wallpaper, getString(R.string.s_wallpapers), () -> l, () -> {
            String current = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
            int i = values.indexOf(current);
            return i >= 0 ? i : 0;   // nothing chosen yet rotates every picture, the same as Random
        }, v -> {
            String value = values.get(v);
            if (value == null) choices.edit().remove(Config.WALLPAPER_CHOICE_KEY).apply();
            else choices.edit().putString(Config.WALLPAPER_CHOICE_KEY, value).apply();
            changed();
        });
    }

    private Item openOnStartChoice() {
        List<String[]> apps = tvApps();
        String[] labels = new String[apps.size() + 1];
        labels[0] = getString(R.string.off);
        for (int i = 0; i < apps.size(); i++) labels[i + 1] = apps.get(i)[0];
        return choice(0, getString(R.string.open_on_start_label), () -> labels, () -> {
            String pkg = choices.getString(OpenOnStart.KEY, "");
            for (int i = 0; i < apps.size(); i++) if (apps.get(i)[1].equals(pkg)) return i + 1;
            return 0;
        }, v -> {
            if (v == 0) choices.edit().remove(OpenOnStart.KEY).apply();
            else {
                OpenOnStart.skipThisBoot(this);
                choices.edit().putString(OpenOnStart.KEY, apps.get(v - 1)[1]).apply();
            }
        });
    }

    private Page infoPage() {
        SharedPreferences np = InfoWidgets.prefs(this);
        Page p = new Page(getString(R.string.s_info));
        p.add(header(R.string.s_weather));
        p.add(toggle(getString(R.string.s_show_weather), null, () -> InfoWidgets.weatherOn(this, fork), v -> {
            InfoWidgets.prefs(this).edit().putBoolean(InfoWidgets.KEY_WEATHER_ON, v).apply();
            changed();
            if (v && InfoWidgets.prefs(this).getString(InfoWidgets.KEY_CITY, "").isEmpty()) showCitySearch();
        }));
        p.add(action(0, getString(R.string.s_city), () -> {
            String city = InfoWidgets.prefs(this).getString(InfoWidgets.KEY_CITY, "");
            return city.isEmpty() ? getString(R.string.s_city_none) : city;
        }, this::showCitySearch));
        Item range = choice(0, getString(R.string.fork_weather_range_title),
                () -> getResources().getStringArray(R.array.fork_weather_ranges),
                () -> Math.max(0, Arrays.asList(InfoWidgets.RANGES).indexOf(InfoWidgets.range(this))), v -> {
                    InfoWidgets.prefs(this).edit().putString(InfoWidgets.KEY_RANGE, InfoWidgets.RANGES[v]).apply();
                    InfoWidgets.invalidateWeather(this);
                    changed();
                });
        range.enabled = () -> InfoWidgets.weatherOn(this, fork);
        p.add(range);
        p.add(header(R.string.s_rates));
        p.add(toggle(getString(R.string.s_show_rates), null, () -> InfoWidgets.ratesOn(this, fork), v -> {
            InfoWidgets.prefs(this).edit().putBoolean(InfoWidgets.KEY_RATES_ON, v).apply();
            changed();
        }));
        Item currencies = nav(0, getString(R.string.s_currencies),
                () -> TextUtils.join(", ", InfoWidgets.currencies(this)), this::currenciesPage);
        currencies.enabled = () -> InfoWidgets.ratesOn(this, fork);
        p.add(currencies);
        p.add(header(R.string.s_news));
        p.add(toggle(getString(R.string.s_show_news), null, () -> InfoWidgets.newsOn(this, fork), v -> {
            InfoWidgets.prefs(this).edit().putBoolean(InfoWidgets.KEY_NEWS_ON, v).apply();
            changed();
        }));
        BooleanSupplier news = () -> InfoWidgets.newsOn(this, fork);
        Item topic = choice(0, getString(R.string.fork_news_topic_title),
                () -> getResources().getStringArray(R.array.fork_news_topics),
                () -> Math.max(0, Arrays.asList(InfoWidgets.NEWS_TOPICS).indexOf(InfoWidgets.newsTopic(this))), v -> {
                    InfoWidgets.prefs(this).edit().putString(InfoWidgets.KEY_NEWS_TOPIC, InfoWidgets.NEWS_TOPICS[v]).apply();
                    InfoWidgets.invalidateNews(this);
                    changed();
                });
        topic.enabled = news;
        p.add(topic);
        Item size = slider(getString(R.string.fork_news_size), null, 16, 40, 2,
                () -> np.getInt(InfoWidgets.KEY_NEWS_SIZE, InfoWidgets.NEWS_SIZE_DEFAULT), v -> {
                    np.edit().putInt(InfoWidgets.KEY_NEWS_SIZE, v).apply();
                    changed();
                }, String::valueOf);
        size.enabled = news;
        p.add(size);
        Item speed = slider(getString(R.string.fork_news_speed), null, 20, 200, 10,
                () -> np.getInt(InfoWidgets.KEY_NEWS_SPEED, InfoWidgets.NEWS_SPEED_DEFAULT), v -> {
                    np.edit().putInt(InfoWidgets.KEY_NEWS_SPEED, v).apply();
                    changed();
                }, String::valueOf);
        speed.enabled = news;
        p.add(speed);
        Item band = slider(getString(R.string.fork_news_bg), null, 0, 100, 5,
                () -> np.getInt(InfoWidgets.KEY_NEWS_BG, InfoWidgets.NEWS_BG_DEFAULT), v -> {
                    np.edit().putInt(InfoWidgets.KEY_NEWS_BG, v).apply();
                    changed();
                }, v -> v + "%");
        band.enabled = news;
        p.add(band);
        p.add(header(R.string.s_sources));
        p.add(info(getString(R.string.fork_info_credit), null));
        return p;
    }

    /** One switch per currency; up to MAX_CURRENCIES on, and at least one. */
    private Page currenciesPage() {
        Page p = new Page(getString(R.string.s_currencies));
        p.add(info(getString(R.string.s_currencies_limit, InfoWidgets.MAX_CURRENCIES), null));
        String[] names = getResources().getStringArray(R.array.s_currency_names);
        for (int k = 0; k < InfoWidgets.CURRENCIES.length; k++) {
            final String code = InfoWidgets.CURRENCIES[k];
            Item it = toggle(code, null, () -> InfoWidgets.currencies(this).contains(code), v -> {
                List<String> chosen = new ArrayList<>(InfoWidgets.currencies(this));
                if (v && !chosen.contains(code)) chosen.add(code);
                if (!v) chosen.remove(code);
                if (chosen.isEmpty() || chosen.size() > InfoWidgets.MAX_CURRENCIES) return;
                InfoWidgets.setCurrencies(this, chosen);
                changed();
            });
            it.summary = () -> currencyName(names, code);
            // A sixth cannot be switched on, and the last one cannot be switched off.
            it.enabled = () -> {
                List<String> chosen = InfoWidgets.currencies(this);
                return chosen.contains(code) ? chosen.size() > 1 : chosen.size() < InfoWidgets.MAX_CURRENCIES;
            };
            p.add(it);
        }
        return p;
    }

    private static String currencyName(String[] names, String code) {
        int i = Arrays.asList(InfoWidgets.CURRENCIES).indexOf(code);
        return i >= 0 && i < names.length ? names[i] : "";
    }

    private Page keysPage() {
        Page p = new Page(getString(R.string.fork_keys_title));
        List<String[]> options = keyChoices();
        String[] labels = new String[options.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = options.get(i)[0];
        for (int[] button : ForkKeys.BUTTONS) {
            if (button[0] <= 0) continue;
            final int code = button[0];
            p.add(choice(0, getString(button[1]), () -> labels, () -> {
                String current = ForkKeys.target(this, code);
                for (int i = 0; i < options.size(); i++) {
                    String v = options.get(i)[1];
                    if (current == null ? v == null : current.equals(v)) return i;
                }
                return -1;
            }, v -> ForkKeys.set(this, code, options.get(v)[1])));
        }
        return p;
    }

    // ---- voice search -----------------------------------------------------------------------------------

    private Page voicePage() {
        Page p = new Page(getString(R.string.voice_search));
        SharedPreferences v = VoiceAi.prefs(this);
        p.add(header(R.string.s_voice_ai));
        p.add(toggle(getString(R.string.s_voice_ai_on), getString(R.string.s_voice_ai_desc),
                () -> v.getBoolean(VoiceAi.KEY_ON, true), on -> v.edit().putBoolean(VoiceAi.KEY_ON, on).apply()));
        p.add(action(0, getString(R.string.s_voice_ai_key), () -> getString(VoiceAi.key(this) != null
                ? R.string.s_voice_ai_key_set : R.string.s_voice_ai_key_none), this::showAiKeyInput));
        p.add(action(0, getString(R.string.s_voice_ai_key_file), () -> getString(R.string.s_voice_ai_key_file_desc), () -> {
            String k = VoiceAi.keyFromFile(this);
            if (k == null) {
                Toast.makeText(this, R.string.s_voice_ai_key_file_none, Toast.LENGTH_LONG).show();
                return;
            }
            saveAiKey(k);
        }));
        p.add(action(0, getString(R.string.s_voice_ai_check), () -> v.getString(VoiceAi.KEY_MODEL,
                getString(R.string.s_voice_ai_model_none)), this::checkAi));
        p.add(header(R.string.s_voice_music));
        VoiceActions actions = new VoiceActions(this);
        List<String> music = new ArrayList<>();
        for (String pkg : VoiceActions.MUSIC_APPS) if (actions.installed(pkg)) music.add(pkg);
        String[] labels = new String[music.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = actions.label(music.get(i));
        p.add(choice(0, getString(R.string.s_voice_music_app), () -> labels,
                () -> music.indexOf(actions.musicApp()),
                i -> v.edit().putString(VoiceActions.KEY_MUSIC, music.get(i)).apply()));
        return p;
    }

    private void showAiKeyInput() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(R.string.s_voice_ai_key_hint);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        box.addView(input);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.s_voice_ai_key)
                .setView(box)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String k = input.getText().toString().trim();
                    if (!k.isEmpty()) saveAiKey(k);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** A new key: the model is picked again for it, at once. */
    private void saveAiKey(String key) {
        VoiceAi.prefs(this).edit().putString(VoiceAi.KEY_KEY, key).remove(VoiceAi.KEY_MODEL).apply();
        checkAi();
    }

    /** Checks the key off the main thread and keeps the model that answered. */
    private void checkAi() {
        String key = VoiceAi.key(this);
        if (key == null) {
            Toast.makeText(this, R.string.s_voice_ai_key_none, Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, R.string.s_voice_ai_checking, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String model;
            try {
                model = VoiceAi.check(key);
                VoiceAi.prefs(this).edit().putString(VoiceAi.KEY_MODEL, model).apply();
            } catch (java.io.IOException | RuntimeException e) {
                model = null;
            }
            String result = model;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                Toast.makeText(this, result != null ? getString(R.string.s_voice_ai_ok, result)
                        : getString(R.string.s_voice_ai_failed), Toast.LENGTH_LONG).show();
                refresh();
            });
        }, "ai-check").start();
    }

    private Page motionPage() {
        Page p = new Page(getString(R.string.s_motion));
        p.add(toggle(getString(R.string.s_animations), getString(R.string.s_animations_desc), this::animations, v -> {
            SharedPreferences.Editor e = choices.edit().putBoolean("animations", v);
            if (!v) e.putBoolean("advanced_effects", false);
            e.apply();
            changed();
        }));
        // Switching the effects on asks first, on a page of its own with the warning beside it.
        Item confirm = nav(0, getString(R.string.advanced_effects), null, () -> {
            Page c = new Page(getString(R.string.advanced_effects));
            Item yes = action(0, getString(R.string.enable), null, () -> {
                choices.edit().putBoolean("advanced_effects", true).apply();
                changed();
                back();
            });
            yes.description = getString(R.string.advanced_effects_warning);
            Item no = action(0, getString(android.R.string.cancel), null, this::back);
            no.description = yes.description;
            return c.add(yes).add(no);
        });
        Item advanced = toggle(getString(R.string.advanced_effects), getString(R.string.advanced_effects_warning),
                () -> animations() && choices.getBoolean("advanced_effects", config.advancedEffects), v -> {
                    if (v) {
                        open(confirm);
                    } else {
                        choices.edit().putBoolean("advanced_effects", false).apply();
                        changed();
                    }
                });
        advanced.enabled = this::animations;
        p.add(advanced);
        return p;
    }

    private Page resetPage() {
        Page p = new Page(getString(R.string.s_reset));
        Item yes = action(0, getString(R.string.s_reset_confirm), null, () -> {
            choices.edit().remove("columns").remove(Config.ROWS_BELOW_DOCK_KEY).remove("animations")
                    .remove("advanced_effects").remove(Config.WALLPAPER_CHOICE_KEY)
                    .remove(Config.DOCK_OPACITY_KEY).remove(Config.CLOCK_FORMAT_KEY).remove(OpenOnStart.KEY).commit();
            config = Config.load(this);   // the baseline the rows fall back to: built-in defaults again
            changed();
            Toast.makeText(this, R.string.s_reset_done, Toast.LENGTH_SHORT).show();
            back();
        });
        yes.description = getString(R.string.s_reset_desc);
        Item no = action(0, getString(android.R.string.cancel), null, this::back);
        no.description = yes.description;
        return p.add(yes).add(no);
    }

    private Page aboutPage() {
        Page p = new Page(getString(R.string.s_about));
        p.add(action(0, getString(R.string.s_version), this::version, null));
        p.add(action(0, getString(R.string.s_package), this::getPackageName, null));
        boolean system = (getApplicationInfo().flags & ApplicationInfo.FLAG_SYSTEM) != 0;
        p.add(action(0, getString(R.string.s_system_app), () -> getString(system ? R.string.s_yes : R.string.s_no), null));
        return p;
    }

    // ---- helpers ----------------------------------------------------------------------------------------

    private boolean animations() {
        return choices.getBoolean("animations", config.animations);
    }

    private String versionName;

    private String version() {
        if (versionName != null) return versionName;
        try {
            versionName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            versionName = "";
        }
        return versionName;
    }

    /** Every app with a TV entry point, by name: label, package. Read once per visit. */
    private List<String[]> tvApps() {
        if (tvApps != null) return tvApps;
        PackageManager pm = getPackageManager();
        List<String[]> apps = new ArrayList<>();
        for (ResolveInfo r : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)) {
            String pkg = r.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;
            boolean seen = false;
            for (String[] a : apps) seen |= a[1].equals(pkg);
            if (!seen) apps.add(new String[]{r.loadLabel(pm).toString(), pkg});
        }
        Collections.sort(apps, (a, b) -> a[0].compareToIgnoreCase(b[0]));
        tvApps = apps;
        return apps;
    }

    /** Fixed choices first (value null = the firmware's own behaviour), then every TV app by name. */
    private List<String[]> keyOptions;

    private List<String[]> keyChoices() {
        if (keyOptions != null) return keyOptions;
        List<String[]> out = new ArrayList<>();
        out.add(new String[]{getString(R.string.fork_key_default), null});
        out.add(new String[]{getString(R.string.fork_key_nothing), "ignore"});
        out.add(new String[]{getString(R.string.inputs), RemoteKeys.INPUTS_OVERLAY});
        String tuner = ForkKeys.tunerTarget(this);
        if (tuner != null) out.add(new String[]{getString(R.string.fork_key_live_tv_action), tuner});
        out.add(new String[]{getString(R.string.fork_qs_settings_panel), getPackageName() + "/.QuickSettingsActivity"});
        out.addAll(tvApps());
        keyOptions = out;
        return out;
    }

    /** Type a city, then pick it from Open-Meteo's matches. */
    private void showCitySearch() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(R.string.fork_weather_city_hint);
        input.setText(InfoWidgets.prefs(this).getString(InfoWidgets.KEY_CITY, ""));
        input.setSelectAllOnFocus(true);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        box.addView(input);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.fork_weather_city_title)
                .setView(box)
                .setPositiveButton(R.string.fork_weather_search, (d, w) -> {
                    String q = input.getText().toString().trim();
                    if (q.isEmpty()) return;
                    String lang = getResources().getConfiguration().getLocales().get(0).getLanguage();
                    InfoWidgets.searchCity(q, lang, places -> {
                        if (isFinishing() || isDestroyed()) return;
                        if (places.isEmpty()) {
                            Toast.makeText(this, R.string.fork_weather_not_found, Toast.LENGTH_LONG).show();
                            return;
                        }
                        Item pick = nav(0, getString(R.string.fork_weather_city_title), null, () -> {
                            Page p = new Page(getString(R.string.fork_weather_city_title));
                            for (InfoWidgets.Place pl : places) {
                                Item r = action(0, pl.label, null, () -> {
                                    InfoWidgets.prefs(this).edit()
                                            .putString(InfoWidgets.KEY_CITY, pl.name)
                                            .putFloat(InfoWidgets.KEY_LAT, (float) pl.lat)
                                            .putFloat(InfoWidgets.KEY_LON, (float) pl.lon)
                                            .putBoolean(InfoWidgets.KEY_WEATHER_ON, true).apply();
                                    InfoWidgets.invalidateWeather(this);
                                    changed();
                                    back();
                                });
                                p.add(r);
                            }
                            return p;
                        });
                        open(pick);
                    });
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * A choice was saved. Home rebuilds when it next resumes because the change count moved, whether these
     * settings are left with Back or with the remote's Home button (which cancels the activity result).
     */
    private void changed() {
        setResult(RESULT_OK);
        choices.edit().putLong(Config.SETTINGS_REVISION_KEY,
                choices.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1).apply();
    }

    private void start(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
        }
    }

    private TextView text(int sp, int color, boolean medium) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(medium ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        t.setIncludeFontPadding(true);
        return t;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
