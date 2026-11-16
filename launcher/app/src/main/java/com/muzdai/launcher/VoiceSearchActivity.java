package com.muzdai.launcher;

import android.app.Activity;
import android.app.SearchManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Voice search over Home: opened by the microphone button in the top bar or by holding Right on Home. Listens at once
 * (SpeechRecognizer, so the words appear as they are said), then acts on the request (VoiceCommand): opens a named app,
 * searches inside one, hands TV programme questions to com.tcl.tv, offers where to search, or shows that an app is not
 * installed with its Play Store logo.
 */
public final class VoiceSearchActivity extends Activity {

    private static final int REQ_MIC = 1;
    /** A request already in words (a test over adb, or another part of the launcher): acted on without listening. */
    static final String EXTRA_QUERY = "query";
    static final String TV_APP = "com.tcl.tv";
    private static final String YOUTUBE = "com.google.android.youtube.tv";
    private static final String YOUTUBE_MUSIC = "com.google.android.youtube.tvmusic";
    private static final String SPOTIFY = "com.spotify.tv.android";
    private static final String ALPAC = "cc.alcopa.tv";
    private static final String EMBY = "tv.emby.embyatv";
    private static final String DEEZER = "deezer.android.app";
    /** Where "search in …" rows are offered for a plain search, in this order, when installed. */
    private static final String[] SEARCH_APPS = {YOUTUBE, ALPAC, EMBY, SPOTIFY, DEEZER, TV_APP};

    private EditText query;
    private TextView hint;
    private ImageView mic;
    private LinearLayout rows, missing;
    private SpeechRecognizer recognizer;
    private boolean listening;
    /** The microphone permission prompt is up: its window stops this one, which must not close the panel. */
    private boolean askingPermission;
    /** Launchable apps on the TV: label to package. */
    private final Map<String, String> labels = new HashMap<>();

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
        loadLabels();
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

