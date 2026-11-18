package com.muzdai.launcher;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A spoken question whose answer is already on Home (the clock, the date, the weather and the days ahead, the rates,
 * what plays): the owner's retort, then the answer from what is shown. Only questions the screen answers fully count:
 * the weather of the city shown, on a day shown, without wind or the like; a rate of a currency shown, to the lari;
 * the news ticker is left out. Anything else is not matched (null) and goes the usual way. Pure Java, JVM tested.
 */
final class ScreenAnswers {
    private ScreenAnswers() { }

    /** The owner's retorts, one at random each time, never the same twice in a row. */
    static final String[] RETORTS = {
            "Ты что, в глаза ебёшься?!!",
            "Глаза разуй!!!",
            "Протри глаза!!!",
            "Да вот же, на экране!!!",
            "Очки надень!!!",
            "Тебе экран для красоты повесили?!!",
            "Слепой, что ли?!!",
            "Экран перед тобой, гений!!!",
            "Посмотри на экран, ёпта!!!",
            "Я что, зря это выводил?!!",
            "Глазоньки-то открой!!!",
            "Ты в телевизор смотришь или куда?!!",
    };
    private static final java.util.Random RANDOM = new java.util.Random();
    private static int lastRetort = -1;

    /** A retort index other than {@code last} (any when there is only one). */
    static int pick(java.util.Random random, int last) {
        if (RETORTS.length < 2) return 0;
        int i = random.nextInt(RETORTS.length - 1);
        return last >= 0 && i >= last ? i + 1 : i;
    }
    private static final Locale RU = new Locale("ru");

    /** What Home shows right now; a null or empty part is not on screen. */
    static final class Facts {
        String time;
        LocalDate today;
        boolean dateShown;
        String city;
        Double nowTemp;
        Integer nowCode;
        /** Index 0 is today (its high and low only when shown), then the days ahead shown. */
        final List<Day> days = new ArrayList<>();
        final List<Rate> rates = new ArrayList<>();
        String playing;
    }

    static final class Day {
        final LocalDate date;
        final int code;
        final Double max, min;

        Day(LocalDate date, int code, Double max, Double min) {
            this.date = date;
            this.code = code;
            this.max = max;
            this.min = min;
        }
    }

    static final class Rate {
        final String code;
        final int quantity;
        final double rate;

        Rate(String code, int quantity, double rate) {
            this.code = code;
            this.quantity = quantity;
            this.rate = rate;
        }
    }

    /** The retort and the answer, or null when the screen does not fully answer the question. */
    static String answer(String spoken, Facts f) {
        if (spoken == null || f == null) return null;
        String q = " " + spoken.toLowerCase(RU).replace('ё', 'е').replaceAll("[^\\p{L}\\p{N}]+", " ").trim() + " ";
        String a = time(q, f);
        if (a == null) a = date(q, f);
        if (a == null) a = playing(q, f);
        if (a == null) a = rate(q, f);
        if (a == null) a = weather(q, f);
        if (a == null) return null;
        lastRetort = pick(RANDOM, lastRetort);
        return RETORTS[lastRetort] + "\n" + a;
    }

    // ---- time and date ------------------------------------------------------------------------------

    private static String time(String q, Facts f) {
        if (f.time == null) return null;
        return has(q, "который час", "сколько времени", "сколько время", "какое время", "текущее время",
                "сколько сейчас времени") ? "Сейчас " + f.time + "." : null;
    }

    private static String date(String q, Facts f) {
        if (!f.dateShown || f.today == null) return null;
        if (has(q, "какое число", "какое сегодня число", "какая дата", "какая сегодня дата", "какое сегодня")) {
            return "Сегодня " + DateTimeFormatter.ofPattern("EEEE, d MMMM", RU).format(f.today) + ".";
        }
        if (has(q, "какой день недели", "какой сегодня день", "какой день сегодня")) {
            return "Сегодня " + DateTimeFormatter.ofPattern("EEEE", RU).format(f.today) + ".";
        }
        return null;
    }

    private static String playing(String q, Facts f) {
        if (f.playing == null || f.playing.isEmpty()) return null;
        return has(q, "что играет", "что сейчас играет", "что за песня", "что за трек", "кто поет", "что звучит",
                "что это играет", "какая песня") ? "Играет " + f.playing + "." : null;
    }

