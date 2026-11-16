package com.muzdai.launcher;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An app's name and logo from its Play Store page, for the "not installed" dialog. Read from the page's og:title and
 * og:image, which Play keeps for link previews; no API key. Blocking: call it off the main thread.
 */
final class PlayStoreInfo {
    final String title;
    final Bitmap icon;

    private PlayStoreInfo(String title, Bitmap icon) {
        this.title = title;
        this.icon = icon;
    }

    private static final Pattern TITLE = Pattern.compile("<meta property=\"og:title\" content=\"([^\"]*)\"");
    private static final Pattern IMAGE = Pattern.compile("<meta property=\"og:image\" content=\"([^\"]*)\"");

    /** The listing for pkg, or null when it has none (404) or the network fails. */
    static PlayStoreInfo fetch(String pkg, String language) {
        try {
            String html = text("https://play.google.com/store/apps/details?id=" + pkg + "&hl=" + language);
            if (html == null) return null;
            String title = title(html);
            Matcher image = IMAGE.matcher(html);
            Bitmap icon = image.find() ? bitmap(sized(unescape(image.group(1)), 256)) : null;
            return new PlayStoreInfo(title, icon);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * og:title without Play's own part, which comes first or last by language: "Приложения в Google Play – Spotify:
     * музыка и подкасты", "Spotify – Apps on Google Play". Null when the page has none.
     */
    static String title(String html) {
        Matcher m = TITLE.matcher(html);
        if (!m.find()) return null;
        StringBuilder out = new StringBuilder();
        for (String part : unescape(m.group(1)).split(" – ")) {
            if (part.contains("Google Play")) continue;
            if (out.length() > 0) out.append(" – ");
            out.append(part.trim());
        }
        return out.length() == 0 ? null : out.toString();
    }

    /** A googleusercontent image URL asked for at this size: its "=…" options replaced by "=s<size>". */
    static String sized(String url, int size) {
        int slash = url.lastIndexOf('/');
        int eq = url.indexOf('=', Math.max(slash, 0));
        return (eq > 0 ? url.substring(0, eq) : url) + "=s" + size;
    }

    static String unescape(String s) {
        return s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<")
                .replace("&gt;", ">");
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0");
        return c;
    }

    private static String text(String url) throws IOException {
        HttpURLConnection c = open(url);
        try {
            if (c.getResponseCode() != 200) return null;
            return new String(read(c.getInputStream()), StandardCharsets.UTF_8);
        } finally {
            c.disconnect();
        }
    }

    private static Bitmap bitmap(String url) throws IOException {
        HttpURLConnection c = open(url);
        try {
            if (c.getResponseCode() != 200) return null;
            byte[] b = read(c.getInputStream());
            return BitmapFactory.decodeByteArray(b, 0, b.length);
        } finally {
            c.disconnect();
        }
    }

    private static byte[] read(InputStream in) throws IOException {
        try (InputStream s = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[16384];
            for (int n; (n = s.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }
}
