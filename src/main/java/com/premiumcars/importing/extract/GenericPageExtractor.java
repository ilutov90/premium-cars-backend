package com.premiumcars.importing.extract;

import static com.premiumcars.importing.extract.ValueParsers.firstNonBlank;
import static com.premiumcars.importing.extract.ValueParsers.isBlank;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.premiumcars.catalog.domain.CarSpecs;
import com.premiumcars.catalog.domain.FuelType;
import com.premiumcars.catalog.domain.Transmission;
import com.premiumcars.importing.ImportedCar;

/**
 * Extracts car data from an HTML page without knowing its exact structure. It tries, in order of reliability:
 * <ol>
 *     <li>JSON-LD (schema.org Car / Vehicle / Product)</li>
 *     <li>Next.js page data in {@code <script id="__NEXT_DATA__">} — global.autohome.com.cn is a Next.js site</li>
 *     <li>Open Graph meta tags</li>
 *     <li>Text heuristics on the visible page</li>
 * </ol>
 * The first source that has a value wins for each field. The result is always a draft that the admin reviews.
 * When a source site changes, tune the key lists below or add a dedicated parser in that source's adapter.
 */
public class GenericPageExtractor {

    private static final Logger log = LoggerFactory.getLogger(GenericPageExtractor.class);

    // Normalized JSON keys (lowercase, no "_" or "-") that usually hold each field.
    private static final Set<String> TITLE_KEYS = Set.of("carname", "carfullname", "fullname", "cartitle", "title", "specname");
    private static final Set<String> MAKE_KEYS = Set.of("brandname", "brand", "make", "makename", "maker");
    private static final Set<String> MODEL_KEYS = Set.of("seriesname", "series", "model", "modelname");
    private static final Set<String> YEAR_KEYS = Set.of("firstregyear", "registyear", "regyear", "modelyear", "year",
            "firstregdate", "firstregtime", "registdate", "firstregistration", "productionyear");
    private static final Set<String> MILEAGE_KEYS = Set.of("mileage", "mileagekm", "drivemileage", "kilometers", "km", "odometer", "mile");
    private static final Set<String> PRICE_KEYS = Set.of("usdprice", "priceusd", "fobprice", "saleprice", "sellprice", "carprice", "price");
    private static final Set<String> CURRENCY_KEYS = Set.of("currency", "pricecurrency", "currencycode");
    private static final Set<String> FUEL_KEYS = Set.of("fueltype", "fuel", "energytype", "fueltypename", "enginetype");
    private static final Set<String> GEARBOX_KEYS = Set.of("gearbox", "transmission", "gearboxtype", "gearboxname");
    private static final Set<String> COLOR_KEYS = Set.of("color", "colour", "carcolor", "colorname");
    private static final Set<String> ENGINE_KEYS = Set.of("displacement", "engine", "enginedisplacement");
    private static final Set<String> BODY_KEYS = Set.of("bodytype", "structure", "structurename", "levelname");
    private static final Set<String> DESCRIPTION_KEYS = Set.of("description", "remark", "carremark", "desc", "intro");
    private static final Set<String> IMAGE_LIST_KEYS = Set.of("images", "imagelist", "imglist", "pics", "piclist",
            "photos", "photolist", "carimages", "carpics", "imgurls", "gallery");