    // ---- rates --------------------------------------------------------------------------------------

    private static final String[][] CURRENCY_WORDS = {
            {"доллар", "USD"}, {"евро", "EUR"}, {"фунт", "GBP"}, {"лир", "TRY"}, {"гривн", "UAH"}, {"драм", "AMD"},
            {"манат", "AZN"}, {"рубл", "RUB"}, {"юан", "CNY"}, {"тенге", "KZT"}, {"франк", "CHF"}};

    private static String rate(String q, Facts f) {
        if (f.rates.isEmpty() || !has(q, "курс", "сколько стоит", "почем", "по чем")) return null;
        Set<String> named = new HashSet<>();
        for (String[] w : CURRENCY_WORDS) if (q.contains(" " + w[0]) ) named.add(w[1]);
        // One currency, to the lari as shown; "dollar to euro" or "in rubles" is not on screen.
        if (named.size() != 1) return null;
        String code = named.iterator().next();
        for (Rate r : f.rates) {
            if (!r.code.equals(code)) continue;
            return (r.quantity > 1 ? r.quantity + " " : "1 ") + code + " = "
                    + String.format(Locale.ROOT, "%.4f", r.rate) + " лари.";
        }
        return null;
    }

    // ---- weather ------------------------------------------------------------------------------------

    private static final String[] WEATHER = {"погод", "градус", "температур", "холодн", "тепл", "жарк", "дожд", "осадк",
            "зонт", "снег", "солнечн", "пасмурн"};
    /** Not on screen: only the sky and the temperatures are. */
    private static final String[] NOT_SHOWN = {"ветер", "ветр", "влажн", "давлен", "восход", "закат", "ультрафиол",
            "туман", "час", "вечер", "утр", "ночь", "ночью", "недел", "выходн"};
    /** Words a weather question may have besides its day and the city shown. */
    private static final Set<String> FILLER = new HashSet<>(Arrays.asList(
            "какая", "какой", "какое", "какие", "как", "будет", "будут", "ли", "а", "и", "на", "в", "во", "у", "нас",
            "там", "сегодня", "сейчас", "завтра", "послезавтра", "улице", "за", "окном", "сколько", "брать", "взять",
            "нужен", "нужно", "ожидается", "обещают", "по", "прогноз", "прогнозу", "мне", "скажи", "подскажи", "день",
            "днем", "пойдет", "идет", "пойдёт", "будет", "или"));
    private static final String[][] WEEKDAYS = {{"понедельник", "MONDAY"}, {"вторник", "TUESDAY"}, {"сред", "WEDNESDAY"},
            {"четверг", "THURSDAY"}, {"пятниц", "FRIDAY"}, {"суббот", "SATURDAY"}, {"воскресен", "SUNDAY"}};

    private static String weather(String q, Facts f) {
        if (f.nowTemp == null || f.city == null || f.today == null || !startsAny(q, WEATHER)) return null;
        if (startsAny(q, NOT_SHOWN)) return null;
        String cityStem = stem(f.city);
        int offset = 0;
        boolean dayNamed = false;
        for (String w : q.trim().split(" ")) {
            if (w.isEmpty() || FILLER.contains(w) || startsWith(w, WEATHER)) continue;
            if (!cityStem.isEmpty() && w.startsWith(cityStem)) continue;   // "в батуми", "батуми"
            if (w.equals("завтра")) continue;
            DayOfWeek day = weekday(w);
            if (day != null) {
                if (dayNamed) return null;
                dayNamed = true;
                int ahead = (day.getValue() - f.today.getDayOfWeek().getValue() + 7) % 7;
                offset = ahead;
                continue;
            }
            return null;   // another city, a time of day, anything the screen may not answer
        }
        if (q.contains(" послезавтра ")) offset = 2;
        else if (q.contains(" завтра ")) offset = 1;
        if (offset == 0) return today(f);
        for (Day d : f.days) {
            if (d.date.equals(f.today.plusDays(offset))) return ahead(d);
        }
        return null;
    }

    private static String today(Facts f) {
        StringBuilder s = new StringBuilder("В ").append(f.city).append(" сейчас ")
                .append(deg(f.nowTemp));
        if (f.nowCode != null) s.append(", ").append(sky(f.nowCode));
        if (!f.days.isEmpty() && f.days.get(0).date.equals(f.today) && f.days.get(0).max != null) {
            Day d = f.days.get(0);
            s.append("; днём до ").append(deg(d.max)).append(", ночью до ").append(deg(d.min));
        }
        return s.append('.').toString();
    }

