package com.premiumcars.importing.source.guazi;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.premiumcars.catalog.domain.CarFeature;
import com.premiumcars.catalog.domain.CarSpecs;
import com.premiumcars.catalog.domain.Drivetrain;
import com.premiumcars.catalog.domain.FuelType;
import com.premiumcars.catalog.domain.SpecGroup;
import com.premiumcars.catalog.domain.Transmission;
import com.premiumcars.importing.extract.FeatureMapper;
import com.premiumcars.importing.extract.NextFlightData;
import com.premiumcars.importing.extract.ValueParsers;

/**
 * Reads an en.guazi.com listing page. No network: give it HTML, get data.
 * <p>
 * The site is a Next.js App Router app: the whole listing is server-rendered into the page's flight data
 * ({@code self.__next_f.push(...)} scripts) as one JSON object, {@code rawData}, so no browser is needed (the
 * "Show More" buttons only reveal data that is already there). It holds:
 * <ul>
 *   <li>{@code vehicleDetails[] {key, name, value}}: the "Vehicle Details" section (name = the page's label);</li>
 *   <li>{@code variantDetailDto.specifications.groupList[] {groupTitle, itemList[] {name, value}}}: the whole
 *       "Specifications" (value "Yes" / "No" / "Optional" for equipment);</li>
 *   <li>{@code carHighLightList}: the "Features" section; {@code batteryHealth}: the battery report's SOH;</li>
 *   <li>{@code prices[] {price, linePrice, isDefault}}: FOB prices in USD per port, the default one first in use;</li>
 *   <li>{@code images[] {imgUrl, ...}}: all photos (exterior, interior, details); {@code reportDetailLite}: the
 *       inspection summary.</li>
 * </ul>
 * The schema.org {@code Car} JSON-LD of the page is the fallback for the price.
 */
public final class GuaziPageParser {

