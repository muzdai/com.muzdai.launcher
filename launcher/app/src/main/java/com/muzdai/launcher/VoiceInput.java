package com.muzdai.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Speech to text with SpeechRecognizer (the words come as they are said), started with a short beep. The beep plays
 * before the microphone opens, so the recognizer does not hear it. Main thread only.
 */
final class VoiceInput {

    interface Callback {
        /** Listening started (true) or ended (false), whatever the outcome. */
        void onListening(boolean on);
        /** Loudness while listening, about 0 to 10, for a pulse. */
        default void onLevel(float level) { }
        default void onPartial(String text) { }
        void onResult(String text);
        /** A message for the owner: nothing heard, no network, no permission and the like. */
        void onError(int messageRes, int code);
    }

    /** How long the beep plays before the recognizer starts. */
    private static final int BEEP_MS = 150;

    private final Context ctx;
    private final Callback callback;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SpeechRecognizer recognizer;
    private ToneGenerator tone;
    private boolean listening;
    private final Runnable begin = this::begin;

    VoiceInput(Context ctx, Callback callback) {
        this.ctx = ctx;
        this.callback = callback;
    }

    static boolean permitted(Context ctx) {
        return ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    boolean listening() {
        return listening;
    }

    /** Beeps and listens; false when this TV cannot recognize speech or the app may not record. */
    boolean start() {
        if (listening) return true;
        if (!permitted(ctx) || !SpeechRecognizer.isRecognitionAvailable(ctx)) return false;
        listening = true;
        callback.onListening(true);
        beep();
        handler.postDelayed(begin, BEEP_MS);
        return true;
    }

    /** Stops without a result. */
    void cancel() {
        handler.removeCallbacks(begin);
        if (recognizer != null && listening) recognizer.cancel();
        finish();
    }

    void destroy() {
        cancel();
        if (recognizer != null) recognizer.destroy();
        recognizer = null;
        if (tone != null) tone.release();
        tone = null;
    }

    private void beep() {
        try {
            if (tone == null) tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 60);
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, BEEP_MS);
        } catch (RuntimeException ignored) {
            // No tone on this TV: listening goes on without it.
        }
    }

    private void begin() {
        if (!listening) return;
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(ctx);
            recognizer.setRecognitionListener(new Listener());
        }
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.getPackageName());
        try {
            recognizer.startListening(i);
        } catch (RuntimeException e) {
            finish();
            callback.onError(R.string.voice_no_recognizer, 0);
        }
    }

    private void finish() {
        if (!listening) return;
        listening = false;
        callback.onListening(false);
    }

    private final class Listener implements RecognitionListener {
        @Override public void onReadyForSpeech(Bundle params) { }
        @Override public void onBeginningOfSpeech() { }
        @Override public void onRmsChanged(float rmsdB) {
            if (listening) callback.onLevel(Math.max(0f, Math.min(10f, rmsdB)));
        }
        @Override public void onBufferReceived(byte[] buffer) { }
        @Override public void onEndOfSpeech() { }
        // After cancel() the recognizer may still answer (no match, an error): no longer listening, it is not reported.
        @Override public void onError(int error) {
            if (!listening) return;
            finish();
            callback.onError(errorText(error), error);
        }
        @Override public void onResults(Bundle results) {
            if (!listening) return;
            finish();
            String t = first(results);
            if (t == null || t.trim().isEmpty()) callback.onError(R.string.voice_err_nomatch, 0);
            else callback.onResult(t);
        }
        @Override public void onPartialResults(Bundle partial) {
            if (!listening) return;
            String t = first(partial);
            if (t != null && !t.isEmpty()) callback.onPartial(t);
        }
        @Override public void onEvent(int eventType, Bundle params) { }
    }

    private static String first(Bundle b) {
        ArrayList<String> r = b == null ? null : b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return r == null || r.isEmpty() ? null : r.get(0);
    }

    static int errorText(int e) {
        switch (e) {
            case SpeechRecognizer.ERROR_NO_MATCH:
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                return R.string.voice_err_nomatch;
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return R.string.voice_err_permission;
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
            case SpeechRecognizer.ERROR_SERVER:
                return R.string.voice_err_network;
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                return R.string.voice_err_busy;
            default:
                return R.string.voice_err_other;
        }
    }
}
