package com.premiumcars.importing.source.autowini;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import com.premiumcars.catalog.domain.CarSpecs;
import com.premiumcars.catalog.domain.FuelType;
import com.premiumcars.catalog.domain.Transmission;
import com.premiumcars.importing.extract.ValueParsers;

/**
 * Reads an autowini.com listing page (dt/dd pairs). No network: give it HTML, get data.
 * Port of AutowiniScraper.scrape() from autowini_poster_parallel.py.
 */
public final class AutowiniPageParser {

    /** dt label (lowercase, no trailing dot) -> field key. */
    static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("item no", "item_no"),
            Map.entry("make", "make"),
            Map.entry("model / class / grade", "model"),
            Map.entry("model year", "year"),
            Map.entry("odometer reading", "km"),
            Map.entry("engine volume", "engine_cc"),
            Map.entry("fuel type", "fuel"),
            Map.entry("transmission", "transmission"),
            Map.entry("steering", "steering"),
            Map.entry("exterior color", "color"),
            Map.entry("location", "location"),
            Map.entry("condition", "condition"),
            Map.entry("vehicle type", "vehicle_type"),
            Map.entry("price", "price"),
            Map.entry("fob price", "price"),
            Map.entry("sale price", "price"));

    private static final Pattern ITEM_ID = Pattern.compile("(IC\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SIZED = Pattern.compile("^(.*?)_(?:320|1024)\\.(jpe?g)(\\?.*)?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern MONEY = Pattern.compile("(US\\$|USD|\\$|€|EUR)\\s*([\\d][\\d,]*(?:\\.\\d+)?)",
            Pattern.CASE_INSENSITIVE);

    /**
     * @param price    price shown on the page (only for members who may see prices), null otherwise
     * @param currency currency of {@code price}
     */
    public record ParsedPage(String itemId, Map<String, String> fields, BigDecimal price, String currency,
                             List<String> images) {
    }

    private AutowiniPageParser() {
    }

    public static String itemId(String url) {
        Matcher m = ITEM_ID.matcher(url);
        return m.find() ? m.group(1).toUpperCase(Locale.ROOT) : null;
    }

    public static String desktopUrl(String itemId) {
        return "https://www.autowini.com/Cars/" + itemId + "/cars-detail";
    }

    public static String mobileUrl(String itemId) {
        return "https://m.autowini.com/items/" + itemId;
    }

    public static ParsedPage parse(String html, String itemId) {
        Document doc = Jsoup.parse(html);
        Map<String, String> fields = new LinkedHashMap<>();
        for (Element dt : doc.select("dt")) {
            String label = dt.text().trim().toLowerCase(Locale.ROOT).replaceAll("\\.$", "");
            String key = LABELS.get(label);
            if (key == null || fields.containsKey(key)) {
                continue;
            }
            Element dd = dt.nextElementSibling();
            if (dd != null && "dd".equals(dd.tagName())) {
                fields.put(key, dd.text().replaceAll("\\s+", " ").trim());
            }
        }

        BigDecimal price = null;
        String currency = null;
        String priceText = fields.get("price");
        if (priceText != null) {
            Matcher m = MONEY.matcher(priceText);
            if (m.find()) {
                price = ValueParsers.parseNumber(m.group(2));
                currency = m.group(1).contains("€") || m.group(1).equalsIgnoreCase("EUR") ? "EUR" : "USD";
            }
        }
        String id = fields.getOrDefault("item_no", itemId);
        return new ParsedPage(id, fields, price, currency, images(doc));
    }

    public static CarSpecs toSpecs(ParsedPage page) {
        Map<String, String> f = page.fields();
        String make = f.get("make");
        String modelRaw = f.get("model");

        // "Q7 (4M) / 45 TDI Quattro Sport" -> model "Q7", trim "(4M) 45 TDI Quattro Sport"
        String model = null;
        String trim = null;
        if (modelRaw != null) {
            String first = modelRaw.split("/")[0].trim();
            model = first.split("\\(")[0].trim();
            String rest = modelRaw.substring(modelRaw.indexOf(model) + model.length())
                    .replaceAll("\\s*/\\s*", " ").replaceAll("\\s+", " ").trim();
            trim = rest.isEmpty() ? null : rest;
        }
        String title = String.join(" ", nonNull(make, modelRaw == null ? null
                : modelRaw.replaceAll("\\s*/\\s*", " ").replaceAll("\\s+", " ").trim()));

        BigDecimal cc = ValueParsers.parseNumber(f.get("engine_cc"));
        String bodyType = f.get("vehicle_type");
        return CarSpecs.builder()
                .title(title.isBlank() ? null : title)
                .make(make)
                .model(model)
                .trim(trim)
                .year(ValueParsers.parseYear(f.get("year")))
                .mileageKm(ValueParsers.parseMileageKm(f.get("km")))
                .fuelType(FuelType.detect(f.get("fuel")))
                .transmission(Transmission.detect(f.get("transmission")))
                .engineCc(cc != null && cc.intValue() > 100 ? cc.intValue() : null)
                .bodyType(bodyType)
                .color(f.get("color"))
                .build();
    }

    /** Photos from imagebox.autowini.com: the 1024 px version of sized photos, new-style URLs as they are. */
    static List<String> images(Document doc) {
        Set<String> seen = new HashSet<>();
        List<String> out = new ArrayList<>();
        List<String> sources = new ArrayList<>();
        for (Element img : doc.select("img")) {
            sources.add(img.attr("src"));
            sources.add(img.attr("data-src"));
        }
        for (Element el : doc.select("[data-src]")) {
            sources.add(el.attr("data-src"));
        }
        for (String src : sources) {
            if (src == null || !src.contains("imagebox.autowini.com")) {
                continue;
            }
            String s = src.trim();
            Matcher m = SIZED.matcher(s);
            String key;
            String url;
            if (m.matches()) {
                key = m.group(1);
                url = m.group(1) + "_1024." + m.group(2) + (m.group(3) == null ? "" : m.group(3));
            } else {
                key = s.replaceAll("(?i)\\.(jpe?g|png|webp)(\\?.*)?$", "");
                url = s;
            }
            if (seen.add(key)) {
                out.add(url);
            }
        }
        return out;
    }

    /** Download variants of a sized photo: 1024, 320, then without the size suffix. */
    public static List<String> imageCandidates(String url) {
        Set<String> out = new LinkedHashSet<>();
        out.add(url);
        Matcher m = Pattern.compile("_(?:320|1024)\\.(jpe?g)(\\?.*)?$", Pattern.CASE_INSENSITIVE).matcher(url);
        if (m.find()) {
            String base = url.substring(0, m.start());
            String ext = m.group(1);
            String q = m.group(2) == null ? "" : m.group(2);
            out.add(base + "_1024." + ext + q);
            out.add(base + "_320." + ext + q);
            out.add(base + "." + ext + q);
        }
        return List.copyOf(out);
    }

    private static List<String> nonNull(String... parts) {
        List<String> out = new ArrayList<>();
        for (String p : parts) {
            if (p != null && !p.isBlank()) {
                out.add(p);
            }
        }
        return out;
    }
}
