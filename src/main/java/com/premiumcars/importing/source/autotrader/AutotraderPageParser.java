package com.premiumcars.importing.source.autotrader;

import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
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
import com.premiumcars.importing.ListingGoneException;
import com.premiumcars.importing.extract.FeatureMapper;

/**
 * Reads an autotrader.ca listing page (www.autotrader.ca/offers/...). No network: give it HTML, get data.
 * <p>
 * The site runs on the AutoScout24 platform (Next.js pages router): the page's
 * {@code <script id="__NEXT_DATA__">} holds {@code props.pageProps.listingDetails}, the whole listing:
 * {@code vehicle} (make, model, year, km, fuel, gearbox, engine, power, body, equipment by category),
 * {@code prices.public.priceRaw} (CAD), {@code images[]} (photo URLs with a size suffix), the seller's free-text
 * {@code description} and {@code status}. A removed listing redirects to a results page: no {@code listingDetails}.
 * Phone numbers, the dealer's name and the carfax link are not read.
 */
public final class AutotraderPageParser {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern UUID = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", Pattern.CASE_INSENSITIVE);
    /** "/offers/bmw-x3-...-566d07ca-...-bdbf" or "/offers/x-566d07ca-..." */
    private static final Pattern OFFER = Pattern.compile("/offers/(?:[^/?#]*-)?(" + UUID.pattern() + ")(?:[/?#]|$)",
            Pattern.CASE_INSENSITIVE);
    /** ".../{listingId}_{photoId}.jpg/1280x960.webp?promo=..." -> the part before the size */
    private static final Pattern IMAGE_SIZE = Pattern.compile("/\\d+x\\d+\\.(?:webp|jpe?g|png)(?:\\?.*)?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PHRASE_SPLIT = Pattern.compile("[,;\\n!|]+");
    private static final double HP_PER_KW = 1.36;
    /** Dealers enter 1 (or a few dollars) for "call for price" / conditional stock. */
    static final BigDecimal MIN_REAL_PRICE = BigDecimal.valueOf(100);

    /**
     * Everything read from one page.
     *
     * @param id       the listing's UUID
     * @param specs    the car's data
     * @param priceCad the price in CAD, or null when the seller shows none (call for price)
     * @param images   photo URLs without the size suffix (the 1600x1200 originals), the page's order
     * @param location "City, PROVINCE" of the listing (admin only)
     * @param listedOn the day the listing was created on the site (yyyy-mm-dd), or null
     */
    public record ParsedPage(String id, CarSpecs specs, BigDecimal priceCad, List<String> images, String location,
                             String listedOn) {
    }

    private AutotraderPageParser() {
    }

    /** The listing's UUID in an /offers/ link, lower case, or null when it is not an offer link. */
    public static String listingId(String url) {
        Matcher m = OFFER.matcher(url == null ? "" : url);
        return m.find() ? m.group(1).toLowerCase(Locale.ROOT) : null;
    }

    /** The stable link of a listing (the site redirects it to its own slug). */
    public static URI canonicalUrl(String id) {
        return URI.create("https://www.autotrader.ca/offers/x-" + id);
    }

    /** True when the HTML carries listing data (cheap check, before parsing). */
    public static boolean hasListing(String html) {
        return html != null && html.contains("\"listingDetails\"");
    }

    /**
     * @param expectedId the UUID the link names; a page of another listing is refused when given
     * @throws ListingGoneException     the page is a results page (the listing was removed) or the listing is not
     *                                  {@code Active}
     * @throws IllegalArgumentException the page is not an autotrader page with data
     */
    public static ParsedPage parse(String html, String expectedId) {
        JsonNode pageProps = pageProps(html);
        JsonNode d = pageProps.path("listingDetails");
        if (!d.isObject()) {
            throw new ListingGoneException("autotrader.ca shows no listing for this link any more (removed or sold)");
        }
        String status = text(d, "status");
        if (!status.isEmpty() && !status.equalsIgnoreCase("Active")) {
            throw new ListingGoneException("The autotrader.ca listing is not active (status " + status + ")");
        }
        String id = text(d, "id").toLowerCase(Locale.ROOT);
        if (id.isEmpty()) {
            throw new IllegalArgumentException("The listing has no id");
        }
        if (expectedId != null && !expectedId.equalsIgnoreCase(id)) {
            throw new IllegalArgumentException("The page is listing " + id + ", not " + expectedId);
        }
        JsonNode v = d.path("vehicle");

        FuelType fuel = fuel(v, d);
        Integer year = integer(v.path("modelYear"));
        String make = clean(text(v, "make"));
        String model = clean(text(v, "model"));
        String trim = trim(v);
        String title = clean(text(d, "title"));
        if (title == null) {
            title = join(year == null ? null : String.valueOf(year), make, model, trim);
        }
        Drivetrain drivetrain = Drivetrain.detect(text(v, "driveTrain"));
        Integer seats = integer(v.path("numberOfSeats"));
        Integer doors = integer(v.path("numberOfDoors"));

        CarSpecs.Builder b = CarSpecs.builder()
                .title(title)
                .make(make)
                .model(model)
                .trim(trim)
                .year(year)
                .mileageKm(integer(v.path("mileageInKmRaw")))
                .fuelType(fuel)
                .transmission(Transmission.detect(firstNonBlank(text(v, "transmissionType"),
                        text(v, "transmissionTypeId").equals("A") ? "automatic" : "")))
                .drivetrain(drivetrain)
                .engineCc(integer(v.path("rawDisplacementInCCM")))
                .engine(engine(v, fuel))
                .bodyType(clean(text(v, "bodyType")))
                .seats(seats)
                .doors(doors)
                .color(clean(firstNonBlank(text(v, "bodyColor"), text(v, "bodyColorRaw"))));

        Integer kw = integer(v.path("rawPowerInKw"));
        Integer hp = integer(v.path("rawPowerInHp"));
        b.powerHp(hp != null ? hp : kw == null ? null : (int) Math.round(kw * HP_PER_KW));
        if (fuel != null && fuel.hasTractionBattery()) {
            b.motorPowerKw(kw != null ? kw : hp == null ? null : (int) Math.round(hp / HP_PER_KW));
        }

        Map<String, List<String>> equipment = equipment(v.path("equipment"));
        List<String> phrases = new ArrayList<>();
        equipment.values().forEach(phrases::addAll);
        phrases.addAll(descriptionPhrases(text(d, "description")));
        Set<CarFeature> features = EnumSet.noneOf(CarFeature.class);
        features.addAll(FeatureMapper.fromPhrases(phrases));
        features.addAll(FeatureMapper.fromSpecs(drivetrain, seats, doors, null));
        b.features(features).sheet(sheet(v, equipment));

        return new ParsedPage(id, b.build(), price(d), images(d.path("images")), location(d),
                listedOn(text(d, "createdTimestampWithOffset")));
    }

    // ---------------------------------------------------------------- page

    private static JsonNode pageProps(String html) {
        Element script = Jsoup.parse(html == null ? "" : html).selectFirst("script#__NEXT_DATA__");
        if (script == null) {
            throw new IllegalArgumentException("No __NEXT_DATA__ in the page (not an autotrader.ca page?)");
        }
        try {
            JsonNode props = JSON.readTree(script.data()).path("props").path("pageProps");
            if (!props.isObject()) {
                throw new IllegalArgumentException("The page data has no pageProps");
            }
            return props;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("The page data is not valid JSON: " + e.getOriginalMessage(), e);
        }
    }

    // ---------------------------------------------------------------- fields

    /**
     * {@code fuelCategory.raw}: B gasoline, D diesel, E electric, 2 gas/electric hybrid (plug-in when the data says
     * so), C natural gas, F flex fuel, L propane, O other. {@code vehicleType} says "ice" even for an EV: not used.
     */
    static FuelType fuel(JsonNode v, JsonNode listing) {
        String raw = text(v.path("fuelCategory"), "raw");
        String formatted = text(v.path("fuelCategory"), "formatted");
        boolean plugIn = v.path("rawData").path("fuels").path("isPluginHybrid").asBoolean(false)
                || formatted.toLowerCase(Locale.ROOT).contains("plug");
        return switch (raw) {
            case "B", "F" -> FuelType.PETROL;
            case "D" -> FuelType.DIESEL;
            case "E" -> FuelType.ELECTRIC;
            case "2" -> plugIn ? FuelType.PLUGIN_HYBRID : FuelType.HYBRID;
            case "L" -> FuelType.LPG;
            case "C", "O" -> FuelType.OTHER;
            default -> FuelType.detect(formatted);
        };
    }

    /**
     * The model is autotrader's own menu model ({@code vehicle.model}, "X3"). The trim is the menu's motor type
     * ("xDrive30i"); without one, the seller's free text ({@code modelVersionInput}) only when it is a short plain
     * name: dealers put equipment lists there ("QUATTRO|NAV|MASSAGE|..."), which go to the sheet instead.
     */
    static String trim(JsonNode v) {
        String motor = clean(text(v, "motorTypeName"));
        if (motor != null) {
            return motor;
        }
        String input = sellerTrimNote(v);
        return input != null && input.length() <= 30 && !input.contains("|") ? input : null;
    }

    private static String sellerTrimNote(JsonNode v) {
        return clean(firstNonBlank(text(v, "modelVersionInput"), text(v, "modelVersionCustom")));
    }

    private static String engine(JsonNode v, FuelType fuel) {
        if (fuel == FuelType.ELECTRIC) {
            return null;
        }
        String size = clean(text(v, "cylinderCapacity"));
        Integer cylinders = integer(v.path("cylinders"));
        if (size == null && cylinders == null) {
            return null;
        }
        return join(size, cylinders == null ? null : cylinders + " cyl.");
    }

    /** The public price in CAD; null for "call for price" (priceRaw 1 and the like) or an on-request price. */
    static BigDecimal price(JsonNode d) {
        JsonNode p = d.path("prices").path("public");
        JsonNode raw = p.path("priceRaw");
        if (!raw.isNumber()) {
            raw = d.path("prices").path("dealer").path("priceRaw");
        }
        if (!raw.isNumber() || p.path("onRequestOnly").asBoolean(false)) {
            return null;
        }
        BigDecimal price = raw.decimalValue();
        return price.compareTo(MIN_REAL_PRICE) < 0 ? null : price;
    }

    /** The photo URLs without the size suffix: that URL answers the original (1600x1200), the suffix only scales. */
    static List<String> images(JsonNode images) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (images.isArray()) {
            for (JsonNode n : images) {
                String url = n.asText("").trim();
                if (!url.isEmpty()) {
                    out.add(originalImage(url));
                }
            }
        }
        return List.copyOf(out);
    }

