package com.muzdai.launcher;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class VoiceCloseTest {
    @Test public void closeFindsTheNamedAppOrNone() {
        assertEquals("", VoiceActions.closeTarget("закрой"));
        assertEquals("", VoiceActions.closeTarget("Закрой плеер"));
        assertEquals("", VoiceActions.closeTarget("закрой активное окно"));
        assertEquals("кобуз", VoiceActions.closeTarget("закрой кобуз"));
        assertEquals("ютуб", VoiceActions.closeTarget("выйди из ютуб"));
        assertNull(VoiceActions.closeTarget("включи закрой глаза"));
        assertNull(VoiceActions.closeTarget("закройщик"));
    }
}
