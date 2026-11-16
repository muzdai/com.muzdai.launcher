package com.muzdai.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * vPro: the weather under the clock (Open-Meteo, no key) and the National Bank of Georgia's
 * official rates to the lari of up to four currencies (USD and EUR unless chosen otherwise) next to the greeting. Both are off unless fork_defaults.xml sets
 * {@code show_weather} / {@code show_nbg_rates} or the owner switches them on in Launcher settings.
 *
 * Network use is bounded: Home asks for a refresh only while it is resumed, the weather is fetched at most
 * every 30 minutes (or when the city or forecast length changes) and the rates at most every 3 hours. The
 * last answers are kept, so Home shows them at once and while the network is down. What is sent: the typed
 * city name to Open-Meteo's geocoding search, then that place's latitude and longitude; nothing to the NBG
 * but the request itself. No account, no key, no identifier.
 */
final class InfoWidgets {
    private static final String TAG = "InfoWidgets";
    static final String PREFS = "fork_widgets";
    static final String KEY_WEATHER_ON = "weather_on";
    static final String KEY_RATES_ON = "rates_on";
    /** The currencies shown next to the greeting, comma-separated, in {@link #CURRENCIES} order. */
    static final String KEY_CURRENCIES = "currencies";
    /** What the NBG list offers here; up to {@link #MAX_CURRENCIES} of them at once. */
    static final String[] CURRENCIES = {"USD", "EUR", "GBP", "TRY", "UAH", "AMD", "AZN"};
    static final int MAX_CURRENCIES = 4;
    private static final String DEFAULT_CURRENCIES = "USD,EUR";
    static final String KEY_CITY = "weather_city";
    static final String KEY_LAT = "weather_lat";
    static final String KEY_LON = "weather_lon";
    static final String KEY_RANGE = "weather_range";
    static final String KEY_NEWS_ON = "news_on";
    /** Ticker text size in sp (16-40) and speed in dp per second (20-200), set in Launcher settings. */
    static final String KEY_NEWS_SIZE = "news_size";
    static final String KEY_NEWS_SPEED = "news_speed";
    static final int NEWS_SIZE_DEFAULT = 22, NEWS_SPEED_DEFAULT = 60;
    /** Transparency of the ticker's dark band in percent (0 solid, 100 none). */
    static final String KEY_NEWS_BG = "news_bg_transparency";
    static final int NEWS_BG_DEFAULT = 50;
    private static final String KEY_NEWS = "news_titles";
    private static final String KEY_NEWS_AT = "news_at";
    /** How often the ticker fetches new headlines, minutes (Launcher settings); NEWS_EVERY_DEFAULT unless chosen. */
    static final String KEY_NEWS_EVERY = "news_every_min";
    static final int[] NEWS_EVERY = {5, 10, 15, 30, 60};
    static final int NEWS_EVERY_DEFAULT = 10;

    static int newsEveryMinutes(Context ctx) {
        int m = prefs(ctx).getInt(KEY_NEWS_EVERY, NEWS_EVERY_DEFAULT);
        for (int v : NEWS_EVERY) if (v == m) return m;
        return NEWS_EVERY_DEFAULT;
    }
    /** Google News sections, Ukrainian edition in Russian (top stories ranked by Google); "TOP" is the front page. */
    static final String[] NEWS_TOPICS = {"WORLD", "TOP", "GEORGIA", "BUSINESS", "TECHNOLOGY", "SCIENCE", "SPORTS",
            "ENTERTAINMENT", "HEALTH"};
    static final String KEY_NEWS_TOPIC = "news_topic";
    private static final String KEY_NEWS_FOR = "news_for";
    /** Part of what the cached headlines are for: a new source (the Ukrainian edition, filtered) refetches them. */
    private static final String NEWS_SOURCE = "|ua-filtered-2";

    static String newsTopic(Context ctx) {
        String t = prefs(ctx).getString(KEY_NEWS_TOPIC, "WORLD");
        for (String k : NEWS_TOPICS) if (k.equals(t)) return t;
        return "WORLD";
    }

