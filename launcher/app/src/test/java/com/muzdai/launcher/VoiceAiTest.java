package com.muzdai.launcher;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class VoiceAiTest {

    @Test public void parseReadsTheJsonEvenInsideFences() {
        VoiceAi.Answer a = VoiceAi.parse("```json\n{\"action\":\"play\",\"app\":\"com.google.android.youtube.tv\","
                + "\"query\":\"Joe Cocker With A Little Help From My Friends\",\"name\":\"\",\"guess\":\"\"}\n```");
        assertEquals("play", a.action);
        assertEquals("com.google.android.youtube.tv", a.app);
        assertEquals("Joe Cocker With A Little Help From My Friends", a.query);
        assertEquals("other", a.kind);   // not given: other
        assertEquals("movie", VoiceAi.parse("{\"action\":\"play\",\"kind\":\"Movie\"}").kind);
    }

    @Test public void parseKeepsTheNameAndGuessOfAnAppTheTvLacks() {
        VoiceAi.Answer a = VoiceAi.parse("{\"action\":\"OPEN\",\"app\":\"\",\"query\":\"\",\"name\":\"Кинопоиск\","
                + "\"guess\":\"ru.kinopoisk.tv\"}");
        assertEquals("open", a.action);
        assertEquals("", a.app);
        assertEquals("Кинопоиск", a.name);
        assertEquals("ru.kinopoisk.tv", a.guess);
    }

    @Test public void parseRejectsAnythingElse() {
        assertNull(VoiceAi.parse("I am not sure"));
        assertNull(VoiceAi.parse("{\"action\":\"dance\"}"));
        assertNull(VoiceAi.parse("{broken"));
        assertNull(VoiceAi.parse(null));
    }

    @Test public void promptListsTheAppsAndTheDefaultMusicApp() {
        Map<String, String> apps = new LinkedHashMap<>();
        apps.put("YouTube", "com.google.android.youtube.tv");
        apps.put("Deezer", "deezer.android.app");
        String p = VoiceAi.prompt("включи джо кокера", apps, "deezer.android.app");
        assertTrue(p.contains("- YouTube (com.google.android.youtube.tv)"));
        assertTrue(p.contains("Музыка по умолчанию: deezer.android.app"));
        assertTrue(p.endsWith("Команда: включи джо кокера"));
        // Films and series with no app named are left to the launcher (Emby, then ALPAC), not to any installed app.
        assertTrue(p.contains("для фильмов и сериалов — пусто"));
    }

    @Test public void fastNewFlashModelsComeFirst() {
        List<String> order = VoiceAi.order(Arrays.asList("gemini-2.5-pro", "gemini-2.5-flash", "gemini-3.5-flash-lite",
                "gemini-2.5-flash-lite", "gemini-3.5-flash-preview", "gemini-2.5-flash-image", "embedding-001"));
        assertEquals(Arrays.asList("gemini-3.5-flash-lite", "gemini-2.5-flash-lite", "gemini-3.5-flash-preview",
                "gemini-2.5-flash"), order);
    }
}