    /** Holding Right here, as on Home, listens again. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT && event.getAction() == KeyEvent.ACTION_DOWN
                && event.getRepeatCount() == 1) {
            if (!listening) listen();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Nothing of the panel stays behind an app it opened, or after Home.
        if (!isChangingConfigurations() && !askingPermission) finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (recognizer != null) recognizer.destroy();
    }

    // ---- listening -------------------------------------------------------------------------------

    private void listen() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            askingPermission = true;
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            hint.setText(R.string.voice_no_recognizer);
            return;
        }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(new Listener());
        }
        clearResults();
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        setListening(true);
        hint.setText(R.string.voice_starting);
        try {
            recognizer.startListening(i);
        } catch (RuntimeException e) {
            setListening(false);
            hint.setText(R.string.voice_no_recognizer);
        }
    }

    private void stopListening() {
        if (recognizer != null && listening) recognizer.cancel();
        setListening(false);
    }

    private void setListening(boolean on) {
        listening = on;
        mic.setActivated(on);
        if (!on) {
            mic.setScaleX(1f);
            mic.setScaleY(1f);
        }
    }

    private final class Listener implements RecognitionListener {
        @Override public void onReadyForSpeech(Bundle params) { hint.setText(R.string.voice_listening); }
        @Override public void onBeginningOfSpeech() { }
        @Override public void onRmsChanged(float rmsdB) {
            float s = 1f + Math.max(0f, Math.min(10f, rmsdB)) / 40f;   // a gentle pulse with the voice
            mic.setScaleX(s);
            mic.setScaleY(s);
        }
        @Override public void onBufferReceived(byte[] buffer) { }
        @Override public void onEndOfSpeech() { hint.setText(R.string.voice_recognizing); }
        @Override public void onError(int error) {
            setListening(false);
            hint.setText(errorText(error));
        }
        @Override public void onResults(Bundle results) {
            setListening(false);
            String t = first(results);
            if (t == null || t.trim().isEmpty()) {
                hint.setText(R.string.voice_err_nomatch);
                return;
            }
            query.setText(t);
            handle(t);
        }
        @Override public void onPartialResults(Bundle partial) {
            String t = first(partial);
            if (t != null && !t.isEmpty()) query.setText(t);
        }
        @Override public void onEvent(int eventType, Bundle params) { }
    }

    private static String first(Bundle b) {
        ArrayList<String> r = b == null ? null : b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return r == null || r.isEmpty() ? null : r.get(0);
    }

    private String errorText(int e) {
        switch (e) {
            case SpeechRecognizer.ERROR_NO_MATCH:
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                return getString(R.string.voice_err_nomatch);
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return getString(R.string.voice_err_permission);
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
            case SpeechRecognizer.ERROR_SERVER:
                return getString(R.string.voice_err_network);
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                return getString(R.string.voice_err_busy);
            default:
                return getString(R.string.voice_err_other, e);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQ_MIC) return;
        askingPermission = false;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) listen();
        else hint.setText(R.string.voice_err_permission);
    }

    // ---- acting on the request -------------------------------------------------------------------

    private void handle(String text) {
        stopListening();
        hideKeyboard();
        if (text == null || text.trim().isEmpty()) return;
        VoiceCommand c = VoiceCommand.parse(text, s -> AppNames.find(s, labels) != null);
        switch (c.kind) {
            case OPEN: {
                String pkg = AppNames.find(c.app, labels);
                if (installed(pkg)) {
                    if (start(openIntent(pkg))) finish();
                } else {
                    showMissing(pkg, c.app);
                }
                return;
            }
            case IN_APP: {
                String pkg = AppNames.find(c.app, labels);
                if (installed(pkg)) {
                    if (start(searchIntent(pkg, c.query))) finish();
                } else {
                    showMissing(pkg, c.app);
                }
                return;
            }
            case TV:
                if (installed(TV_APP) && start(tvIntent(c.query))) {
                    finish();
                    return;
                }
                showChoices(c.query, null);
                return;
            default:
                String notOnPlay = c.maybeApp == null ? null : AppNames.notOnPlay(c.maybeApp);
                if (notOnPlay != null) {
                    showMissing(null, notOnPlay);
                    return;
                }
                showChoices(c.query, c.maybeApp);
        }
    }

    /** "Search in …" rows for the installed apps that take a query, and Google Play for what may be an app. */
    private void showChoices(String q, String maybeApp) {
        clearResults();
        hint.setText(R.string.voice_choose);
        PackageManager pm = getPackageManager();
        for (String pkg : SEARCH_APPS) {
            if (!installed(pkg)) continue;
            String where = TV_APP.equals(pkg) ? getString(R.string.voice_tv_programme) : label(pkg);
            Drawable icon = null;
            try {
                icon = pm.getApplicationIcon(pkg);
            } catch (PackageManager.NameNotFoundException ignored) {
            }
            addRow(icon, true, getString(R.string.voice_search_in, where), () -> {
                if (start(searchIntent(pkg, q))) finish();
            });
        }
        if (maybeApp != null) {
            addRow(getDrawable(R.drawable.ic_apps), false, getString(R.string.voice_find_app, maybeApp), () -> {
                if (start(marketSearch(maybeApp))) finish();
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
    private void showMissing(String pkg, String spoken) {
        clearResults();
        hint.setText(null);
        String name = pkg != null && AppNames.name(pkg) != null ? AppNames.name(pkg) : capitalize(spoken);
        ((TextView) findViewById(R.id.missingTitle)).setText(getString(R.string.voice_not_installed, name));
        TextView sub = findViewById(R.id.missingSub);
        ImageView icon = findViewById(R.id.missingIcon);
        icon.setImageDrawable(null);
        View play = findViewById(R.id.missingPlay);
        play.setOnClickListener(v -> {
            if (start(pkg != null ? marketDetails(pkg) : marketSearch(name))) finish();
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
    }

    // ---- intents ---------------------------------------------------------------------------------

    private Intent openIntent(String pkg) {
        if (AppNames.SETTINGS.equals(pkg)) return new Intent(Settings.ACTION_SETTINGS);
        PackageManager pm = getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        return i != null ? i : pm.getLaunchIntentForPackage(pkg);
    }

    /** The app's own search with this query; an app with no way in for a query just opens. */
    private Intent searchIntent(String pkg, String q) {
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

    private static Intent marketDetails(String pkg) {
        return new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + pkg));
    }

    private static Intent marketSearch(String name) {
        return new Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=" + Uri.encode(name) + "&c=apps"));
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

    // ---- apps ------------------------------------------------------------------------------------

    private void loadLabels() {
        PackageManager pm = getPackageManager();
        List<ResolveInfo> found = new ArrayList<>(pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0));
        found.addAll(pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0));
        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null || getPackageName().equals(ri.activityInfo.packageName)) continue;
            labels.put(String.valueOf(ri.loadLabel(pm)), ri.activityInfo.packageName);
        }
    }

    private boolean installed(String pkg) {
        if (pkg == null) return false;
        if (AppNames.SETTINGS.equals(pkg)) return true;
        return openIntent(pkg) != null;
    }

    private String label(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (PackageManager.NameNotFoundException e) {
            return AppNames.name(pkg) != null ? AppNames.name(pkg) : pkg;
        }
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return "";
        return s.substring(0, 1).toUpperCase(Locale.getDefault()) + s.substring(1);
    }

    private void hideKeyboard() {
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(query.getWindowToken(), 0);
    }
}