    private static String newsUrl(String topic) {
        // The Ukrainian edition in Russian: Ukrainian, European and exiled outlets, where the Russian (and, as
        // tested, the Georgian and US) Russian-language editions are made of Russian outlets. NewsFilter does the rest.
        String edition = "hl=ru&gl=UA&ceid=UA:ru";
        // Google News has no Georgia section in the Russian edition: a search for "Грузия" over the last day.
        if ("GEORGIA".equals(topic)) return "https://news.google.com/rss/search?q=%D0%93%D1%80%D1%83%D0%B7%D0%B8%D1%8F+when:1d&" + edition;
        return "TOP".equals(topic) ? "https://news.google.com/rss?" + edition
                : "https://news.google.com/rss/headlines/section/topic/" + topic + "?" + edition;
    }

    /** The section changed in settings: fetch it now. */
    static void invalidateNews(Context ctx) {
        lastNewsTry = 0;
        prefs(ctx).edit().remove(KEY_NEWS_AT).apply();
    }
    private static long lastNewsTry;
    private static final String KEY_WEATHER_JSON = "weather_json";
    private static final String KEY_WEATHER_AT = "weather_at";
    private static final String KEY_WEATHER_FOR = "weather_for";
    private static final String KEY_RATES_JSON = "rates_json";
    private static final String KEY_RATES_AT = "rates_at";

    /** Forecast lengths offered in settings: current only, today, 3 days, a week. */
    static final String[] RANGES = {"now", "today", "3", "7"};
    static final String DEFAULT_RANGE = "3";

    private static final long WEATHER_MAX_AGE_MS = 30L * 60 * 1000;
    private static final long RATES_MAX_AGE_MS = 3L * 60 * 60 * 1000;
    private static final long RETRY_MS = 5L * 60 * 1000;
    private static final int TIMEOUT_MS = 10_000;
    private static final String NBG_URL = "https://nbg.gov.ge/gw/api/ct/monetarypolicy/currencies/en/json/";

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static long lastWeatherTry, lastRatesTry;

    private InfoWidgets() { }

    static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Weather on: the owner's switch in settings, else fork_defaults.xml show_weather. A city is needed either way. */
    static boolean weatherOn(Context ctx, ForkConfig fork) {
        SharedPreferences p = prefs(ctx);
        boolean on = p.contains(KEY_WEATHER_ON) ? p.getBoolean(KEY_WEATHER_ON, false) : fork.showWeather;
        return on && p.contains(KEY_LAT);
    }

    /** Rates on: the owner's switch in settings, else fork_defaults.xml show_nbg_rates. */
    static boolean ratesOn(Context ctx, ForkConfig fork) {
        SharedPreferences p = prefs(ctx);
        return p.contains(KEY_RATES_ON) ? p.getBoolean(KEY_RATES_ON, false) : fork.showNbgRates;
    }

    /** News ticker on: the owner's switch in settings, else fork_defaults.xml show_news. */
    static boolean newsOn(Context ctx, ForkConfig fork) {
        SharedPreferences p = prefs(ctx);
        return p.contains(KEY_NEWS_ON) ? p.getBoolean(KEY_NEWS_ON, false) : fork.showNews;
    }

    /** The top 10 headlines joined for the ticker, or null before the first answer. */
    static String newsLine(Context ctx) {
        // Headlines cached from the earlier, unfiltered source are not shown, even before new ones arrive.
        if (!prefs(ctx).getString(KEY_NEWS_FOR, "").endsWith(NEWS_SOURCE)) return null;
        String raw = prefs(ctx).getString(KEY_NEWS, null);
        if (raw == null || raw.isEmpty()) return null;
        return raw.replace("\n", "      \u2022      ");
    }

    static String range(Context ctx) {
        String r = prefs(ctx).getString(KEY_RANGE, DEFAULT_RANGE);
        for (String known : RANGES) if (known.equals(r)) return r;
        return DEFAULT_RANGE;
    }

    // ---- what Home shows ---------------------------------------------------------------------------

