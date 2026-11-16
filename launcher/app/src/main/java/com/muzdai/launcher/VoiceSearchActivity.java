package com.muzdai.launcher;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/**
 * The voice panel, for requests that need a choice: where to search, or an app that is not installed
 * (with its Google Play name and logo). Home listens in place and opens this with the words in {@link #EXTRA_QUERY};
 * opened without them (microphone permission not granted yet) it asks and listens itself. Its microphone listens again,
 * and its text line takes typed words.
 */
public final class VoiceSearchActivity extends Activity {

    private static final int REQ_MIC = 1;
    /** The request in words, from Home or from a test over adb. */
    static final String EXTRA_QUERY = "query";

    private EditText query;
    private TextView hint;
    private ImageView mic;
    private LinearLayout rows, missing;
    private VoiceActions actions;
    private VoiceInput voice;
    /** The microphone permission prompt is up: its window stops this one, which must not close the panel. */
    private boolean askingPermission;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_voice);
        ForkConfig fork = ForkConfig.load(this);
        if (fork != null) ForkConfig.applyOpacity(findViewById(R.id.panel), fork.menuOpacity);
        query = findViewById(R.id.query);
        hint = findViewById(R.id.hint);
        mic = findViewById(R.id.mic);
        rows = findViewById(R.id.rows);
        missing = findViewById(R.id.missing);
        actions = new VoiceActions(this);
        voice = new VoiceInput(this, new VoiceInput.Callback() {
            @Override public void onListening(boolean on) {
                mic.setActivated(on);
                if (on) {
                    clearResults();
                    hint.setText(R.string.voice_listening);
                } else {
                    mic.setScaleX(1f);
                    mic.setScaleY(1f);
                }
            }
            @Override public void onLevel(float level) {
                mic.setScaleX(1f + level / 40f);
                mic.setScaleY(1f + level / 40f);
            }
            @Override public void onPartial(String text) { query.setText(text); }
            @Override public void onResult(String text) {
                query.setText(text);
                handle(text);
            }
            @Override public void onError(int messageRes, int code) { hint.setText(getString(messageRes, code)); }
        });
        mic.setOnClickListener(v -> listen());
        query.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                handle(query.getText().toString());
                return true;
            }
            return false;
        });
        findViewById(R.id.missingCancel).setOnClickListener(v -> finish());
        String given = getIntent().getStringExtra(EXTRA_QUERY);
        if (given != null && !given.trim().isEmpty()) {
            query.setText(given);
            handle(given);
        } else {
            // After the window is up: before that the text line, the first focusable view, took focus.
            mic.post(mic::requestFocus);
            listen();
        }
    }

    /** TCL Channel again while the panel is up (singleTop): listen again; words given with it are acted on. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String given = intent.getStringExtra(EXTRA_QUERY);
        if (given != null && !given.trim().isEmpty()) {
            query.setText(given);
            handle(given);
        } else {
            listen();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        voice.cancel();
        // Nothing of the panel stays behind an app it opened, or after Home.
        if (!isChangingConfigurations() && !askingPermission) finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        voice.destroy();
    }

    private void listen() {
        if (voice.listening()) return;
        if (!VoiceInput.permitted(this)) {
            askingPermission = true;
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        if (!voice.start()) hint.setText(R.string.voice_no_recognizer);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQ_MIC) return;
        askingPermission = false;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) listen();
        else hint.setText(R.string.voice_err_permission);
    }

    private void handle(String text) {
        voice.cancel();
        hideKeyboard();
        if (text == null || text.trim().isEmpty()) return;
        VoiceActions.Plan p = actions.plan(text);
        switch (p.kind) {
            case START:
                if (start(p.intent)) finish();
                return;
            case MISSING:
                showMissing(p.pkg, p.name);
                return;
            default:
                showChoices(p.query, p.maybeApp);
        }
    }

    /** "Search in …" rows for the installed apps that take a query, and Google Play for what may be an app. */
    private void showChoices(String q, String maybeApp) {
        clearResults();
        hint.setText(R.string.voice_choose);
        PackageManager pm = getPackageManager();
        for (String pkg : VoiceActions.SEARCH_APPS) {
            if (!actions.installed(pkg)) continue;
            String where = VoiceActions.TV_APP.equals(pkg) ? getString(R.string.voice_tv_programme) : actions.label(pkg);
            Drawable icon = null;
            try {
                icon = pm.getApplicationIcon(pkg);
            } catch (PackageManager.NameNotFoundException ignored) {
            }
            addRow(icon, true, getString(R.string.voice_search_in, where), () -> {
                if (start(actions.searchIntent(pkg, q))) finish();
            });
        }
        if (maybeApp != null) {
            addRow(getDrawable(R.drawable.ic_apps), false, getString(R.string.voice_find_app, maybeApp), () -> {
                if (start(VoiceActions.marketSearch(maybeApp))) finish();
            });
        }
        rows.setVisibility(rows.getChildCount() > 0 ? View.VISIBLE : View.GONE);
        if (rows.getChildCount() > 0) rows.getChildAt(0).requestFocus();
    }

    /** @param appIcon an app's own icon, which keeps its colours instead of taking the row's white/black tint */
    private void addRow(Drawable icon, boolean appIcon, String title, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ImageView iv = row.findViewById(R.id.icon);
        iv.setImageDrawable(icon);
        if (appIcon) iv.setImageTintList(null);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        row.findViewById(R.id.status).setVisibility(View.GONE);
        row.findViewById(R.id.dot).setVisibility(View.GONE);
        row.setOnClickListener(v -> action.run());
        rows.addView(row);
    }

    /** "X is not installed" with its Play Store name and logo, fetched in the background; pkg null: not on Play. */
    private void showMissing(String pkg, String name) {
        clearResults();
        asDialog(true);
        ((TextView) findViewById(R.id.missingTitle)).setText(getString(R.string.voice_not_installed, name));
        TextView sub = findViewById(R.id.missingSub);
        ImageView icon = findViewById(R.id.missingIcon);
        icon.setImageDrawable(null);
        View play = findViewById(R.id.missingPlay);
        play.setOnClickListener(v -> {
            if (start(pkg != null ? VoiceActions.marketDetails(pkg) : VoiceActions.marketSearch(name))) finish();
        });
        missing.setVisibility(View.VISIBLE);
        play.requestFocus();
        if (pkg == null) {
            sub.setText(R.string.voice_not_on_play);
            return;
        }
        sub.setText(R.string.voice_looking_up);
        String language = Locale.getDefault().getLanguage();
        new Thread(() -> {
            PlayStoreInfo info = PlayStoreInfo.fetch(pkg, language);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                sub.setText(info == null ? getString(R.string.voice_not_on_play)
                        : info.title != null ? info.title : name);
                if (info != null && info.icon != null) icon.setImageBitmap(info.icon);
            });
        }, "play-info").start();
    }

    private void clearResults() {
        rows.removeAllViews();
        rows.setVisibility(View.GONE);
        missing.setVisibility(View.GONE);
        asDialog(false);
    }

    /**
     * true: the small "not installed" dialog in the middle of the screen, sized to its content, with no microphone or
     * words; false: the panel at the bottom with them.
     */
    private void asDialog(boolean dialog) {
        View panel = findViewById(R.id.panel);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) panel.getLayoutParams();
        float d = getResources().getDisplayMetrics().density;
        lp.width = dialog ? ViewGroup.LayoutParams.WRAP_CONTENT : Math.round(760 * d);
        lp.gravity = dialog ? Gravity.CENTER : Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.bottomMargin = dialog ? 0 : Math.round(48 * d);
        panel.setLayoutParams(lp);
        findViewById(R.id.header).setVisibility(dialog ? View.GONE : View.VISIBLE);
    }

    private boolean start(Intent i) {
        if (i == null) return false;
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    private void hideKeyboard() {
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(query.getWindowToken(), 0);
    }
}
