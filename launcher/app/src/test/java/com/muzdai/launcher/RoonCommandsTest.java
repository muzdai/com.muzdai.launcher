package com.muzdai.launcher;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class RoonCommandsTest {

    private static void is(RoonCommands.Kind kind, String arg, String spoken) {
        RoonCommands.Cmd c = RoonCommands.parse(spoken);
        assertTrue(spoken, c != null);
        assertEquals(spoken, kind, c.kind);
        assertEquals(spoken, arg, c.arg);
    }

    @Test public void playback() {
        is(RoonCommands.Kind.PAUSE, null, "пауза");
        is(RoonCommands.Kind.PAUSE, null, "Поставь на паузу");
        is(RoonCommands.Kind.STOP, null, "стоп");
        is(RoonCommands.Kind.PLAY, null, "продолжи");
        is(RoonCommands.Kind.PLAY, null, "сними с паузы");
        is(RoonCommands.Kind.NEXT, null, "дальше");
        is(RoonCommands.Kind.NEXT, null, "следующую песню");
        is(RoonCommands.Kind.NEXT, null, "вперёд");
        is(RoonCommands.Kind.PREVIOUS, null, "предыдущий трек");
        is(RoonCommands.Kind.PREVIOUS, null, "назад");
    }

    @Test public void volume() {
        is(RoonCommands.Kind.LOUDER, null, "громче");
        is(RoonCommands.Kind.LOUDER, null, "сделай погромче");
        is(RoonCommands.Kind.LOUDER, "5", "громче на 5");
        is(RoonCommands.Kind.QUIETER, null, "чуть тише");
        is(RoonCommands.Kind.QUIETER, "2,5", "тише на 2,5 процента");
        is(RoonCommands.Kind.VOLUME, "30", "звук на 30");
        is(RoonCommands.Kind.VOLUME, "45", "громкость 45%");
        is(RoonCommands.Kind.VOLUME, "70", "поставь громкость на 70 процентов");
        is(RoonCommands.Kind.MUTE, null, "выключи звук");
        is(RoonCommands.Kind.UNMUTE, null, "верни звук");
        is(RoonCommands.Kind.WHAT_VOLUME, null, "какая громкость");
    }

    @Test public void questionsQueueZones() {
        is(RoonCommands.Kind.WHAT_PLAYS, null, "что сейчас играет?");
        is(RoonCommands.Kind.WHAT_PLAYS, null, "что за песня");
        is(RoonCommands.Kind.RADIO, null, "включи радио по этой песне");
        is(RoonCommands.Kind.RADIO, null, "включи похожую музыку");
        is(RoonCommands.Kind.PLAY_NEXT, "come together", "включи следующим come together");
        is(RoonCommands.Kind.PLAY_NEXT, "time", "поставь time следующим");
        is(RoonCommands.Kind.QUEUE, "альбом the wall", "добавь в очередь альбом the wall");
        is(RoonCommands.Kind.QUEUE, "money", "добавь money в очередь");
        is(RoonCommands.Kind.TRANSFER, "кухню", "перенеси музыку на кухню");
        is(RoonCommands.Kind.EVERYWHERE, null, "включи везде");
        is(RoonCommands.Kind.EVERYWHERE, null, "объедини все зоны");
    }

    @Test public void roonNamedAndOtherWords() {
        RoonCommands.Cmd c = RoonCommands.parse("пауза на руне");
        assertEquals(RoonCommands.Kind.PAUSE, c.kind);
        assertTrue(c.named);
        assertTrue(RoonCommands.parse("громче через роон").named);
        assertFalse(RoonCommands.parse("громче").named);
        assertNull(RoonCommands.parse("включи come together"));
        assertNull(RoonCommands.parse("какая погода"));
        assertNull(RoonCommands.parse("открой ютуб"));
    }

    @Test public void volumeScaleIsRoundedDown() {
        // Eversolo: -100..0 dB in 0.5 dB steps.
        assertEquals(-50.0, RoonCommands.toDevice(50, -100, 0, 0.5), 1e-9);
        assertEquals(-67.0, RoonCommands.toDevice(33, -100, 0, 0.5), 1e-9);
        assertEquals(-83.0, RoonCommands.toDevice(17, -100, 0, 0.5), 1e-9);
        assertEquals(0.0, RoonCommands.toDevice(100, -100, 0, 0.5), 1e-9);
        assertEquals(-66.5, RoonCommands.toDevice(33.7, -100, 0, 0.5), 1e-9);   // 33.7 → -66.3 → down to -66.5
        assertEquals(-100.0, RoonCommands.toDevice(-5, -100, 0, 0.5), 1e-9);
        // A 0..100 device in steps of 1, and one in steps of 2.
        assertEquals(33.0, RoonCommands.toDevice(33.9, 0, 100, 1), 1e-9);
        assertEquals(32.0, RoonCommands.toDevice(33, 0, 100, 2), 1e-9);
        assertEquals(67.0, RoonCommands.toScale(-33, -100, 0), 1e-9);
        assertEquals("67", RoonCommands.say(67));
        assertEquals("67,5", RoonCommands.say(67.5));
        // Louder by 0.5 from -33 dB.
        assertEquals(-32.5, RoonCommands.toDevice(RoonCommands.toScale(-33, -100, 0) + 0.5, -100, 0, 0.5), 1e-9);
    }

    @Test public void zoneBySpokenName() throws Exception {
        JSONArray zones = new JSONArray()
                .put(new JSONObject().put("zone_id", "z1").put("display_name", "Eversolo").put("outputs", new JSONArray()))
                .put(new JSONObject().put("zone_id", "z2").put("display_name", "Кухня").put("outputs", new JSONArray()));
        assertEquals("z2", RoonPlay.zoneNamed(zones, "кухню").getString("zone_id"));
        assertEquals("z2", RoonPlay.zoneNamed(zones, "кухне").getString("zone_id"));
        assertEquals("z1", RoonPlay.zoneNamed(zones, "eversolo").getString("zone_id"));
        assertNull(RoonPlay.zoneNamed(zones, "первый канал"));
    }
}