    /** The two weather lines (current, then the days ahead), or null when there is nothing to show yet. */
    static String[] weatherLines(Context ctx) {
        SharedPreferences p = prefs(ctx);
        String raw = p.getString(KEY_WEATHER_JSON, null);
        if (raw == null) return null;
        try {
            JSONObject json = new JSONObject(raw);
            JSONObject cur = json.getJSONObject("current");
            JSONObject daily = json.optJSONObject("daily");
            String range = range(ctx);
            boolean day = cur.optInt("is_day", 1) == 1;
            StringBuilder first = new StringBuilder();
            first.append(icon(cur.optInt("weather_code", -1), day)).append("  ")
                    .append(deg(cur.optDouble("temperature_2m")));
            if (!"now".equals(range) && daily != null) {
                JSONArray max = daily.getJSONArray("temperature_2m_max");
                JSONArray min = daily.getJSONArray("temperature_2m_min");
                first.append("   ↑").append(deg(max.optDouble(0)))
                        .append(" ↓").append(deg(min.optDouble(0)));
            }
            String city = p.getString(KEY_CITY, "");
            if (!city.isEmpty()) first.append("   ·   ").append(city);
            String second = null;
            int days = "3".equals(range) ? 3 : "7".equals(range) ? 7 : 0;
            if (days > 0 && daily != null) {
                JSONArray dates = daily.getJSONArray("time");
                JSONArray codes = daily.getJSONArray("weather_code");
                JSONArray max = daily.getJSONArray("temperature_2m_max");
                JSONArray min = daily.getJSONArray("temperature_2m_min");
                Locale locale = ctx.getResources().getConfiguration().getLocales().get(0);
                StringBuilder s = new StringBuilder();
                // Day 0 is today, already on the first line; the next `days` follow.
                for (int i = 1; i <= days && i < dates.length(); i++) {
                    String name = LocalDate.parse(dates.getString(i)).getDayOfWeek()
                            .getDisplayName(TextStyle.SHORT, locale);
                    if (s.length() > 0) s.append("     ");
                    s.append(capitalize(name, locale)).append(' ')
                            .append(icon(codes.optInt(i, -1), true)).append(' ')
                            .append(deg(max.optDouble(i))).append('/').append(deg(min.optDouble(i)));
                }
                second = s.toString();
            }
            return new String[]{first.toString(), second};
        } catch (Exception e) {
            return null;
        }
    }

