package com.muzdai.launcher;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class VoiceCommandTest {
    private static final Map<String, String> INSTALLED = new HashMap<>();
    static {
        INSTALLED.put("YouTube", "com.google.android.youtube.tv");
        INSTALLED.put("Emby", "tv.emby.embyatv");
        INSTALLED.put("TorrServe MatriX", "ru.yourok.torrserve");
    }
    private static final Predicate<String> IS_APP = s -> AppNames.find(s, INSTALLED) != null;

    private static VoiceCommand parse(String s) {
        return VoiceCommand.parse(s, IS_APP);
    }

    @Test public void openVerbWithAnAppOpensIt() {
        VoiceCommand c = parse("Запусти YouTube");
        assertEquals(VoiceCommand.Kind.OPEN, c.kind);
        assertEquals("youtube", c.app);
        assertEquals(VoiceCommand.Kind.OPEN, parse("открой эмби").kind);
        assertEquals(VoiceCommand.Kind.OPEN, parse("включи спотифай").kind);
        assertEquals(VoiceCommand.Kind.OPEN, parse("ютуб").kind);   // just the name
    }

    @Test public void somethingOnAnAppSearchesThere() {
        VoiceCommand c = parse("Включи Джо Кокера на ютубе!");
        assertEquals(VoiceCommand.Kind.IN_APP, c.kind);
        assertEquals("ютубе", c.app);
        assertEquals("джо кокера", c.query);
        assertTrue(c.play);
        VoiceCommand s = parse("найди битлз в спотифай");
        assertEquals(VoiceCommand.Kind.IN_APP, s.kind);
        assertEquals("битлз", s.query);
        assertFalse(s.play);
    }

    @Test public void tvQuestionsGoToTheTvApp() {
        assertEquals(VoiceCommand.Kind.TV, parse("что сейчас идёт на первом").kind);
        assertEquals(VoiceCommand.Kind.TV, parse("включи канал Россия 1").kind);
        assertEquals(VoiceCommand.Kind.TV, parse("когда матч Реала").kind);
        assertEquals("Когда матч Реала", parse("Когда матч Реала").query);
    }

    @Test public void anythingElseIsASearch() {
        VoiceCommand c = parse("найди фильм матрица");
        assertEquals(VoiceCommand.Kind.SEARCH, c.kind);
        assertEquals("фильм матрица", c.query);
        assertNull(c.maybeApp);
        // "в" without an app after it stays part of the query.
        VoiceCommand t = parse("включи песни в машину");
        assertEquals(VoiceCommand.Kind.SEARCH, t.kind);
        assertEquals("песни в машину", t.query);
    }

    @Test public void aShortRestAfterAnOpenVerbMayBeAnAppTheTvLacks() {
        VoiceCommand c = parse("запусти лампу");
        assertEquals(VoiceCommand.Kind.SEARCH, c.kind);
        assertEquals("лампу", c.maybeApp);
        assertEquals("Lampa", AppNames.notOnPlay(c.maybeApp));
    }

    @Test public void normalizeDropsPunctuationAndYo() {
        assertEquals("что идет на первом", VoiceCommand.normalize("  Что идёт — на «Первом»?! "));
    }
}
