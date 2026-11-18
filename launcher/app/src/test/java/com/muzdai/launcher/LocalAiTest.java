package com.muzdai.launcher;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class LocalAiTest {

    @Test public void parseKeepsTheReplyToAQuestion() {
        VoiceAi.Answer a = LocalAi.parse("{\"action\":\"answer\",\"app\":\"\",\"query\":\"курс доллара\",\"name\":\"\","
                + "\"guess\":\"\",\"kind\":\"\",\"answer\":\"Доллар стоит 2,59 лари.\"}");
        assertEquals("answer", a.action);
        assertEquals("курс доллара", a.query);
        assertEquals("Доллар стоит 2,59 лари.", a.reply);
    }

    @Test public void parseReadsCommandsAsGeminiDoes() {
        VoiceAi.Answer a = LocalAi.parse("{\"action\":\"play\",\"app\":\"com.google.android.youtube.tv\","
                + "\"query\":\"Eminem\",\"name\":\"\",\"guess\":\"\",\"kind\":\"music\",\"answer\":\"\"}");
        assertEquals("play", a.action);
        assertEquals("Eminem", a.query);
        assertEquals("music", a.kind);
        assertEquals("", a.reply);
        assertEquals("close", LocalAi.parse("{\"action\":\"close\",\"app\":\"\"}").action);
    }

    @Test public void parseRejectsAQuestionWithoutAReply() {
        assertNull(LocalAi.parse("{\"action\":\"answer\",\"query\":\"погода\",\"answer\":\"\"}"));
        assertNull(LocalAi.parse("{\"error\":\"model is loading\"}"));
    }

    @Test public void normalizeMakesABaseAddress() {
        assertEquals("http://192.168.1.247:8090", LocalAi.normalize(" 192.168.1.247:8090/ "));
        assertEquals("https://ai.example", LocalAi.normalize("https://ai.example//"));
        assertNull(LocalAi.normalize("  "));
        assertNull(LocalAi.normalize(null));
    }
}
