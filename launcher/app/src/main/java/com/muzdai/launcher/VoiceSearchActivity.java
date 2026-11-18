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

import java.util.ArrayList;
import java.util.List;
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
    /** Home's plan for it (pack()), so the AI is not asked twice; without it the panel works the request out. */
    static final String EXTRA_PLAN = "plan";

    private EditText query;
    private TextView hint, answer;
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
        answer = findViewById(R.id.answer);
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
        Bundle packed = getIntent().getBundleExtra(EXTRA_PLAN);
        if (given != null && packed != null) {
            query.setText(given);
            apply(unpack(packed));
        } else if (given != null && !given.trim().isEmpty()) {
            query.setText(given);
            handle(given);
        } else {
            // After the window is up: before that the text line, the first focusable view, took focus.
            mic.post(mic::requestFocus);
            listen();
        }
    }

    /** Voice search again while the panel is up (singleTop): listen again; words given with it are acted on. */
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
        MainActivity.answerShowing(false);
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
        clearResults();
        String seen = MainActivity.onScreenAnswer(this, text);
        if (seen != null) {
            showAnswer(seen);
            return;
        }
        hint.setText(R.string.voice_thinking);
        new Thread(() -> {
            VoiceActions.Plan p = actions.inApp(MediaListener.playingApp(getApplicationContext())).planWithAi(text);
            runOnUiThread(() -> {
                if (!isFinishing() && !isDestroyed()) apply(p);
            });
        }, "voice-ai").start();
    }

    /** A CARDS, CHOICES, MISSING or ANSWER plan as extras, for Home to hand over; START and PLAY never need the panel. */
    static Bundle pack(VoiceActions.Plan p) {
        Bundle b = new Bundle();
        b.putString("kind", p.kind.name());
        b.putString("query", p.query);
        b.putString("maybeApp", p.maybeApp);
        b.putString("pkg", p.pkg);
        b.putString("name", p.name);
        if (p.cards != null) {
            ArrayList<Bundle> cards = new ArrayList<>();
            for (ContentSearch.Card c : p.cards) {
                Bundle cb = new Bundle();
                cb.putString("pkg", c.pkg);
                cb.putString("uri", c.uri);
                cb.putString("title", c.title);
                cb.putString("subtitle", c.subtitle);
                cb.putString("image", c.image);
                cb.putString("click", c.click);
                cb.putBoolean("launchFirst", c.launchFirst);
                cards.add(cb);
            }
            b.putParcelableArrayList("cards", cards);
        }
        return b;
    }

    @SuppressWarnings("deprecation")
    private static VoiceActions.Plan unpack(Bundle b) {
        VoiceActions.Plan p = VoiceActions.Plan.of(VoiceActions.Plan.Kind.valueOf(b.getString("kind")), null,
                b.getString("query"), b.getString("maybeApp"), b.getString("pkg"), b.getString("name"));
        ArrayList<Bundle> cards = b.getParcelableArrayList("cards");
        if (cards != null) {
            p.cards = new ArrayList<>();
            for (Bundle cb : cards) {
                p.cards.add(new ContentSearch.Card(cb.getString("pkg"), cb.getString("uri"), cb.getString("title"),
                        cb.getString("subtitle"), cb.getString("image"), cb.getString("click"), cb.getBoolean("launchFirst")));
            }
        }
        return p;
    }

    private void apply(VoiceActions.Plan p) {
        switch (p.kind) {
            case START:
                if (start(p.intent)) finish();
                return;
            case PLAY:
                VoiceRun.play(this, p.card);
                finish();
                return;
            case CARDS:
                showCards(p.cards);
                return;
            case MISSING:
                showMissing(p.pkg, p.name);
                return;
            case CLOSE:
                VoiceRun.close(this, p.pkg);
                finish();
                return;
            case ANSWER:
                showAnswer(p.name);
                return;
            default:
                showChoices(p.query, p.maybeApp);
        }
    }

    /** The answer balloon's smallest size, as the voice panel was with a one-line answer. */
    private static final int ANSWER_MIN_WIDTH_DP = 760, ANSWER_MIN_HEIGHT_DP = 130;

    /**
     * A question's answer alone in a balloon (no microphone, no question), centred in Home's free space between the
     * weather and the dock (MainActivity.answerSpace); the focused app's name above the dock is hidden meanwhile.
     */
    private void showAnswer(String text) {
        clearResults();
        hint.setText("");
        answer.setText(text);
        answer.setVisibility(View.VISIBLE);
        asAnswer(true);
    }

    /** true: the answer balloon; false: back to the panel (asDialog). */
    private void asAnswer(boolean on) {
        View panel = findViewById(R.id.panel);
        float d = getResources().getDisplayMetrics().density;
        findViewById(R.id.header).setVisibility(on ? View.GONE : View.VISIBLE);
        LinearLayout.LayoutParams alp = (LinearLayout.LayoutParams) answer.getLayoutParams();
        alp.topMargin = on ? 0 : Math.round(14 * d);
        answer.setLayoutParams(alp);
        answer.setGravity(on ? Gravity.CENTER_VERTICAL : Gravity.START);
        // An "info" circle at the left, centred by height (a compound drawable is), the answer after a gap.
        answer.setCompoundDrawablesRelativeWithIntrinsicBounds(on ? R.drawable.answer_info : 0, 0, 0, 0);
        answer.setCompoundDrawablePadding(on ? Math.round(20 * d) : 0);
        panel.setMinimumHeight(on ? Math.round(ANSWER_MIN_HEIGHT_DP * d) : 0);
        answer.setMinimumHeight(on ? Math.round((ANSWER_MIN_HEIGHT_DP - 36) * d) : 0);   // less the panel's padding
        MainActivity.answerShowing(on);
        if (!on) return;
        int[] space = MainActivity.answerSpace();
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) panel.getLayoutParams();
        lp.width = Math.round(ANSWER_MIN_WIDTH_DP * d);
        if (space == null) {
            lp.gravity = Gravity.CENTER;   // Home not laid out: the middle of the screen
            lp.topMargin = 0;
            lp.bottomMargin = 0;
            panel.setLayoutParams(lp);
            return;
        }
        int room = space[1] - space[0];
        answer.setMaxHeight(Math.max(Math.round(40 * d), room - Math.round(36 * d)));
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.bottomMargin = 0;
        lp.topMargin = space[0];
        panel.setLayoutParams(lp);
        // Centred in the space once the balloon knows its height.
        panel.post(() -> {
            FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) panel.getLayoutParams();
            int[] screen = new int[2];
            ((View) panel.getParent()).getLocationOnScreen(screen);
            p.topMargin = space[0] - screen[1] + Math.max(0, (room - panel.getHeight()) / 2);
            panel.setLayoutParams(p);
        });
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

    /** A row of cards per source, in the order found; a card opens and starts its content. */
    private void showCards(List<ContentSearch.Card> cards) {
        clearResults();
        hint.setText(null);
        LinearLayout box = findViewById(R.id.cardsBox);
        float d = getResources().getDisplayMetrics().density;
        View first = null;
        int groups = 0;
        String group = null;
        LinearLayout row = null;
        for (ContentSearch.Card c : cards) {
            if (!c.pkg.equals(group)) {
                group = c.pkg;
                groups++;
                TextView header = new TextView(this);
                header.setText(actions.label(c.pkg).toUpperCase(Locale.getDefault()));
                header.setTextColor(0xB3FFFFFF);
                header.setTextSize(11f);
                header.setLetterSpacing(0.06f);
                header.setPadding(Math.round(4 * d), Math.round(groups > 1 ? 10 * d : 0), 0, Math.round(4 * d));
                box.addView(header);
                android.widget.HorizontalScrollView scroll = new android.widget.HorizontalScrollView(this);
                scroll.setHorizontalScrollBarEnabled(false);
                scroll.setClipToPadding(false);
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                scroll.addView(row);
                box.addView(scroll);
            }
            View v = cardView(c, d);
            row.addView(v);
            if (first == null) first = v;
        }
        View scroll = findViewById(R.id.cardsScroll);
        ViewGroup.LayoutParams lp = scroll.getLayoutParams();
        // Two rows fit the 540dp screen with the words above them; more scroll.
        lp.height = groups > 2 ? Math.round(360 * d) : ViewGroup.LayoutParams.WRAP_CONTENT;
        scroll.setLayoutParams(lp);
        scroll.setVisibility(View.VISIBLE);
        if (first != null) first.requestFocus();
    }

    /** A card: 16:9 for YouTube, square for covers and posters, 112dp tall; its title and subtitle under it. */
    private View cardView(ContentSearch.Card c, float d) {
        int h = Math.round(112 * d);
        int w = VoiceActions.YOUTUBE.equals(c.pkg) ? Math.round(200 * d) : h;
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setFocusable(true);
        v.setClickable(true);
        v.setBackgroundResource(R.drawable.voice_card_bg);
        int pad = Math.round(6 * d);
        v.setPadding(pad, pad, pad, pad);
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setBackgroundColor(0x22FFFFFF);
        img.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), 10 * d);
            }
        });
        img.setClipToOutline(true);
        v.addView(img, new LinearLayout.LayoutParams(w, h));
        CardImages.load(img, c.image);
        TextView title = new TextView(this);
        title.setText(c.title);
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(12f);
        title.setMaxLines(2);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.topMargin = Math.round(4 * d);
        v.addView(title, tp);
        if (c.subtitle != null && !c.subtitle.isEmpty()) {
            TextView sub = new TextView(this);
            sub.setText(c.subtitle);
            sub.setTextColor(0x99FFFFFF);
            sub.setTextSize(10f);
            sub.setSingleLine(true);
            sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
            v.addView(sub, new LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(Math.round(8 * d));
        v.setLayoutParams(lp);
        v.setOnClickListener(x -> {
            VoiceRun.play(this, c);
            finish();
        });
        return v;
    }

    private void clearResults() {
        answer.setVisibility(View.GONE);
        rows.removeAllViews();
        rows.setVisibility(View.GONE);
        ((LinearLayout) findViewById(R.id.cardsBox)).removeAllViews();
        findViewById(R.id.cardsScroll).setVisibility(View.GONE);
        missing.setVisibility(View.GONE);
        asDialog(false);
        asAnswer(false);
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
        lp.topMargin = 0;
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
