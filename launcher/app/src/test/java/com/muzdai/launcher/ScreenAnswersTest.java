package com.muzdai.launcher;

import org.junit.Test;

import java.time.LocalDate;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ScreenAnswersTest {
    private static final String WEATHER = "{\"current\":{\"temperature_2m\":13.4,\"weather_code\":61,\"is_day\":1},"
            + "\"daily\":{\"time\":[\"2026-11-18\",\"2026-11-19\",\"2026-11-20\",\"2026-11-21\"],"
            + "\"weather_code\":[61,63,3,0],\"temperature_2m_max\":[17.8,19.2,19,16],"
            + "\"temperature_2m_min\":[12.9,15,13,14]}}";
    private static final String RATES = "[{\"code\":\"USD\",\"quantity\":1,\"rate\":2.5928},"
            + "{\"code\":\"UAH\",\"quantity\":10,\"rate\":0.5713},{\"code\":\"RUB\",\"quantity\":100,\"rate\":3.2}]";

    private static ScreenAnswers.Facts facts(String range) {
        ScreenAnswers.Facts f = new ScreenAnswers.Facts();
        f.time = "07:40";
        f.today = LocalDate.of(2026, 11, 18);   // a Wednesday
        f.dateShown = true;
        ScreenAnswers.weatherFrom(f, WEATHER, range, "Батуми");
        ScreenAnswers.ratesFrom(f, RATES, Arrays.asList("USD", "EUR", "TRY", "UAH"));
        f.playing = "Pink Floyd — Time";
        return f;
    }

    private static String body(String spoken, ScreenAnswers.Facts f) {
        String a = ScreenAnswers.answer(spoken, f);
        if (a == null) return null;
        int nl = a.indexOf('\n');
        assertTrue(nl > 0);
        assertTrue(Arrays.asList(ScreenAnswers.RETORTS).contains(a.substring(0, nl)));
        return a.substring(nl + 1);
    }

    @Test public void timeDateAndWhatPlays() {
        ScreenAnswers.Facts f = facts("3");
        assertEquals("Сейчас 07:40.", body("Который час?", f));
        assertEquals("Сегодня среда, 18 ноября.", body("какое сегодня число", f));
        assertEquals("Сегодня среда.", body("какой сегодня день недели", f));
        assertEquals("Играет Pink Floyd — Time.", body("что сейчас играет", f));
        f.playing = null;
        assertNull(body("что сейчас играет", f));
    }

    @Test public void ratesShownToTheLariOnly() {
        ScreenAnswers.Facts f = facts("3");
        assertEquals("1 USD = 2.5928 лари.", body("какой курс доллара", f));
        assertEquals("10 UAH = 0.5713 лари.", body("сколько стоит гривна", f));
        assertNull(body("курс рубля", f));                 // not among the shown currencies
        assertNull(body("курс доллара к евро", f));        // two currencies: not on screen
    }

    @Test public void weatherOfTheCityAndDaysShown() {
        ScreenAnswers.Facts f = facts("3");
        assertEquals("В Батуми сейчас +13°, дождь; днём до +18°, ночью до +13°.", body("какая погода", f));
        assertEquals("В Батуми сейчас +13°, дождь; днём до +18°, ночью до +13°.", body("сколько градусов в батуми", f));
        assertEquals("Четверг: дождь, от +15° до +19°.", body("какая погода завтра", f));
        assertEquals("Пятница: пасмурно, от +13° до +19°.", body("будет ли дождь в пятницу", f));
        assertNull(body("какая погода в тбилиси", f));     // another city
        assertNull(body("какой ветер завтра", f));          // wind is not shown
        assertNull(body("погода в воскресенье", f));        // beyond the 3 days shown
        assertNull(body("погода на выходные", f));
        assertNull(body("какая погода завтра", facts("now")));   // only now is shown
    }

    @Test public void retortsChangeAndNeverRepeatTwiceInARow() {
        java.util.Random r = new java.util.Random(1);
        int last = -1;
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (int k = 0; k < 500; k++) {
            int i = ScreenAnswers.pick(r, last);
            assertTrue(i >= 0 && i < ScreenAnswers.RETORTS.length);
            assertTrue(i != last);
            seen.add(i);
            last = i;
        }
        assertEquals(ScreenAnswers.RETORTS.length, seen.size());
        for (String s : ScreenAnswers.RETORTS) {
            assertEquals(s.substring(0, 1).toUpperCase(), s.substring(0, 1));
            assertTrue(s, !s.substring(1).equals(s.substring(1).toUpperCase()));   // not in capitals
        }
    }

    @Test public void otherQuestionsAreNotMatched() {
        ScreenAnswers.Facts f = facts("3");
        assertNull(body("кто выиграл чемпионат мира", f));
        assertNull(body("включи come together", f));
        assertNull(body("какие новости", f));
        assertNull(ScreenAnswers.answer("который час", null));
    }
}