    /** The chosen currencies, in {@link #CURRENCIES} order; never empty. */
    static java.util.List<String> currencies(Context ctx) {
        String saved = prefs(ctx).getString(KEY_CURRENCIES, DEFAULT_CURRENCIES);
        java.util.List<String> chosen = java.util.Arrays.asList(saved.split(","));
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String code : CURRENCIES) if (chosen.contains(code) && out.size() < MAX_CURRENCIES) out.add(code);
        if (out.isEmpty()) out.add("USD");
        return out;
    }

    static void setCurrencies(Context ctx, java.util.List<String> codes) {
        prefs(ctx).edit().putString(KEY_CURRENCIES, android.text.TextUtils.join(",", codes)).apply();
        invalidateRates(ctx);
    }

    /**
     * "USD 2.5943 ▼   EUR 2.9041 ▲" with a green ▲ and a red ▼, or null before the first answer. A currency the NBG
     * quotes per 10 or 1000 units (UAH, AMD) is shown the same way: "1000 AMD 6.8123".
     */
    static CharSequence ratesLine(Context ctx) {
        String raw = prefs(ctx).getString(KEY_RATES_JSON, null);
        if (raw == null) return null;
        try {
            JSONArray list = new JSONArray(raw);
            android.text.SpannableStringBuilder s = new android.text.SpannableStringBuilder();
            for (String code : currencies(ctx)) {
                for (int i = 0; i < list.length(); i++) {
                    JSONObject c = list.getJSONObject(i);
                    if (!code.equals(c.optString("code"))) continue;
                    int quantity = Math.max(1, c.optInt("quantity", 1));
                    double rate = c.optDouble("rate");
                    double diff = c.optDouble("diff", 0);
                    if (s.length() > 0) s.append("     ");
                    if (quantity > 1) s.append(String.valueOf(quantity)).append(' ');
                    s.append(code).append(' ').append(String.format(Locale.ROOT, "%.4f", rate));
                    if (diff != 0) {
                        s.append(' ');
                        int start = s.length();
                        s.append(diff > 0 ? "▲" : "▼");
                        s.setSpan(new android.text.style.ForegroundColorSpan(diff > 0 ? 0xFF4CD964 : 0xFFFF5A5F),
                                start, s.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                }
            }
            return s.length() == 0 ? null : s;
        } catch (Exception e) {
            return null;
        }
    }

    // ---- fetching ----------------------------------------------------------------------------------

    /** Fetches what is stale in the background; {@code done} runs on the main thread when something changed. */
    static void refresh(Context ctx, ForkConfig fork, Runnable done) {
        Context app = ctx.getApplicationContext();
        boolean weather = weatherOn(app, fork), rates = ratesOn(app, fork), news = newsOn(app, fork);
        if (!weather && !rates && !news) return;
        long now = System.currentTimeMillis();
        SharedPreferences p = prefs(app);
        String weatherFor = p.getFloat(KEY_LAT, 0) + "," + p.getFloat(KEY_LON, 0) + "," + range(app);
        boolean needWeather = weather && now - lastWeatherTry > RETRY_MS
                && (!weatherFor.equals(p.getString(KEY_WEATHER_FOR, ""))
                    || now - p.getLong(KEY_WEATHER_AT, 0) > WEATHER_MAX_AGE_MS);
        boolean needRates = rates && now - lastRatesTry > RETRY_MS
                && now - p.getLong(KEY_RATES_AT, 0) > RATES_MAX_AGE_MS;
        long newsEvery = newsEveryMinutes(app) * 60_000L;
        // Retries after a failure no sooner than the refresh interval allows (at most every 5 minutes).
        boolean needNews = news && now - lastNewsTry > Math.min(RETRY_MS, newsEvery) - 30_000L
                && (now - p.getLong(KEY_NEWS_AT, 0) > newsEvery - 30_000L
                || !(newsTopic(app) + NEWS_SOURCE).equals(p.getString(KEY_NEWS_FOR, "")));
        if (!needWeather && !needRates && !needNews) return;
        if (needWeather) lastWeatherTry = now;
        if (needRates) lastRatesTry = now;
        if (needNews) lastNewsTry = now;
        IO.execute(() -> {
            boolean changed = false;
            if (needNews) changed |= fetchNews(app);
            if (needWeather) changed |= fetchWeather(app, weatherFor);
            if (needRates) changed |= fetchRates(app);
            if (changed && done != null) MAIN.post(done);
        });
    }

    /** Settings changed the city or the forecast length: fetch now rather than at the next 30-minute mark. */
    /** The next refresh fetches the rates again (a newly chosen currency may not be in the cached answer). */
    static void invalidateRates(Context ctx) {
        lastRatesTry = 0;
        prefs(ctx).edit().remove(KEY_RATES_AT).apply();
    }

    static void invalidateWeather(Context ctx) {
        lastWeatherTry = 0;
        prefs(ctx).edit().remove(KEY_WEATHER_AT).apply();
    }

    private static boolean fetchWeather(Context ctx, String key) {
        SharedPreferences p = prefs(ctx);
        String range = range(ctx);
        int days = "7".equals(range) ? 8 : "3".equals(range) ? 4 : 1;
        String url = String.format(Locale.ROOT, "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                        + "&current=temperature_2m,weather_code,is_day"
                        + "&daily=weather_code,temperature_2m_max,temperature_2m_min"
                        + "&forecast_days=%d&timezone=auto",
                p.getFloat(KEY_LAT, 0), p.getFloat(KEY_LON, 0), days);
        try {
            JSONObject json = new JSONObject(get(url));
            if (!json.has("current")) return false;
            p.edit().putString(KEY_WEATHER_JSON, json.toString())
                    .putLong(KEY_WEATHER_AT, System.currentTimeMillis())
                    .putString(KEY_WEATHER_FOR, key).apply();
            return true;
        } catch (Exception e) {
            Log.w(TAG, "weather: " + e.getMessage());
            return false;
        }
    }

    /**
     * The first 10 items' titles of the feed, one per line, leaving out Russian and pro-Russian outlets (NewsFilter, by
     * the item's {@code <source url>}); Google appends " - Outlet", shown as " · Outlet".
     */
    private static boolean fetchNews(Context ctx) {
        try {
            org.xmlpull.v1.XmlPullParser x = android.util.Xml.newPullParser();
            String topic = newsTopic(ctx);
            x.setInput(new java.io.StringReader(getFeed(newsUrl(topic))));
            StringBuilder out = new StringBuilder();
            int n = 0;
            boolean inItem = false;
            String title = null;
            boolean blocked = false;
            for (int e = x.getEventType(); e != org.xmlpull.v1.XmlPullParser.END_DOCUMENT && n < 10; e = x.next()) {
                if (e == org.xmlpull.v1.XmlPullParser.START_TAG && "item".equals(x.getName())) {
                    inItem = true;
                    title = null;
                    blocked = false;
                } else if (e == org.xmlpull.v1.XmlPullParser.END_TAG && "item".equals(x.getName())) {
                    inItem = false;
                    if (title == null || title.isEmpty() || blocked) continue;
                    if (out.length() > 0) out.append('\n');
                    out.append(title);
                    n++;
                } else if (inItem && e == org.xmlpull.v1.XmlPullParser.START_TAG && "title".equals(x.getName())) {
                    String t = x.nextText().replace('\n', ' ').trim();
                    int dash = t.lastIndexOf(" - ");
                    if (dash > 0) t = t.substring(0, dash) + "  \u00B7  " + t.substring(dash + 3);
                    title = t;
                } else if (inItem && e == org.xmlpull.v1.XmlPullParser.START_TAG && "source".equals(x.getName())) {
                    blocked = NewsFilter.blocked(x.getAttributeValue(null, "url"));
                }
            }
            if (n == 0) return fetchOwnFeeds(ctx, topic);
            prefs(ctx).edit().putString(KEY_NEWS, out.toString()).putLong(KEY_NEWS_AT, System.currentTimeMillis())
                    .putString(KEY_NEWS_FOR, topic + NEWS_SOURCE).apply();
            return true;
        } catch (Exception e) {
            Log.w(TAG, "news: " + e.getMessage());
            return fetchOwnFeeds(ctx, newsTopic(ctx));
        }
    }

    /** Google refused or gave nothing: the outlets' own feeds (NewsFeeds), whatever the section. */
    private static boolean fetchOwnFeeds(Context ctx, String topic) {
        List<NewsFeeds.Item> all = new java.util.ArrayList<>();
        for (String[] f : NewsFeeds.FEEDS) {
            try {
                all.addAll(NewsFeeds.parse(getFeed(f[0]), f[1]));
            } catch (Exception e) {
                Log.w(TAG, "news feed " + f[1] + ": " + e.getMessage());
            }
        }
        List<NewsFeeds.Item> top = NewsFeeds.merge(all, 10, 2);
        if (top.isEmpty()) return false;
        StringBuilder out = new StringBuilder();
        for (NewsFeeds.Item i : top) {
            if (out.length() > 0) out.append('\n');
            out.append(i.title).append("  \u00B7  ").append(i.source);
        }
        prefs(ctx).edit().putString(KEY_NEWS, out.toString()).putLong(KEY_NEWS_AT, System.currentTimeMillis())
                .putString(KEY_NEWS_FOR, topic + NEWS_SOURCE).apply();
        return true;
    }

    private static boolean fetchRates(Context ctx) {
        try {
            JSONArray root = new JSONArray(get(NBG_URL));
            if (root.length() == 0) return false;
            JSONArray all = root.getJSONObject(0).getJSONArray("currencies");
            JSONArray keep = new JSONArray();
            for (int i = 0; i < all.length(); i++) {
                JSONObject c = all.getJSONObject(i);
                String code = c.optString("code");
                if (java.util.Arrays.asList(CURRENCIES).contains(code)) keep.put(c);
            }
            if (keep.length() == 0) return false;
            prefs(ctx).edit().putString(KEY_RATES_JSON, keep.toString())
                    .putLong(KEY_RATES_AT, System.currentTimeMillis()).apply();
            return true;
        } catch (Exception e) {
            Log.w(TAG, "rates: " + e.getMessage());
            return false;
        }
    }

    /** A place found by Open-Meteo's geocoding search. */
    static final class Place {
        final String name, label;
        final double lat, lon;

        Place(String name, String label, double lat, double lon) {
            this.name = name;
            this.label = label;
            this.lat = lat;
            this.lon = lon;
        }
    }

    /** Searches places by name in the background; {@code done} gets the list (empty on failure) on the main thread. */
    static void searchCity(String query, String language, Consumer<List<Place>> done) {
        IO.execute(() -> {
            List<Place> found = new ArrayList<>();
            try {
                String url = "https://geocoding-api.open-meteo.com/v1/search?count=10&format=json&name="
                        + URLEncoder.encode(query.trim(), "UTF-8")
                        + "&language=" + URLEncoder.encode(language, "UTF-8");
                JSONArray results = new JSONObject(get(url)).optJSONArray("results");
                for (int i = 0; results != null && i < results.length(); i++) {
                    JSONObject r = results.getJSONObject(i);
                    String name = r.optString("name");
                    StringBuilder label = new StringBuilder(name);
                    String admin = r.optString("admin1", "");
                    if (!admin.isEmpty() && !admin.equals(name)) label.append(", ").append(admin);
                    String country = r.optString("country", "");
                    if (!country.isEmpty()) label.append(", ").append(country);
                    found.add(new Place(name, label.toString(), r.optDouble("latitude"), r.optDouble("longitude")));
                }
            } catch (Exception e) {
                Log.w(TAG, "geocoding: " + e.getMessage());
            }
            MAIN.post(() -> done.accept(found));
        });
    }

    private static String get(String address) throws Exception {
        return get(address, "application/json", null, 512 * 1024);
    }

    /**
     * Google News answers a feed request that looks like a script (Dalvik, Accept JSON) with 429 more often: ask
     * for XML as a browser would.
     */
    private static String getFeed(String address) throws Exception {
        return get(address, "application/rss+xml, application/xml;q=0.9, */*;q=0.8",
                "Mozilla/5.0 (Linux; Android 14; Google TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36",
                3 * 1024 * 1024);   // some outlets' feeds carry hundreds of items
    }

    private static String get(String address, String accept, String agent, int maxBytes) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        try {
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setRequestProperty("Accept", accept);
            if (agent != null) c.setRequestProperty("User-Agent", agent);
            int code = c.getResponseCode();
            if (code != 200) throw new java.io.IOException("HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n, total = 0;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > maxBytes) throw new java.io.IOException("answer too large");
                    out.write(buf, 0, n);
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            c.disconnect();
        }
    }

    // ---- formatting --------------------------------------------------------------------------------

    private static String deg(double t) {
        if (Double.isNaN(t)) return "–";
        long r = Math.round(t);
        return (r == 0 ? "0" : String.valueOf(r)) + "°";
    }

    private static String capitalize(String s, Locale locale) {
        if (s == null || s.isEmpty()) return "";
        return s.substring(0, 1).toUpperCase(locale) + s.substring(1);
    }

    /** WMO weather code to a symbol. */
    static String icon(int code, boolean day) {
        if (code == 0) return day ? "☀️" : "🌙";
        if (code == 1 || code == 2) return day ? "🌤️" : "🌙";
        if (code == 3) return "☁️";
        if (code == 45 || code == 48) return "🌫️";
        if (code >= 51 && code <= 57) return "🌦️";
        if ((code >= 61 && code <= 67) || (code >= 80 && code <= 82)) return "🌧️";
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return "❄️";
        if (code >= 95) return "⛈️";
        return "☁️";
    }
}