    private static final ObjectMapper JSON = new ObjectMapper();
    /** "/products/byd-song-max-2021-...-68pfqu2abf.html" -> "68pfqu2abf" */
    private static final Pattern PRODUCT = Pattern.compile("/products/(?:[^/?#]*-)?([a-z0-9]{6,})\\.html",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern YEAR_MONTH = Pattern.compile("((?:19|20)\\d{2})[.\\-/](\\d{1,2})");
    private static final Pattern YYYYMMDD = Pattern.compile("^((?:19|20)\\d{2})(\\d{2})\\d{2}$");
    private static final Pattern RANGE_STANDARD = Pattern.compile("\\b(CLTC|NEDC|WLTP|EPA)\\b", Pattern.CASE_INSENSITIVE);
    private static final Set<String> EMPTY_VALUES = Set.of("", "-", "--", "—", "/", "N/A", "null", "$undefined");
    private static final Set<String> NO = Set.of("no", "none", "not available", "optional", "○");

    /** The page's "Vehicle Details" labels (lowercase) and machine keys -> our field key. */
    static final Map<String, String> DETAILS = Map.ofEntries(
            Map.entry("model year", "year"), Map.entry("modelyear", "year"),
            Map.entry("1st reg. date", "reg_date"), Map.entry("regdate", "reg_date"),
            Map.entry("mfg. date", "mfg_date"), Map.entry("mfgdate", "mfg_date"),
            Map.entry("mileage (km)", "km"), Map.entry("mileage", "km"),
            Map.entry("fuel type", "fuel"), Map.entry("fuel", "fuel"), Map.entry("fueltype", "fuel"),
            Map.entry("transmission", "transmission"),
            Map.entry("drive train", "drivetrain"), Map.entry("drivetype", "drivetrain"),
            Map.entry("drivetrain", "drivetrain"),
            Map.entry("engine", "engine"),
            Map.entry("horsepower (ps)", "ps"), Map.entry("horsepower", "ps"),
            Map.entry("battery capacity (kwh)", "battery"), Map.entry("batterycapacity", "battery"),
            Map.entry("battery type", "battery_type"), Map.entry("batterytype", "battery_type"),
            Map.entry("body style", "body"), Map.entry("bodytype", "body"), Map.entry("body type", "body"),
            Map.entry("seats", "seats"), Map.entry("doors", "doors"),
            Map.entry("exterior color", "color"), Map.entry("exteriorcolor", "color"),
            Map.entry("dimension (mm)", "dimensions"), Map.entry("dimension", "dimensions"),
            Map.entry("dimensions (mm)", "dimensions"),
            Map.entry("curb weight (kg)", "weight"), Map.entry("weight", "weight"),
            Map.entry("steering", "steering"),
            Map.entry("location", "location"),
            Map.entry("item no", "item_no"), Map.entry("vin", "vin"));

    /**
     * Everything read from one page.
     *
     * @param productId    the listing id at the end of the URL (e.g. "68pfqu2abf")
     * @param fields       our field key -> value from "Vehicle Details" (see {@link #DETAILS}); the electric range
     *                     is under "range", the range's label under "range_label"
     * @param specs        the "Specifications" groups as the page shows them (every row, Yes/No values included)
     * @param highlights   the "Features" section
     * @param priceUsd     the price in use (the default port's current, possibly discounted, price)
     * @param images       photo URLs in the page's order
     * @param inspection   short summary of the inspection report for the admin, or null
     */
    public record ParsedPage(String productId, String title, String make, String model, Map<String, String> fields,
                             List<SpecGroup> specs, List<String> highlights, BigDecimal priceUsd,
                             Integer batteryHealthPct, List<String> images, String licenseDate, String inspection) {

        public FuelType fuelType() {
            FuelType f = FuelType.detect(fields.get("fuel"));
            return f != null ? f : FuelType.detect(title);
        }

        /** The value of a "Specifications" row by name (any case), first match; null when not there. */
        public String spec(String name) {
            for (SpecGroup g : specs) {
                for (SpecGroup.Item i : g.items()) {
                    if (i.name().equalsIgnoreCase(name)) {
                        return value(i.value());
                    }
                }
            }
            return null;
        }
    }

    private GuaziPageParser() {
    }

    /** The listing id at the end of a product URL, or null when it is not a product page. */
    public static String productId(String url) {
        Matcher m = PRODUCT.matcher(url == null ? "" : url);
        return m.find() ? m.group(1).toLowerCase(Locale.ROOT) : null;
    }

    /** Any language version (ru., ar., fr. ...) -> the English page, without query or fragment. */
    public static URI englishUrl(URI url) {
        return URI.create("https://en.guazi.com" + (url.getRawPath() == null ? "" : url.getRawPath()));
    }

    public static ParsedPage parse(String html, String productId) {
        Document doc = Jsoup.parse(html);
        String flight = NextFlightData.text(doc);
        JsonNode raw = rawData(flight, productId);
        if (raw == null) {
            throw new IllegalArgumentException("The guazi page has no listing data (rawData): " + describe(html, doc,
                    flight));
        }
        String id = text(raw, "productId");
        Map<String, String> fields = details(raw.path("vehicleDetails"));
        List<SpecGroup> specs = specGroups(raw.path("variantDetailDto").path("specifications").path("groupList"));
        BigDecimal price = price(raw.path("prices"));
        if (price == null) {
            price = ldPrice(doc);
        }
        String title = firstNonBlank(text(raw, "tdkTitle"), stripUsed(text(raw, "title")));
        return new ParsedPage(id.isEmpty() ? productId : id, title, value(text(raw, "makeNameEn")),
                value(text(raw, "modelName")), fields, specs, highlights(raw.path("carHighLightList")), price,
                batteryHealth(raw.path("batteryHealth")), images(raw.path("images")),
                value(text(raw, "licenseDate")), inspection(raw.path("reportDetailLite")));
    }

    /** Our car data from the parsed page. */
    public static CarSpecs toSpecs(ParsedPage page) {
        Map<String, String> f = page.fields();
        FuelType fuel = page.fuelType();
        Integer year = ValueParsers.parseYear(f.get("year"));
        if (year == null) {
            year = ValueParsers.parseYear(page.title());
        }
        CarSpecs.Builder b = CarSpecs.builder()
                .title(page.title())
                .make(page.make())
                .model(page.model())
                .trim(trim(page.title(), page.make(), page.model(), year))
                .year(year)
                .firstRegistration(firstRegistration(f.get("reg_date"), page.licenseDate()))
                .mileageKm(ValueParsers.parseMileageKm(f.get("km")))
                .fuelType(fuel)
                .transmission(Transmission.detect(f.get("transmission")))
                .drivetrain(Drivetrain.detect(firstNonBlank(f.get("drivetrain"), page.spec("Drive Type"))))
                .engine(value(f.get("engine")))
                .bodyType(value(f.get("body")))
                .seats(intValue(firstNonBlank(f.get("seats"), page.spec("Seats"))))
                .doors(intValue(firstNonBlank(f.get("doors"), page.spec("Doors"))))
                .color(value(f.get("color")))
                .curbWeightKg(intValue(firstNonBlank(f.get("weight"), page.spec("Curb Weight (kg)"))))
                .dimensions(dimensions(f.get("dimensions")))
                .euroStandard(FeatureMapper.euroStandard(page.spec("Emission Standard")));

        Integer cc = engineCc(page);
        b.engineCc(cc);

        b.powerHp(powerHp(page, fuel, f.get("ps")));

        if (fuel != null && fuel.hasTractionBattery()) {
            b.batteryKwh(decimal(firstNonBlank(f.get("battery"), page.spec("Battery Capacity (kWh)"))))
                    .electricRangeKm(intValue(f.get("range")))
                    .rangeStandard(rangeStandard(f.get("range_label")))
                    .motorPowerKw(intValue(page.spec("Total Motor Power (kw)")))
                    .batteryHealthPct(page.batteryHealthPct());
        }

        Set<CarFeature> features = EnumSet.noneOf(CarFeature.class);
        features.addAll(FeatureMapper.fromPhrases(phrases(page)));
        features.addAll(FeatureMapper.fromSpecs(b.drivetrain(), b.seats(), b.doors(),
                FeatureMapper.rightHandDrive(f.get("steering"))));
        return b.features(features).sheet(sheet(page.specs())).build();
    }

    // ---------------------------------------------------------------- rawData

    /** The {@code rawData} object of the listing (the one with this product id when there are several). */
    static JsonNode rawData(String data, String productId) {
        JsonNode first = null;
        int at = data.indexOf("\"rawData\":");
        while (at >= 0) {
            int valueAt = at + "\"rawData\":".length();
            JsonNode node = NextFlightData.valueAt(data, valueAt);
            if (node == null) {
                node = referencedRow(data, valueAt); // "rawData":"$1f": the object is flight row 1f
            }
            if (node != null && node.isObject()) {
                if (productId == null || productId.equalsIgnoreCase(text(node, "productId"))) {
                    return node;
                }
                if (first == null) {
                    first = node;
                }
            }
            at = data.indexOf("\"rawData\":", at + 1);
        }
        return first;
    }

    private static final Pattern ROW_REFERENCE = Pattern.compile("\\G\\s*\"\\$([0-9a-fA-F]+)\"");

    /** The value of flight row {@code id} when the text at {@code from} is a reference {@code "$id"}. */
    static JsonNode referencedRow(String data, int from) {
        Matcher m = ROW_REFERENCE.matcher(data);
        if (!m.find(from)) {
            return null;
        }
        String id = m.group(1);
        int start;
        if (data.startsWith(id + ":")) {
            start = id.length() + 1;
        } else {
            int line = data.indexOf("\n" + id + ":");
            if (line < 0) {
                return null;
            }
            start = line + id.length() + 2;
        }
        JsonNode node = NextFlightData.valueAt(data, start);
        return node != null && node.isObject() ? node : null;
    }

    /** What the page was, for the error message: size, title and whether it has Next.js data at all. */
    static String describe(String html, Document doc, String flight) {
        String title = doc.title();
        return html.length() + " characters, title \"" + (title.length() > 80 ? title.substring(0, 80) : title)
                + "\", Next.js data " + (flight.isEmpty() ? "missing" : flight.length() + " characters");
    }

    static Map<String, String> details(JsonNode list) {
        Map<String, String> out = new HashMap<>();
        for (JsonNode row : iterable(list)) {
            String value = value(text(row, "value"));
            if (value == null) {
                continue;
            }
            String name = text(row, "name");
            String label = name.toLowerCase(Locale.ROOT);
            if (label.contains("electric range") || label.contains("pure electric") || label.contains("ev range")) {
                out.putIfAbsent("range", value);
                out.putIfAbsent("range_label", name);
                continue;
            }
            String field = DETAILS.get(label);
            if (field == null) {
                field = DETAILS.get(text(row, "key").toLowerCase(Locale.ROOT));
            }
            if (field != null) {
                out.putIfAbsent(field, value);
            }
        }
        return out;
    }

    /** The whole "Specifications" as the page shows it (values kept: "Yes", "No", "Optional", "2890" ...). */
    static List<SpecGroup> specGroups(JsonNode groupList) {
        List<SpecGroup> out = new ArrayList<>();
        for (JsonNode g : iterable(groupList)) {
            List<SpecGroup.Item> items = new ArrayList<>();
            for (JsonNode i : iterable(g.path("itemList"))) {
                String name = text(i, "name");
                String value = value(text(i, "value"));
                if (!name.isEmpty() && value != null) {
                    items.add(new SpecGroup.Item(name, value));
                }
            }
            out.add(new SpecGroup(text(g, "groupTitle"), items));
        }
        return SpecGroup.clean(out);
    }

    /** The "Features" names; entries are plain strings or objects with a name. */
    static List<String> highlights(JsonNode list) {
        Set<String> out = new LinkedHashSet<>();
        for (JsonNode h : iterable(list)) {
            String name = h.isTextual() ? h.asText() : firstNonBlank(text(h, "name"), text(h, "title"),
                    text(h, "highlightName"), text(h, "text"), text(h, "value"), text(h, "label"));
            if (name != null && value(name) != null) {
                out.add(name.trim());
            }
        }
        return List.copyOf(out);
    }

    /** The default port's current price; the first priced port when none is marked. */
    static BigDecimal price(JsonNode prices) {
        BigDecimal first = null;
        for (JsonNode p : iterable(prices)) {
            BigDecimal v = ValueParsers.parseNumber(text(p, "price"));
            if (v == null || v.signum() <= 0) {
                continue;
            }
            JsonNode def = p.path("isDefault");
            if (def.asBoolean(false) || def.asInt(0) == 1 || "1".equals(def.asText())) {
                return v;
            }
            if (first == null) {
                first = v;
            }
        }
        return first;
    }

    /** Price of the page's schema.org Car (USD), the fallback when rawData has none. */
    static BigDecimal ldPrice(Document doc) {
        for (Element script : doc.select("script[type=application/ld+json]")) {
            try {
                JsonNode node = JSON.readTree(script.data());
                for (JsonNode n : node.isArray() ? node : List.of(node)) {
                    if ("Car".equals(n.path("@type").asText()) || n.has("vehicleIdentificationNumber")) {
                        JsonNode offers = n.path("offers");
                        JsonNode offer = offers.isArray() ? offers.path(0) : offers;
                        String currency = offer.path("priceCurrency").asText("USD");
                        BigDecimal v = ValueParsers.parseNumber(offer.path("price").asText(""));
                        if (v != null && "USD".equalsIgnoreCase(currency)) {
                            return v;
                        }
                    }
                }
            } catch (JsonProcessingException e) {
                // not valid JSON-LD; ignore
            }
        }
        return null;
    }

    /** SOH in percent: a number, "91%", or an object with such a value. */
    static Integer batteryHealth(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            for (String key : List.of("soh", "sohValue", "health", "value", "batteryHealth", "score")) {
                Integer v = batteryHealth(node.get(key));
                if (v != null) {
                    return v;
                }
            }
            return null;
        }
        BigDecimal v = ValueParsers.parseNumber(node.asText(""));
        if (v == null) {
            return null;
        }
        if (v.compareTo(BigDecimal.ONE) <= 0 && v.scale() > 0) {
            v = v.multiply(BigDecimal.valueOf(100)); // 0.91
        }
        int pct = v.setScale(0, RoundingMode.HALF_UP).intValue();
        return pct > 0 && pct <= 100 ? pct : null;
    }