    private static String ahead(Day d) {
        String name = DateTimeFormatter.ofPattern("EEEE", RU).format(d.date);
        return name.substring(0, 1).toUpperCase(RU) + name.substring(1) + ": " + sky(d.code) + ", от " + deg(d.min)
                + " до " + deg(d.max) + ".";
    }

    /** The WMO code in words, as InfoWidgets.icon draws it. */
    static String sky(int code) {
        if (code == 0) return "ясно";
        if (code == 1 || code == 2) return "переменная облачность";
        if (code == 3) return "пасмурно";
        if (code == 45 || code == 48) return "туман";
        if (code >= 51 && code <= 57) return "морось";
        if ((code >= 61 && code <= 67) || (code >= 80 && code <= 82)) return "дождь";
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return "снег";
        if (code >= 95) return "гроза";
        return "облачно";
    }

    // ---- facts from what InfoWidgets keeps ------------------------------------------------------------

    /**
     * The weather Home shows from the cached Open-Meteo answer: now, today's high and low unless the range is "now",
     * and the next 3 or 7 days for those ranges.
     */
    static void weatherFrom(Facts f, String json, String range, String city) {
        try {
            JSONObject o = new JSONObject(json);
            JSONObject cur = o.getJSONObject("current");
            f.city = city == null || city.isEmpty() ? null : city;
            f.nowTemp = cur.has("temperature_2m") ? cur.getDouble("temperature_2m") : null;
            f.nowCode = cur.has("weather_code") ? cur.getInt("weather_code") : null;
            JSONObject daily = o.optJSONObject("daily");
            if (daily == null || "now".equals(range)) return;
            JSONArray dates = daily.getJSONArray("time");
            JSONArray codes = daily.getJSONArray("weather_code");
            JSONArray max = daily.getJSONArray("temperature_2m_max");
            JSONArray min = daily.getJSONArray("temperature_2m_min");
            int shown = "3".equals(range) ? 3 : "7".equals(range) ? 7 : 0;
            for (int i = 0; i <= shown && i < dates.length(); i++) {
                f.days.add(new Day(LocalDate.parse(dates.getString(i)), codes.optInt(i, -1),
                        max.optDouble(i), min.optDouble(i)));
            }
        } catch (Exception e) {
            f.nowTemp = null;
        }
    }

    /** The rates Home shows (National Bank of Georgia's answer, the chosen currencies). */
    static void ratesFrom(Facts f, String json, List<String> shown) {
        try {
            JSONArray list = new JSONArray(json);
            for (int i = 0; i < list.length(); i++) {
                JSONObject c = list.getJSONObject(i);
                String code = c.optString("code");
                if (shown.contains(code)) {
                    f.rates.add(new Rate(code, Math.max(1, c.optInt("quantity", 1)), c.optDouble("rate")));
                }
            }
        } catch (Exception ignored) {
            // no rates on screen
        }
    }

    // ---- words ------------------------------------------------------------------------------------------

    private static boolean has(String q, String... phrases) {
        for (String p : phrases) if (q.contains(" " + p + " ")) return true;
        return false;
    }

    private static boolean startsAny(String q, String[] stems) {
        for (String w : q.trim().split(" ")) if (startsWith(w, stems)) return true;
        return false;
    }

    private static boolean startsWith(String w, String[] stems) {
        for (String s : stems) if (w.startsWith(s)) return true;
        return false;
    }

    private static DayOfWeek weekday(String w) {
        for (String[] d : WEEKDAYS) if (w.startsWith(d[0])) return DayOfWeek.valueOf(d[1]);
        return null;
    }

    /** A city's stem for its Russian cases: "Батуми" → "батум", "Тбилиси" → "тбилис", "Москва" → "москв". */
    static String stem(String city) {
        String c = city.toLowerCase(RU).replace('ё', 'е').trim();
        if (c.contains(" ")) c = c.substring(0, c.indexOf(' '));
        return c.length() > 4 ? c.substring(0, c.length() - 1) : c;
    }

    private static String deg(double t) {
        long r = Math.round(t);
        return (r > 0 ? "+" : "") + r + "°";
    }
}
