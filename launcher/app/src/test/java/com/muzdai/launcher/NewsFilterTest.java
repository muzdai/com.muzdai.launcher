package com.muzdai.launcher;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NewsFilterTest {
    @Test public void russianAndProRussianOutletsAreLeftOut() {
        assertTrue(NewsFilter.blocked("https://www.fontanka.ru"));
        assertTrue(NewsFilter.blocked("https://news.rambler.ru/world/"));
        assertTrue(NewsFilter.blocked("https://www.business-gazeta.ru"));
        assertTrue(NewsFilter.blocked("https://xn--80aswg.xn--p1ai"));
        assertTrue(NewsFilter.blocked("https://lenta.news"));
        assertTrue(NewsFilter.blocked("https://eadaily.com/ru/"));
        assertTrue(NewsFilter.blocked("https://ru.rt.com"));
        assertTrue(NewsFilter.blocked("https://sputnik-georgia.com"));
        assertTrue(NewsFilter.blocked("http://www.belta.by"));
        assertFalse(NewsFilter.blocked("https://www.pravda.com.ua"));
        assertFalse(NewsFilter.blocked("https://www.dw.com"));
        assertFalse(NewsFilter.blocked("https://www.bbc.com"));
        assertFalse(NewsFilter.blocked("https://ru.themoscowtimes.com"));
        assertFalse(NewsFilter.blocked("https://smart.com"));   // ends with "rt.com" but is not RT
        assertFalse(NewsFilter.blocked(null));
        assertEquals("www.dw.com", NewsFilter.host("https://WWW.DW.com:443/ru/?x=1"));
    }
}