    /** Photo URLs (original size: without the CDN's processing query). */
    static List<String> images(JsonNode list) {
        Set<String> out = new LinkedHashSet<>();
        for (JsonNode img : iterable(list)) {
            String url = firstNonBlank(text(img, "imgUrl"), text(img, "url"), text(img, "smallImgUrl"));
            if (url == null) {
                continue;
            }
            if (url.startsWith("//")) {
                url = "https:" + url;
            }
            if (url.startsWith("http")) {
                out.add(url);
            }
        }
        return List.copyOf(out);
    }

    /** Download variants of a photo: the original (no query) first, then the URL as given. */
    public static List<String> imageCandidates(String url) {
        String clean = url.split("\\?")[0];
        return List.copyOf(new LinkedHashSet<>(List.of(clean, url)));
    }

    /** "Inspection: grade A; issues: Exterior 6, New Energy 1" for the admin (never shown to customers). */
    static String inspection(JsonNode report) {
        if (report == null || !report.isObject()) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        JsonNode base = report.path("baseInfo");
        String grade = firstNonBlank(text(base, "grade"), text(base, "level"), text(base, "gradeName"),
                text(base, "carLevel"));
        if (grade != null) {
            parts.add("grade " + grade);
        }
        List<String> issues = new ArrayList<>();
        for (JsonNode c : iterable(report.path("categoryList"))) {
            int abnormal = c.path("abnormalCount").asInt(0);
            if (abnormal > 0) {
                issues.add(text(c, "categoryName") + " " + abnormal);
            }
        }
        parts.add(issues.isEmpty() ? "no issues reported" : "issues: " + String.join(", ", issues));
        return "guazi inspection: " + String.join("; ", parts);
    }

