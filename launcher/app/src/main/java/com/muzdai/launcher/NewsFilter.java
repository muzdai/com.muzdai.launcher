package com.muzdai.launcher;

import java.util.Locale;

/**
 * Keeps Russian and openly pro-Russian outlets out of the news ticker, by the outlet's address in Google News'
 * {@code <source url>}: every .ru, .su and .рф site, and the known ones elsewhere (RT, Sputnik, TASS, EADaily, RTVI,
 * Baltnews, Russian outlets' mirror domains such as lenta.news and gazeta.press, Strana, Belarus state media), and
 * Russian outlets working from abroad (Meduza, The Moscow Times, Novaya Gazeta Europe, Dozhd).
 */
final class NewsFilter {
    private NewsFilter() { }

    private static final String[] ZONES = {".ru", ".su", ".xn--p1ai", ".рф"};

    /** Sites, matched with their subdomains (www.rt.com, ru.rt.com). */
    private static final String[] SITES = {
            "rt.com", "rt.rs", "sputniknews.com", "sputniknews.cn", "sputnik.by", "sputnik.kz", "sputnik.kg",
            "sputnik.az", "sputnik.md", "sputnik.tj", "sputnik-abkhazia.info", "sputnik-ossetia.com",
            "sputnik-georgia.com", "sputnik.am", "sputniknews.uz", "sputnik-tj.com", "tass.com", "eadaily.com",
            "rtvi.com", "baltnews.com", "baltnews.ee", "baltnews.lt", "baltnews.lv", "lenta.news", "gazeta.press",
            "news-front.info", "news-front.su", "strana.ua", "strana.today", "strana.news", "ukraina.ru",
            "voennoedelo.com", "pravda.ru", "southfront.org", "belta.by", "sb.by", "ont.by", "tvr.by", "ctv.by",
            "belarus24.by", "rg.ru", "ria.ru",
            // Russian outlets abroad: the owner wants no Russian media at all.
            "meduza.io", "themoscowtimes.com", "novayagazeta.eu", "tvrain.tv", "zona.media", "istories.media",
    };

    /** True for an outlet the ticker leaves out; url is the source's address (or a bare host). */
    static boolean blocked(String url) {
        String host = host(url);
        if (host.isEmpty()) return false;
        for (String z : ZONES) if (host.endsWith(z)) return true;
        for (String s : SITES) if (host.equals(s) || host.endsWith("." + s)) return true;
        return false;
    }

    static String host(String url) {
        if (url == null) return "";
        String h = url.trim().toLowerCase(Locale.ROOT);
        int scheme = h.indexOf("://");
        if (scheme >= 0) h = h.substring(scheme + 3);
        int end = h.length();
        for (char c : new char[]{'/', ':', '?', '#'}) {
            int i = h.indexOf(c);
            if (i >= 0 && i < end) end = i;
        }
        h = h.substring(0, end);
        return h.endsWith(".") ? h.substring(0, h.length() - 1) : h;
    }
}
