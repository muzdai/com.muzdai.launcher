package com.muzdai.launcher;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * vPro: the apps that are open, as a glass panel over whatever is on screen (remote button action
 * {@link RemoteKeys#RUNNING_APPS}). OK opens an app, holding OK closes it (am force-stop), Back leaves.
 * The list comes from root ({@link RunningApps}); nothing is shown for apps without a launcher entry.
 * Pressing the same button again closes the panel.
 */
public final class RunningAppsActivity extends Activity {
    private static final class Row {
        String pkg, label;
        Drawable icon;
        boolean onScreen, stoppable;
    }

    private LinearLayout rows;
    private TextView hint;
    private volatile boolean gone;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_running_apps);
        ForkConfig.applyOpacity(findViewById(R.id.panel), ForkConfig.load(this).menuOpacity);
        findViewById(R.id.scrim).setOnClickListener(v -> finish());
        rows = findViewById(R.id.rows);
        hint = findViewById(R.id.hint);
        hint.setVisibility(View.GONE);
        message(getString(R.string.running_apps_loading));
        View panel = findViewById(R.id.panel);
        panel.setAlpha(0f);
        panel.setScaleX(0.96f);
        panel.setScaleY(0.96f);
        panel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
        new Thread(this::load, "running-apps").start();
    }

    /** The same button again while the panel is up. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        finish();
    }

    @Override
    protected void onDestroy() {
        gone = true;
        super.onDestroy();
    }

    // ---- loading (background thread) -------------------------------------------------------------

    private void load() {
        String self = getPackageName();
        String out = Root.run("dumpsys activity recents; echo @@PS@@; ps -A -o NAME; echo @@KEEP@@;"
                + " settings get secure enabled_accessibility_services; settings get secure default_input_method");
        if (out == null || !out.contains("@@KEEP@@")) {
            show(null, getString(R.string.running_apps_no_root));
            return;
        }
        int ps = out.indexOf("@@PS@@"), keepAt = out.indexOf("@@KEEP@@");
        String recents = out.substring(0, ps), procs = out.substring(ps + 6, keepAt), keepText = out.substring(keepAt + 8);
        // Android turns a stopped app's accessibility service off for good, and the keyboard is the only way to type.
        Set<String> keep = new HashSet<>();
        for (String line : keepText.split("\n")) {
            for (String c : line.split(":")) {
                String owner = RunningApps.owner(c);
                if (!owner.isEmpty()) keep.add(owner);
            }
        }
        PackageManager pm = getPackageManager();
        Map<String, String> launchable = launchable(pm);
        List<Row> found = new ArrayList<>();
        Set<String> closable = new HashSet<>();
        for (RunningApps.Task t : RunningApps.parse(recents, procs, launchable::containsKey)) {
            if (t.pkg.equals(self)) continue;
            Row r = new Row();
            r.pkg = t.pkg;
            r.label = launchable.get(t.pkg);
            r.onScreen = t.hasActivity;
            r.stoppable = RunningApps.mayStop(t.pkg, self, keep);
            try {
                r.icon = pm.getApplicationIcon(t.pkg);
            } catch (PackageManager.NameNotFoundException e) {
                r.icon = null;
            }
            if (r.stoppable) closable.add(t.pkg);
            found.add(r);
        }
        // The software under the TV's inputs and the receiver of the remote's Input key stay (off, HDMI shows
        // "No signal"); when the check doesn't finish, nothing is closable this time.
        Set<String> needed = neededByTv(closable);
        for (Row r : found) if (needed == null || needed.contains(r.pkg)) r.stoppable = false;
        // What may not be closed is not listed at all.
        List<Row> shown = new ArrayList<>();
        for (Row r : found) if (r.stoppable) shown.add(r);
        show(shown, needed == null ? getString(R.string.running_apps_no_root)
                : shown.isEmpty() ? getString(R.string.running_apps_none) : null);
    }

    /** Apps with a TV or phone launcher entry: package → name. */
    private Map<String, String> launchable(PackageManager pm) {
        Map<String, String> out = new HashMap<>();
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(category), 0)) {
                String pkg = ri.activityInfo.packageName;
                if (!out.containsKey(pkg)) out.put(pkg, String.valueOf(ri.loadLabel(pm)));
            }
        }
        return out;
    }

    /** Of these, the ones the TV's inputs or remote need (as Free My TV's HomeMode.neededByTv); null if unknown. */
    private static Set<String> neededByTv(Set<String> pkgs) {
        Set<String> keep = new HashSet<>();
        if (pkgs.isEmpty()) return keep;
        StringBuilder list = new StringBuilder();
        for (String p : pkgs) if (RunningApps.safe(p) && !RunningApps.safeToClose(p)) list.append(p).append(' ');
        if (list.length() == 0) return keep;
        // dumpsys is piped straight into grep: a big package (the TV's Settings) does not fit a shell variable passed
        // to printf ("Argument list too long"). A package the check cannot read is kept.
        String out = Root.run("for p in " + list + "; do "
                + "if ! dumpsys package $p | grep -q -F \"Package [$p]\"; then echo \"keep $p\"; "
                + "elif dumpsys package $p | sed -n -r '/requested permissions:/,/(install|runtime) permissions:|User [0-9]+:/p'"
                + " | grep -q TV_INPUT_HARDWARE || dumpsys package $p | grep -q -E 'TV_INPUT_BUTTON|CONNECT_INPUT|AddAccessory';"
                + " then echo \"keep $p\"; else echo \"- $p\"; fi; done; echo checked");
        if (out == null || !out.contains("checked")) return null;
        Matcher m = Pattern.compile("keep ([\\w.]+)").matcher(out);
        while (m.find()) keep.add(m.group(1));
        return keep;
    }

    // ---- UI (main thread) ------------------------------------------------------------------------

    private void show(List<Row> found, String text) {
        runOnUiThread(() -> {
            if (gone || isFinishing()) return;
            rows.removeAllViews();
            if (found == null || found.isEmpty()) {
                message(text);
                return;
            }
            for (Row r : found) addRow(r);
            hint.setText(R.string.running_apps_hint);
            hint.setVisibility(View.VISIBLE);
            rows.getChildAt(0).requestFocus();
        });
    }

    /** A plain line of text in place of the list (loading, nothing open, no root). */
    private void message(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(0xCCFFFFFF);
        t.setTextSize(14);
        int p = (int) (12 * getResources().getDisplayMetrics().density);
        t.setPadding(p, p, p, p);
        rows.addView(t);
    }

    private void addRow(Row r) {
        View row = getLayoutInflater().inflate(R.layout.item_running_app, rows, false);
        ImageView icon = row.findViewById(R.id.icon);
        if (r.icon != null) icon.setImageDrawable(r.icon);
        ((TextView) row.findViewById(R.id.title)).setText(r.label);
        ((TextView) row.findViewById(R.id.status)).setText(r.onScreen ? R.string.running_apps_on_screen
                : R.string.running_apps_background);
        row.setOnClickListener(v -> {
            finish();
            open(r.pkg);
        });
        row.setOnLongClickListener(v -> {
            if (!r.stoppable) return true;
            close(r, row);
            return true;
        });
        rows.addView(row);
    }

    private void open(String pkg) {
        PackageManager pm = getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) return;
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    /** Force-stops the app as root, then takes its row out (focus moves to a neighbour). */
    private void close(Row r, View row) {
        if (!RunningApps.safe(r.pkg)) return;
        row.setEnabled(false);
        row.animate().alpha(0.35f).setDuration(150).start();
        new Thread(() -> {
            String out = Root.run("am force-stop " + r.pkg + "; pidof " + r.pkg + " || echo gone");
            boolean stopped = out != null && out.contains("gone");
            runOnUiThread(() -> {
                if (gone || isFinishing()) return;
                Toast.makeText(this, getString(stopped ? R.string.running_apps_closed
                        : R.string.running_apps_not_closed, r.label), Toast.LENGTH_SHORT).show();
                if (!stopped) {
                    row.setEnabled(true);
                    row.animate().alpha(1f).setDuration(150).start();
                    return;
                }
                int at = rows.indexOfChild(row);
                rows.removeView(row);
                if (rows.getChildCount() == 0) {
                    hint.setVisibility(View.GONE);
                    message(getString(R.string.running_apps_none));
                } else {
                    rows.getChildAt(Math.min(at, rows.getChildCount() - 1)).requestFocus();
                }
            });
        }, "running-apps-close").start();
    }
}
