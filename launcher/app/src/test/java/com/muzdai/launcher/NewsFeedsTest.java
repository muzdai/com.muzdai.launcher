package com.muzdai.launcher;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class NewsFeedsTest {
    @Test public void readsTitlesAndDates() {
        String xml = "<rss><channel><title>Feed</title><item><title><![CDATA[Санду: Молдова &quot;не&quot; конкурирует]]></title>"
                + "<pubDate>Mon, 16 Nov 2026 23:57:00 +0200</pubDate></item>"
                + "<item><title>Second &amp; last</title><pubDate>Mon, 16 Nov 2026 22:00:00 GMT</pubDate></item></channel></rss>";
        List<NewsFeeds.Item> items = NewsFeeds.parse(xml, "UP");
        assertEquals(2, items.size());
        assertEquals("Санду: Молдова \"не\" конкурирует", items.get(0).title);
        assertEquals("Second & last", items.get(1).title);
        assertTrue(items.get(0).time < items.get(1).time);   // 21:57 GMT before 22:00 GMT
    }

    @Test public void newestFirstAtMostTwoPerOutlet() {
        List<NewsFeeds.Item> all = new ArrayList<>();
        for (int i = 0; i < 5; i++) all.add(new NewsFeeds.Item("a" + i, "A", 100 - i));
        all.add(new NewsFeeds.Item("b", "B", 50));
        List<NewsFeeds.Item> out = NewsFeeds.merge(all, 10, 2);
        assertEquals(3, out.size());
        assertEquals("a0", out.get(0).title);
        assertEquals("a1", out.get(1).title);
        assertEquals("b", out.get(2).title);
    }
}
