package com.muzdai.launcher;

import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The ticker's own sources, for when Google News refuses (it answers a shared VPN address with 429 for hours):
 * Ukrainian and Western outlets' Russian-language RSS feeds, newest first, at most two headlines from one outlet.
 * Pure text, tested on the JVM.
 */
final class NewsFeeds {
    private NewsFeeds() { }

    /** Address and the name shown after a headline. */
    static final String[][] FEEDS = {
            {"https://rss.dw.com/xml/rss-ru-all", "DW"},
            {"https://feeds.bbci.co.uk/russian/rss.xml", "BBC News Русская служба"},
            {"https://www.pravda.com.ua/rus/rss/view_news/", "Украинская правда"},
            {"https://www.rbc.ua/static/rss/all.rus.rss.xml", "РБК-Украина"},
            {"https://www.svoboda.org/api/", "Радио Свобода"},
            {"https://zn.ua/rss/full.rss", "Зеркало недели"},
            {"https://www.obozrevatel.com/rss.xml", "Обозреватель"},
    };

    static final class Item {
        final String title, source;
        final long time;

        Item(String title, String source, long time) {
            this.title = title;
            this.source = source;
            this.time = time;
        }
    }

    private static final Pattern ITEM = Pattern.compile("<item[ >].*?</item>", Pattern.DOTALL);
    private static final Pattern TITLE = Pattern.compile("<title>(.*?)</title>", Pattern.DOTALL);
    private static final Pattern DATE = Pattern.compile("<pubDate>(.*?)</pubDate>", Pattern.DOTALL);

    static List<Item> parse(String xml, String source) {
        List<Item> out = new ArrayList<>();
        Matcher m = ITEM.matcher(xml == null ? "" : xml);
        while (m.find()) {
            String item = m.group();
            Matcher t = TITLE.matcher(item);
            if (!t.find()) continue;
            String title = clean(t.group(1));
            if (title.isEmpty()) continue;
            Matcher d = DATE.matcher(item);
            out.add(new Item(title, source, d.find() ? time(d.group(1).trim()) : 0));
        }
        return out;
    }

    /** The newest, at most perSource from one outlet, max in all. */
    static List<Item> merge(List<Item> all, int max, int perSource) {
        List<Item> sorted = new ArrayList<>(all);
        Collections.sort(sorted, (a, b) -> Long.compare(b.time, a.time));
        Map<String, Integer> count = new HashMap<>();
        List<Item> out = new ArrayList<>();
        for (Item i : sorted) {
            int n = count.containsKey(i.source) ? count.get(i.source) : 0;
            if (n >= perSource) continue;
            count.put(i.source, n + 1);
            out.add(i);
            if (out.size() >= max) break;
        }
        return out;
    }

    static String clean(String s) {
        String v = s.replace("<![CDATA[", "").replace("]]>", "");
        v = v.replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&nbsp;", " ").replace("&amp;", "&");
        return v.replace(' ', ' ').replaceAll("\\s+", " ").trim();
    }

    /** RFC 822 dates as feeds write them ("Mon, 16 Nov 2026 22:00:00 GMT", "… +0300"); 0 when unreadable. */
    static long time(String s) {
        for (String f : new String[]{"EEE, dd MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy HH:mm:ss zzz", "EEE, d MMM yyyy HH:mm:ss Z"}) {
            SimpleDateFormat p = new SimpleDateFormat(f, Locale.US);
            Date d = p.parse(s, new ParsePosition(0));
            if (d != null) return d.getTime();
        }
        return 0;
    }
}