    // ---------------------------------------------------------------- our fields

    /**
     * The spec sheet for the car page: equipment rows with "Yes" become "" (fitted), "No" / "Optional" rows are
     * left out (an option cannot be assumed to be fitted), every other row keeps its value.
     */
    static List<SpecGroup> sheet(List<SpecGroup> specs) {
        List<SpecGroup> out = new ArrayList<>();
        for (SpecGroup g : specs) {
            List<SpecGroup.Item> items = new ArrayList<>();
            for (SpecGroup.Item i : g.items()) {
                String v = i.value().trim();
                String low = v.toLowerCase(Locale.ROOT);
                if (NO.contains(low)) {
                    continue;
                }
                items.add(new SpecGroup.Item(i.name(), low.equals("yes") || low.equals("standard") ? "" : v));
            }
            out.add(new SpecGroup(g.name(), items));
        }
        return SpecGroup.clean(out);
    }

    /** "Features" plus the fitted equipment of "Specifications", as {@code FeatureMapper} phrases. */
    static List<String> phrases(ParsedPage page) {
        List<String> out = new ArrayList<>(page.highlights());
        for (SpecGroup g : page.specs()) {
            for (SpecGroup.Item i : g.items()) {
                String low = i.value().trim().toLowerCase(Locale.ROOT);
                if (NO.contains(low)) {
                    continue;
                }
                out.add(low.equals("yes") || low.equals("standard") ? i.name() : i.name() + ": " + i.value());
            }
        }
        return out;
    }