    private static final Pattern IMAGE_URL = Pattern.compile(
            "(?:https?:)?//[^\"'\\s<>()]+?\\.(?:jpe?g|png|webp)(?:\\.(?:jpe?g|png|webp))?(?:\\?[^\"'\\s<>()]*)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern TEXT_PRICE = Pattern.compile("(?:US\\$|USD|\\$)\\s?(\\d[\\d,]*(?:\\.\\d+)?)");
    private static final Pattern TEXT_MILEAGE = Pattern.compile("(\\d[\\d,.]*\\s?(?:万公里|km|KM|Km|kilometers|miles))");

    private final ObjectMapper mapper;

    public GenericPageExtractor(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public ImportedCar extract(Document doc, ExtractionHints hints) {
        Fields jsonLd = fromJsonLd(doc);
        Fields next = fromNextData(doc, hints);
        Fields og = fromOpenGraph(doc);
        Fields text = fromText(doc);

        String title = cleanTitle(firstNonBlank(jsonLd.title, next.title, og.title, text.title));
        String make = firstNonBlank(jsonLd.make, next.make);
        String model = firstNonBlank(jsonLd.model, next.model);
        if (title != null && (make == null || model == null)) {
            String[] guess = guessMakeModel(title);
            make = make != null ? make : guess[0];
            model = model != null ? model : guess[1];
        }

        Integer year = firstNonNull(jsonLd.year, next.year, ValueParsers.parseYear(title));
        Integer mileage = firstNonNull(jsonLd.mileageKm, next.mileageKm, text.mileageKm);
        BigDecimal price = firstNonNull(jsonLd.price, next.price, text.price);
        String currency = firstNonBlank(jsonLd.currency, next.currency, text.currency, hints.defaultCurrency());

        String fuelText = firstNonBlank(jsonLd.fuel, next.fuel);
        FuelType fuel = firstNonNull(FuelType.detect(fuelText), FuelType.detect(title));
        Transmission gearbox = firstNonNull(
                Transmission.detect(firstNonBlank(jsonLd.gearbox, next.gearbox)), Transmission.detect(title));

        LinkedHashSet<String> images = new LinkedHashSet<>();
        images.addAll(jsonLd.images);
        images.addAll(next.images);
        images.addAll(og.images);
        List<String> photos = images.stream()
                .filter(hints.isCarPhoto())
                .limit(hints.maxImages())
                .toList();

        CarSpecs specs = CarSpecs.builder()
                .title(title)
                .make(make)
                .model(model)
                .year(year)
                .mileageKm(mileage)
                .fuelType(fuel)
                .transmission(gearbox)
                .bodyType(firstNonBlank(jsonLd.body, next.body))
                .engine(firstNonBlank(jsonLd.engine, next.engine))
                .color(firstNonBlank(jsonLd.color, next.color))
                // The source's marketing text is not imported: the site writes its own description.
                .extraSpecs(specsFromHtml(doc))
                .build();

        return new ImportedCar(null, null, specs, price,
                currency == null ? null : currency.toUpperCase(Locale.ROOT), photos, List.of());
    }

    // ------------------------------------------------------------------ JSON-LD

    private Fields fromJsonLd(Document doc) {
        Fields f = new Fields();
        for (Element script : doc.select("script[type=application/ld+json]")) {
            try {
                JsonNode root = mapper.readTree(script.data());
                for (JsonNode node : candidates(root)) {
                    String type = node.path("@type").asText("").toLowerCase(Locale.ROOT);
                    if (!(type.contains("car") || type.contains("vehicle") || type.contains("product"))) {
                        continue;
                    }
                    f.title = firstNonBlank(f.title, text(node, "name"));
                    f.make = firstNonBlank(f.make, text(node.path("brand"), "name"), text(node, "brand"), text(node, "manufacturer"));
                    f.model = firstNonBlank(f.model, text(node, "model"));
                    f.year = firstNonNull(f.year, ValueParsers.parseYear(firstNonBlank(
                            text(node, "vehicleModelDate"), text(node, "productionDate"), text(node, "dateVehicleFirstRegistered"))));
                    JsonNode odo = node.path("mileageFromOdometer");
                    f.mileageKm = firstNonNull(f.mileageKm, ValueParsers.parseMileageKm(
                            firstNonBlank(text(odo, "value"), odo.isValueNode() ? odo.asText() : null)));
                    JsonNode offers = node.path("offers").isArray() ? node.path("offers").path(0) : node.path("offers");
                    f.price = firstNonNull(f.price, ValueParsers.parseNumber(text(offers, "price")));
                    f.currency = firstNonBlank(f.currency, text(offers, "priceCurrency"));
                    f.fuel = firstNonBlank(f.fuel, text(node, "fuelType"));
                    f.gearbox = firstNonBlank(f.gearbox, text(node, "vehicleTransmission"));
                    f.color = firstNonBlank(f.color, text(node, "color"));
                    f.body = firstNonBlank(f.body, text(node, "bodyType"));
                    f.description = firstNonBlank(f.description, text(node, "description"));
                    collectImageUrls(node.path("image").toString(), f.images);
                }
            } catch (Exception e) {
                log.debug("Ignoring invalid JSON-LD block: {}", e.getMessage());
            }
        }
        return f;
    }

    private static List<JsonNode> candidates(JsonNode root) {
        List<JsonNode> list = new ArrayList<>();
        if (root.isArray()) {
            root.forEach(list::add);
        } else {
            list.add(root);
            root.path("@graph").forEach(list::add);
        }
        return list;
    }

    // ------------------------------------------------------------------ Next.js __NEXT_DATA__

    private Fields fromNextData(Document doc, ExtractionHints hints) {
        Fields f = new Fields();
        Element script = doc.selectFirst("script#__NEXT_DATA__");
        if (script == null) {
            return f;
        }
        JsonNode root;
        try {
            root = mapper.readTree(script.data());
        } catch (Exception e) {
            log.debug("Invalid __NEXT_DATA__: {}", e.getMessage());
            return f;
        }
        // Page data lives under props.pageProps; starting there skips layout/menu noise.
        JsonNode start = root.path("props").path("pageProps");
        if (start.isMissingNode() || start.isEmpty()) {
            start = root;
        }

        Map<String, String> found = firstValuesByKey(start);
        f.title = pick(found, TITLE_KEYS);
        f.make = pick(found, MAKE_KEYS);
        f.model = pick(found, MODEL_KEYS);
        f.year = ValueParsers.parseYear(pick(found, YEAR_KEYS));
        f.mileageKm = parseNextMileage(pick(found, MILEAGE_KEYS));
        String priceText = pick(found, PRICE_KEYS);
        f.price = ValueParsers.parseNumber(priceText);
        f.currency = pick(found, CURRENCY_KEYS);
        if (f.currency == null && priceText != null && priceText.contains("万")) {
            f.currency = "CNY";
        }
        f.fuel = pick(found, FUEL_KEYS);
        f.gearbox = pick(found, GEARBOX_KEYS);
        f.color = pick(found, COLOR_KEYS);
        f.engine = pick(found, ENGINE_KEYS);
        f.body = pick(found, BODY_KEYS);
        f.description = pick(found, DESCRIPTION_KEYS);

        // Prefer an explicit photo list; fall back to every image URL in the page data.
        JsonNode imageList = firstArrayByKey(start, IMAGE_LIST_KEYS);
        if (imageList != null) {
            collectImageUrls(imageList.toString(), f.images);
        }
        if (f.images.stream().noneMatch(hints.isCarPhoto())) {
            collectImageUrls(start.toString(), f.images);
        }
        return f;
    }

    /**
     * Che168/Autohome often state mileage in units of 10,000 km ("3.5" meaning 35,000 km).
     * A small decimal number is therefore read as "wan km".
     */
    private static Integer parseNextMileage(String text) {
        if (text == null) {
            return null;
        }
        BigDecimal n = ValueParsers.parseNumber(text);
        if (n != null && !text.contains("万") && n.compareTo(BigDecimal.valueOf(100)) < 0 && n.scale() > 0) {
            return n.multiply(BigDecimal.valueOf(10_000)).intValue();
        }
        return ValueParsers.parseMileageKm(text);
    }

    /** Breadth-first walk: for each normalized key, remember the first non-empty scalar value (shallowest wins). */
    private static Map<String, String> firstValuesByKey(JsonNode start) {
        Map<String, String> found = new HashMap<>();
        Deque<JsonNode> queue = new ArrayDeque<>();
        queue.add(start);
        int visited = 0;
        while (!queue.isEmpty() && visited++ < 50_000) {
            JsonNode node = queue.poll();
            if (node.isObject()) {
                for (Map.Entry<String, JsonNode> e : node.properties()) {
                    JsonNode v = e.getValue();
                    if (v.isValueNode()) {
                        String s = v.asText();
                        if (!isBlank(s)) {
                            found.putIfAbsent(normalizeKey(e.getKey()), s);
                        }
                    } else {
                        queue.add(v);
                    }
                }
            } else if (node.isArray()) {
                node.forEach(queue::add);
            }
        }
        return found;
    }

    private static JsonNode firstArrayByKey(JsonNode start, Set<String> keys) {
        Deque<JsonNode> queue = new ArrayDeque<>();
        queue.add(start);
        int visited = 0;
        while (!queue.isEmpty() && visited++ < 50_000) {
            JsonNode node = queue.poll();
            if (node.isObject()) {
                for (Map.Entry<String, JsonNode> e : node.properties()) {
                    if (e.getValue().isArray() && !e.getValue().isEmpty() && keys.contains(normalizeKey(e.getKey()))) {
                        return e.getValue();
                    }
                    if (e.getValue().isContainerNode()) {
                        queue.add(e.getValue());
                    }
                }
            } else if (node.isArray()) {
                node.forEach(queue::add);
            }
        }
        return null;
    }

    private static String pick(Map<String, String> found, Set<String> keys) {
        // Keys are checked in a stable order of preference: longer (more specific) names first.
        return keys.stream()
                .sorted((a, b) -> Integer.compare(b.length(), a.length()))
                .map(found::get)
                .filter(v -> !isBlank(v))
                .findFirst()
                .orElse(null);
    }

    private static String normalizeKey(String key) {
        return key.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
    }

    // ------------------------------------------------------------------ Open Graph & text

    private static Fields fromOpenGraph(Document doc) {
        Fields f = new Fields();
        f.title = meta(doc, "og:title");
        f.description = firstNonBlank(meta(doc, "og:description"), meta(doc, "description"));
        for (Element e : doc.select("meta[property=og:image], meta[name=og:image]")) {
            collectImageUrls(e.attr("content"), f.images);
        }
        return f;
    }

    private static Fields fromText(Document doc) {
        Fields f = new Fields();
        Element h1 = doc.selectFirst("h1");
        f.title = firstNonBlank(h1 != null ? h1.text() : null, doc.title());
        String body = doc.body() != null ? doc.body().text() : "";
        Matcher price = TEXT_PRICE.matcher(body);
        if (price.find()) {
            f.price = ValueParsers.parseNumber(price.group(1));
            f.currency = "USD";
        }
        Matcher mileage = TEXT_MILEAGE.matcher(body);
        if (mileage.find()) {
            f.mileageKm = ValueParsers.parseMileageKm(mileage.group(1));
        }
        return f;
    }

    /** Label/value pairs from definition lists and two-column tables, e.g. "Engine: 2.0T". */
    private static Map<String, String> specsFromHtml(Document doc) {
        Map<String, String> specs = new LinkedHashMap<>();
        for (Element dt : doc.select("dl dt")) {
            Element dd = dt.nextElementSibling();
            if (dd != null && "dd".equals(dd.tagName())) {
                putSpec(specs, dt.text(), dd.text());
            }
        }
        for (Element row : doc.select("table tr")) {
            List<Element> cells = row.children().stream()
                    .filter(c -> "th".equals(c.tagName()) || "td".equals(c.tagName()))
                    .toList();
            if (cells.size() == 2) {
                putSpec(specs, cells.get(0).text(), cells.get(1).text());
            }
        }
        return specs;
    }

    private static void putSpec(Map<String, String> specs, String label, String value) {
        if (specs.size() >= 80 || isBlank(label) || isBlank(value) || label.length() > 40 || value.length() > 200) {
            return;
        }
        specs.putIfAbsent(label.replaceAll("[:：]\\s*$", "").trim(), value.trim());
    }

    // ------------------------------------------------------------------ helpers

    static void collectImageUrls(String text, Set<String> target) {
        if (text == null) {
            return;
        }
        Matcher m = IMAGE_URL.matcher(text.replace("\\/", "/").replace("\\u002F", "/"));
        while (m.find()) {
            String url = m.group();
            target.add(url.startsWith("//") ? "https:" + url : url);
        }
    }

    private static String meta(Document doc, String name) {
        Element e = doc.selectFirst("meta[property=" + name + "], meta[name=" + name + "]");
        return e == null ? null : e.attr("content");
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isValueNode() ? v.asText() : null;
    }

    /** "BMW 3 Series 2021 325Li - Autohome" -> "BMW 3 Series 2021 325Li" */
    static String cleanTitle(String title) {
        if (title == null) {
            return null;
        }
        String t = title.split("\\s[|–-]\\s")[0].trim();
        // Autohome repeats the brand: "Audi Audi A3" -> "Audi A3"
        String[] words = t.split("\\s+");
        if (words.length > 1 && words[0].equalsIgnoreCase(words[1])) {
            t = t.substring(words[0].length()).trim();
        }
        return t.isEmpty() ? null : t;
    }

    static String[] guessMakeModel(String title) {
        String[] words = title.split("\\s+");
        String make = words.length > 0 ? words[0] : null;
        String model = words.length > 1 && ValueParsers.parseYear(words[1]) == null ? words[1] : null;
        return new String[]{make, model};
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T v : values) {
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    /** Mutable holder for one extraction strategy's results. */
    private static final class Fields {
        String title;
        String make;
        String model;
        Integer year;
        Integer mileageKm;
        BigDecimal price;
        String currency;
        String fuel;
        String gearbox;
        String color;
        String engine;
        String body;
        String description;
        final Set<String> images = new LinkedHashSet<>();
    }
}
