package com.premiumcars.importing.source.autowini;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
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

import com.premiumcars.catalog.domain.CarFeature;
import com.premiumcars.catalog.domain.SpecGroup;
import com.premiumcars.catalog.domain.CarSpecs;
import com.premiumcars.catalog.domain.FuelType;
import com.premiumcars.catalog.domain.Transmission;
import com.premiumcars.importing.extract.FeatureMapper;
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
            Map.entry("first registration date", "reg_date"),
            Map.entry("location", "location"),
            Map.entry("condition", "condition"),
            Map.entry("vehicle type", "vehicle_type"),
            Map.entry("price", "price"),
            Map.entry("fob price", "price"),
            Map.entry("sale price", "price"));

    private static final Pattern ITEM_ID = Pattern.compile("(IC\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SIZED = Pattern.compile("^(.*?)_(?:320|1024)\\.(jpe?g)(\\?.*)?$", Pattern.CASE_INSENSITIVE);
    /** "19th Sep. 2023", "1st Jan 2020" */
    private static final Pattern DAY_MONTH_YEAR = Pattern.compile(
            "(?:\\d{1,2}(?:st|nd|rd|th)?\\s+)?([A-Za-z]{3})[a-z]*\\.?\\s+((?:19|20)\\d{2})");
    private static final List<String> MONTHS =
            List.of("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec");
    private static final Pattern MONEY = Pattern.compile("(US\\$|USD|\\$|€|EUR)\\s*([\\d][\\d,]*(?:\\.\\d+)?)",
            Pattern.CASE_INSENSITIVE);

    /** Hidden fields of autowini's own discounts on the BIS price (USD). */
    private static final List<String> DISCOUNT_FIELDS =
            List.of("i_iBisDiscount", "i_iBisDiscountByEvent", "i_iBisDiscountByMembership");

    /**
     * @param price    price shown on the page (only for members who may see prices), null otherwise
     * @param currency currency of {@code price}
     * @param discount autowini's discount on that price (USD), when it shows one; null otherwise
     * @param optionGroups the seller's "Options" of this car by group (e.g. "Interior Option": "Heating Seat"),
     *                     empty when the page has none
     */
    public record ParsedPage(String itemId, Map<String, String> fields, BigDecimal price, String currency,
                             BigDecimal discount, List<String> images, List<SpecGroup> optionGroups) {

        public ParsedPage {
            optionGroups = optionGroups == null ? List.of() : List.copyOf(optionGroups);
        }

        /** All options, without their groups, e.g. "Heating Seat". */
        public List<String> options() {
            return optionGroups.stream().flatMap(g -> g.items().stream()).map(SpecGroup.Item::name).distinct()
                    .toList();
        }
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
            // the site menu repeats the labels ("Make", "Fuel Type", "Location") with lists of links as values
            if (inSiteChrome(dt)) {
                continue;
            }
            String label = dt.text().trim().toLowerCase(Locale.ROOT).replaceAll("\\.$", "");
            String key = LABELS.get(label);
            if (key == null || fields.containsKey(key)) {
                continue;
            }
            Element dd = dt.nextElementSibling();
            if (dd != null && "dd".equals(dd.tagName()) && dd.select("li").isEmpty()) {
                // without the "!" tooltips: the odometer's says "...real kilometer(mileage)..." (read as miles)
                Element value = dd.clone();
                value.select(".btnTip, .popTip").remove();
                fields.put(key, value.text().replace('\u00a0', ' ').replaceAll("\\s+", " ").trim());
            }
        }

        // The desktop page fills its price box with JavaScript from hidden fields: i_iBisPrice is the price shown
        // to buyers ("FOB, Incheon"), in USD: the seller's price (i_iPrice) plus autowini's BIS fee and expenses.
        // Members who may not see prices get no value there.
        BigDecimal price = positive(hidden(doc, "i_iBisPrice"));
        String currency = price == null ? null : "USD";
        BigDecimal discount = null;
        for (String field : DISCOUNT_FIELDS) {
            BigDecimal d = positive(hidden(doc, field));
            if (d != null) {
                discount = discount == null ? d : discount.add(d);
            }
        }
        String priceText = fields.get("price");
        if (price == null && priceText != null) {
            Matcher m = MONEY.matcher(priceText);
            if (m.find()) {
                price = ValueParsers.parseNumber(m.group(2));
                currency = m.group(1).contains("€") || m.group(1).equalsIgnoreCase("EUR") ? "EUR" : "USD";
            }
        }
        String id = fields.getOrDefault("item_no", itemId);
        return new ParsedPage(id, fields, price, currency, price == null ? null : discount, images(doc),
                optionGroups(doc));
    }

    private static boolean inSiteChrome(Element element) {
        for (Element parent : element.parents()) {
            String tag = parent.tagName();
            if (tag.equals("header") || tag.equals("nav") || tag.equals("footer")) {
                return true;
            }
        }
        return false;
    }

    private static String hidden(Document doc, String id) {
        Element input = doc.getElementById(id);
        return input == null ? null : input.attr("value");
    }

    private static BigDecimal positive(String value) {
        BigDecimal n = value == null || value.isBlank() ? null : ValueParsers.parseNumber(value);
        return n != null && n.signum() > 0 ? n : null;
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
        Set<CarFeature> features = EnumSet.noneOf(CarFeature.class);
        features.addAll(FeatureMapper.fromPhrases(page.options()));
        features.addAll(FeatureMapper.fromSpecs(null, null, null, FeatureMapper.rightHandDrive(f.get("steering"))));
        return CarSpecs.builder()
                .title(title.isBlank() ? null : title)
                .make(make)
                .model(model)
                .trim(trim)
                .year(ValueParsers.parseYear(f.get("year")))
                .firstRegistration(yearMonth(f.get("reg_date")))
                .mileageKm(ValueParsers.parseMileageKm(f.get("km")))
                .fuelType(FuelType.detect(f.get("fuel")))
                .transmission(Transmission.detect(f.get("transmission")))
                .engineCc(cc != null && cc.intValue() > 100 ? cc.intValue() : null)
                .bodyType(bodyType)
                .color(f.get("color"))
                .features(features)
                .sheet(page.optionGroups())
                .build();
    }

    /**
     * The "Options" block by group. Desktop page: {@code .optionInfo li} with an {@code h3} (group) and
     * {@code .list span} items; mobile page (what the server gets): {@code .boxOptions [id^=carOptionList]} with an
     * {@code h3} and {@code li} items.
     */
    static List<SpecGroup> optionGroups(Document doc) {
        Map<String, List<SpecGroup.Item>> groups = new LinkedHashMap<>();
        for (Element li : doc.select(".optionInfo > ul > li")) {
            addOptions(groups, li.selectFirst("h3"), li.select(".list span"));
        }
        for (Element box : doc.select(".boxOptions [id^=carOptionList]")) {
            addOptions(groups, box.selectFirst("h3"), box.select("li"));
        }
        return groups.entrySet().stream().map(e -> new SpecGroup(e.getKey(), e.getValue())).toList();
    }

    private static void addOptions(Map<String, List<SpecGroup.Item>> groups, Element heading, List<Element> items) {
        String group = heading == null ? "Options" : clean(heading.text());
        List<SpecGroup.Item> rows = groups.computeIfAbsent(group.isEmpty() ? "Options" : group, g -> new ArrayList<>());
        for (Element item : items) {
            String text = clean(item.text());
            if (!text.isEmpty() && rows.stream().noneMatch(r -> r.name().equals(text))) {
                rows.add(new SpecGroup.Item(text, ""));
            }
        }
    }

    private static String clean(String text) {
        return text.replace('\u00a0', ' ').replaceAll("\\s+", " ").trim();
    }

    /** "19th Sep. 2023" -> "2023-09"; the text as it is when it has another shape; null when empty. */
    static String yearMonth(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Matcher m = DAY_MONTH_YEAR.matcher(raw);
        if (m.find()) {
            int month = MONTHS.indexOf(m.group(1).toLowerCase(Locale.ROOT)) + 1;
            if (month > 0) {
                return m.group(2) + "-" + String.format("%02d", month);
            }
        }
        return raw.trim();
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