    /**
     * Total power in metric hp. Combustion cars: the engine's ps. Electric cars: the system (or all motors') kW.
     * Hybrids: the system's kW when given (the engine alone is not the car's power), else the engine's ps.
     */
    static Integer powerHp(ParsedPage page, FuelType fuel, String detailsPs) {
        Integer ps = intValue(firstNonBlank(detailsPs, page.spec("Maximum Horsepower (ps)")));
        Integer systemKw = intValue(page.spec("Maximum Power (kw)"));
        Integer motorsKw = intValue(page.spec("Total Motor Power (kw)"));
        boolean combustionOnly = fuel == FuelType.PETROL || fuel == FuelType.DIESEL || fuel == FuelType.LPG;
        Integer kw = fuel == FuelType.ELECTRIC && systemKw == null ? motorsKw : systemKw;
        if (combustionOnly && ps != null) {
            return ps;
        }
        if (kw != null) {
            return (int) Math.round(kw * 1.36);
        }
        return ps;
    }

    /** cm³ from "Displacement (ml)", or "Displacement (L)" × 1000; null for electric cars. */
    static Integer engineCc(ParsedPage page) {
        Integer ml = intValue(page.spec("Displacement (ml)"));
        if (ml != null && ml > 100) {
            return ml;
        }
        BigDecimal litres = ValueParsers.parseNumber(page.spec("Displacement (L)"));
        if (litres == null) {
            litres = ValueParsers.parseNumber(page.spec("Displacement"));
        }
        if (litres != null && litres.signum() > 0 && litres.compareTo(BigDecimal.TEN) < 0) {
            return litres.multiply(BigDecimal.valueOf(1000)).intValue();
        }
        return null;
    }

