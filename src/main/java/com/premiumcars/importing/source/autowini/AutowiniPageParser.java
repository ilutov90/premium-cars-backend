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

    private static final String MAKE_MODEL = "make_model";

    /** dt label (lowercase, no trailing dot, no spaces around "/") -> field key. */
    static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("item no", "item_no"),
            Map.entry("make", "make"),
            Map.entry("model/class/grade", "model"),
            // since Nov 2026 one row "Make/Model/Class": "Hyundai / Grandeur IG (Azera) / 2.4 Modern"
            Map.entry("make/model/class", MAKE_MODEL),
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

    /** The export name after the model on the mobile page: "Grandeur IG (Azera)". */
    private static final Pattern EXPORT_NAME = Pattern.compile("\\s*\\([^)]*\\)\\s*$");
    private static final Pattern ITEM_ID = Pattern.compile("(IC\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SIZED = Pattern.compile("^(.*?)_(?:320|1024)\\.(jpe?g)(\\?.*)?$", Pattern.CASE_INSENSITIVE);
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
            String label = dt.text().trim().toLowerCase(Locale.ROOT).replaceAll("\\.$", "")
                    .replaceAll("\\s*/\\s*", "/");
            String key = LABELS.get(label);
            if (key == null || fields.containsKey(key)) {
                continue;
            }
            Element dd = dt.nextElementSibling();
            if (MAKE_MODEL.equals(key)) {
                if (dd != null && "dd".equals(dd.tagName())) {
                    fields.put(key, dd.text());
                    putMakeModel(fields, dd.wholeText());
                }
                continue;
            }
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

    /**
     * "Make / Model [export name] / Class" -> "make" and "model" as the old rows had them ("Grandeur IG / 2.4 Modern").
     * The export name ("Azera") is on its own line on the desktop page and in brackets on the mobile page: left out,
     * as autowini's own title leaves it out.
     */
    private static void putMakeModel(Map<String, String> fields, String value) {
        String[] parts = value.replace('\u00a0', ' ').split("/");
        String make = clean(parts[0]);
        if (!make.isEmpty()) {
            fields.putIfAbsent("make", make);
        }
        if (parts.length < 2) {
            return;
        }
        String model = parts[1].lines().map(AutowiniPageParser::clean).filter(l -> !l.isEmpty()).findFirst()
                .orElse("");
        model = EXPORT_NAME.matcher(model).replaceFirst("").trim();
        List<String> rest = new ArrayList<>();
        for (int i = 2; i < parts.length; i++) {
            String c = clean(parts[i]);
            if (!c.isEmpty()) {
                rest.add(c);
            }
        }
        String full = rest.isEmpty() ? model : model + " / " + String.join(" / ", rest);
        if (!full.isBlank()) {
            fields.putIfAbsent("model", full.startsWith(" / ") ? full.substring(3) : full);
        }
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

    private static final java.util.regex.Pattern NEW_PREFIX =
            java.util.regex.Pattern.compile("^(?:the\\s+)?(?:all\\s+)?new\\s+(?=\\S)", java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * The model without the sellers' generation prefix ("All New 5 Series", "The New Grandeur", "All NEW K3" ->
     * "5 Series", "Grandeur", "K3"), as autowini's menu names it. The automatic import takes the menu's own model
     * anyway; this is for listings imported by link.
     */
    static String withoutNew(String model) {
        String m = NEW_PREFIX.matcher(model).replaceFirst("").trim();
        return m.isEmpty() ? model : m;
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
            model = withoutNew(first.split("\\(")[0].trim());
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