    public static String originalImage(String url) {
        return IMAGE_SIZE.matcher(url).replaceFirst("");
    }

    private static String location(JsonNode d) {
        String city = clean(text(d.path("location"), "city"));
        String region = clean(firstNonBlank(text(d.path("location"), "provinceCode"),
                text(d.path("seller").path("dealer"), "region")));
        return joinWith(city == null ? null : titleCase(city),
                region == null ? null : region.toUpperCase(Locale.ROOT), ", ");
    }

    private static String listedOn(String timestamp) {
        return timestamp.length() >= 10 && timestamp.charAt(4) == '-' ? timestamp.substring(0, 10) : null;
    }

    // ---------------------------------------------------------------- equipment

    /** category name -> the equipment names of {@code vehicle.equipment}, in the page's order. */
    static Map<String, List<String>> equipment(JsonNode equipment) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (!equipment.isObject()) {
            return out;
        }
        for (JsonNode category : equipment) {
            if (!category.isArray()) {
                continue;
            }
            for (JsonNode item : category) {
                String name = clean(text(item, "id"));
                if (name == null) {
                    continue;
                }
                String group = clean(text(item, "categoryName"));
                out.computeIfAbsent(group == null ? "Equipment" : group, k -> new ArrayList<>()).add(name);
            }
        }
        return out;
    }

    /** The seller's HTML description as phrases for {@code FeatureMapper} (it lists the equipment in capitals). */
    static List<String> descriptionPhrases(String html) {
        if (html.isBlank()) {
            return List.of();
        }
        String plain = Jsoup.parse(html.replaceAll("(?i)<br\\s*/?>", ",")).text();
        List<String> out = new ArrayList<>();
        for (String s : PHRASE_SPLIT.split(plain)) {
            String p = s.trim();
            if (!p.isEmpty() && p.length() <= 80) {
                out.add(p);
            }
        }
        return out;
    }

    /**
     * The sheet: the seller's equipment by category (English, as the site shows it) and a "Vehicle" group with the
     * few details that have no field of ours. Read-only like the other sources' sheets.
     */
    static List<SpecGroup> sheet(JsonNode v, Map<String, List<String>> equipment) {
        List<SpecGroup> groups = new ArrayList<>();
        List<SpecGroup.Item> vehicle = new ArrayList<>();
        addItem(vehicle, "Cylinders", text(v, "cylinders"));
        addItem(vehicle, "Gears", text(v, "gears"));
        addItem(vehicle, "Upholstery", text(v, "upholstery"));
        addItem(vehicle, "Interior colour", text(v, "upholsteryColor"));
        String note = sellerTrimNote(v);
        if (note != null && !note.equals(trim(v))) {
            addItem(vehicle, "Seller's version note", note);
        }
        if (!vehicle.isEmpty()) {
            groups.add(new SpecGroup("Vehicle", vehicle));
        }
        equipment.forEach((name, items) -> groups.add(new SpecGroup(name,
                items.stream().map(i -> new SpecGroup.Item(i, "")).toList())));
        return SpecGroup.clean(groups);
    }

    private static void addItem(List<SpecGroup.Item> items, String name, String value) {
        String v = clean(value);
        if (v != null) {
            items.add(new SpecGroup.Item(name, v));
        }
    }

    // ---------------------------------------------------------------- helpers

    private static Integer integer(JsonNode n) {
        return n != null && n.isNumber() ? Integer.valueOf((int) Math.round(n.asDouble())) : null;
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return "";
        }
        JsonNode v = node.get(field);
        return v == null || v.isNull() || v.isContainerNode() ? "" : v.asText("").trim();
    }

    private static String clean(String s) {
        if (s == null) {
            return null;
        }
        String v = s.replace(' ', ' ').replaceAll("\\s+", " ").trim();
        return v.isEmpty() ? null : v;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private static String join(String... parts) {
        List<String> p = new ArrayList<>();
        for (String s : parts) {
            if (s != null && !s.isBlank()) {
                p.add(s.trim());
            }
        }
        return p.isEmpty() ? null : String.join(" ", p);
    }

    private static String joinWith(String a, String b, String separator) {
        if (a == null || b == null) {
            return a != null ? a : b;
        }
        return a + separator + b;
    }

    private static String titleCase(String s) {
        StringBuilder out = new StringBuilder();
        boolean start = true;
        for (char c : s.toLowerCase(Locale.ROOT).toCharArray()) {
            out.append(start ? Character.toUpperCase(c) : c);
            start = c == ' ' || c == '-' || c == '\'';
        }
        return out.toString();
    }
}