    /** "2025.04" -> "2025-04"; else "20250414" (licence date) -> "2025-04". */
    static String firstRegistration(String regDate, String licenseDate) {
        String v = value(regDate);
        if (v != null) {
            Matcher m = YEAR_MONTH.matcher(v);
            if (m.find()) {
                return m.group(1) + "-" + String.format("%02d", Integer.parseInt(m.group(2)));
            }
        }
        String l = value(licenseDate);
        if (l != null) {
            Matcher m = YYYYMMDD.matcher(l);
            if (m.find()) {
                return m.group(1) + "-" + m.group(2);
            }
        }
        return v;
    }

    /** The title after make, model and year: "Long-Range All-Wheel-Drive Version". */
    static String trim(String title, String make, String model, Integer year) {
        if (title == null) {
            return null;
        }
        String t = title;
        for (String part : new String[] {make, model, year == null ? null : String.valueOf(year)}) {
            if (part != null && t.toLowerCase(Locale.ROOT).startsWith(part.toLowerCase(Locale.ROOT))) {
                t = t.substring(part.length()).trim();
            }
        }
        return t.isEmpty() || t.equals(title) ? null : t;
    }

    /** "CLTC Electric Range (km)" -> "CLTC" */
    static String rangeStandard(String label) {
        if (label == null) {
            return null;
        }
        Matcher m = RANGE_STANDARD.matcher(label);
        return m.find() ? m.group(1).toUpperCase(Locale.ROOT) : null;
    }

    /** "4797*1920*1624" -> "4797 × 1920 × 1624" */
    static String dimensions(String raw) {
        String v = value(raw);
        return v == null ? null : v.replaceAll("\\s*[*xX×]\\s*", " × ");
    }

    private static String stripUsed(String title) {
        return title == null ? null : title.replaceFirst("(?i)^used\\s+", "");
    }

    private static Integer intValue(String raw) {
        BigDecimal n = decimal(raw);
        return n == null ? null : n.setScale(0, RoundingMode.HALF_UP).intValue();
    }

    private static BigDecimal decimal(String raw) {
        return ValueParsers.parseNumber(value(raw));
    }

    static String value(String raw) {
        if (raw == null) {
            return null;
        }
        String v = raw.replace(' ', ' ').trim();
        return EMPTY_VALUES.contains(v) ? null : v;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (value(v) != null) {
                return v.trim();
            }
        }
        return null;
    }

    private static Iterable<JsonNode> iterable(JsonNode node) {
        return node != null && node.isArray() ? node : List.of();
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return "";
        }
        JsonNode v = node.get(field);
        return v == null || v.isNull() || v.isContainerNode() ? "" : v.asText("").trim();
    }
}
