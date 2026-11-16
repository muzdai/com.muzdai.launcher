package com.muzdai.launcher;

import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class AppNamesTest {
    private static final Map<String, String> NONE = Collections.emptyMap();

    @Test public void russianSpellingsOfKnownApps() {
        assertEquals("com.google.android.youtube.tv", AppNames.find("ютуб", NONE));
        assertEquals("com.google.android.youtube.tv", AppNames.find("ютубе", NONE));
        assertEquals("com.google.android.youtube.tvmusic", AppNames.find("ютуб музыка", NONE));
        assertEquals("com.spotify.tv.android", AppNames.find("Спотифай", NONE));
        assertEquals("deezer.android.app", AppNames.find("дизере", NONE));
        assertEquals("com.qobuz.music", AppNames.find("кобуз", NONE));
        assertEquals("cc.alcopa.tv", AppNames.find("алпак", NONE));
        assertEquals("com.android.vending", AppNames.find("плей маркет", NONE));
        assertEquals(AppNames.SETTINGS, AppNames.find("настройки", NONE));
    }

    @Test public void installedLabelsMatchAsWrittenInLatinOrByTheirStart() {
        Map<String, String> installed = new HashMap<>();
        installed.put("Kinopoisk HD", "ru.kinopoisk");
        installed.put("Smart YouTube", "com.liskovsoft.smarttubetv");
        installed.put("Х-плор", "com.example.xplore");
        assertEquals("ru.kinopoisk", AppNames.find("кинопоиск", installed));   // Latin form, start of the label
        assertEquals("com.example.xplore", AppNames.find("х плор", installed));
        assertNull(AppNames.find("матрица", installed));
    }

    @Test public void caseEndingsComeOff() {
        assertEquals("лампа", AppNames.forms("лампу")[2]);
        assertEquals("ютуб", AppNames.forms("ютубе")[1]);
    }

    @Test public void latinTransliteration() {
        assertEquals("embi", AppNames.latin("эмби"));
        assertEquals("netfliks", AppNames.latin("нетфликс"));
    }
}
