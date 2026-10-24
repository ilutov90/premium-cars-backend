package com.premiumcars.importing.source.autohome;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
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
import org.jsoup.parser.Parser;

import com.premiumcars.catalog.domain.CarFeature;
import com.premiumcars.catalog.domain.CarSpecs;
import com.premiumcars.catalog.domain.Drivetrain;
import com.premiumcars.catalog.domain.FuelType;
import com.premiumcars.catalog.domain.Transmission;
import com.premiumcars.importing.extract.EvSpecsExtractor;
import com.premiumcars.importing.extract.EvSpecsExtractor.EvSpecs;
import com.premiumcars.importing.extract.FeatureMapper;
import com.premiumcars.importing.extract.LabelValues;
import com.premiumcars.importing.extract.PageText;
import com.premiumcars.importing.extract.TitleParser;
import com.premiumcars.importing.extract.ValueParsers;

/**
 * Reads a global.autohome.com.cn (che168) listing page. No network: give it HTML, get data.
 * Port of AutohomeScraper from autowini_poster_parallel.py.
 */
public final class AutohomePageParser {

    /** Page label (lowercase) -> field key. */
    static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("mfg.date", "mfg_date"),
            Map.entry("mfg. date", "mfg_date"),
            Map.entry("1st reg. date", "reg_date"),
            Map.entry("first registration", "reg_date"),
            Map.entry("model year", "year"),
            Map.entry("mileage (km)", "km"),
            Map.entry("mileage", "km"),
            Map.entry("fuel type", "fuel"),
            Map.entry("engine (cc)", "engine_cc"),
            Map.entry("engine", "engine"),
            Map.entry("displacement", "engine_cc"),
            Map.entry("trans.", "transmission"),
            Map.entry("transmission", "transmission"),
            Map.entry("gearbox", "transmission"),
            Map.entry("steering", "steering"),
            Map.entry("location", "location"),
            Map.entry("drive train", "drivetrain"),
            Map.entry("drivetrain", "drivetrain"),
            Map.entry("body type", "body"),
            Map.entry("seats", "seats"),
            Map.entry("doors", "doors"),
            Map.entry("exterior color", "color"),
            Map.entry("color", "color"),
            Map.entry("curb weight (kg)", "weight"),
            Map.entry("dimensions (mm)", "dimensions"));

    /** Hint that a script or response carries electric drive data. */
    public static final Pattern EV_HINT = Pattern.compile("kwh|\\(ps\\)|motor|range", Pattern.CASE_INSENSITIVE);

    private static final Pattern IMAGE_URL = Pattern.compile(
            "(?:https?:)?//[a-z0-9.\\-]*autoimg\\.cn/[^\\s\"'<>\\\\)]+?\\.(?:jpe?g|png|webp)(?:\\.webp)?"
                    + "(?:\\?[^\\s\"'<>\\\\)]*)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern IMAGE_KEY = Pattern.compile("autohomecar__([A-Za-z0-9_\\-]+)");
    private static final Pattern GALLERY_TOTAL = Pattern.compile(">\\s*1\\s*/\\s*(\\d{1,3})\\s*<");
    private static final Pattern DETAIL_LINK = Pattern.compile("/detail/(\\d+)");
    private static final Pattern MONEY = Pattern.compile("(?:US\\s*)?\\$\\s*(\\d[\\d,]*(?:\\.\\d+)?)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PRICE_LABEL = Pattern.compile("^(selling\\s+)?price\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern YEAR_MONTH = Pattern.compile("((?:19|20)\\d{2})[.\\-/年](\\d{1,2})");
    private static final Set<String> EMPTY_VALUES = Set.of("-", "--", "—", "/", "N/A");

    /**
     * Everything read from one HTML document.
     *
     * @param galleryTotal photo count shown by the gallery ("1/24" -> 24), null when not shown
     * @param tokenLists   visible lines plus JSON tokens of scripts that mention motor/battery data
     * @param specSheet    the page's "Specifications" table (parameters and equipment of the model version)
     */
    public record ParsedPage(String title, Map<String, String> fields, BigDecimal priceUsd, List<String> images,
                             Integer galleryTotal, List<List<String>> tokenLists, AutohomeSpecSheet specSheet) {

        public FuelType fuelType() {
            FuelType f = FuelType.detect(fields.get("fuel"));
            return f != null ? f : FuelType.detect(title);
        }
    }

    private AutohomePageParser() {
    }

    /** https://global.autohome.com.cn/detail/123 -> .../en/detail/123 (English labels). */
    public static String englishUrl(String url) {
        Matcher m = DETAIL_LINK.matcher(url);
        if (url.contains("global.autohome.com.cn") && !url.contains("/en/") && m.find()) {
            return "https://global.autohome.com.cn/en/detail/" + m.group(1);
        }
        return url;
    }

    public static String listingId(String url) {
        Matcher m = DETAIL_LINK.matcher(url);
        if (m.find()) {
            return m.group(1);
        }
        Matcher info = Pattern.compile("infoid=(\\d+)", Pattern.CASE_INSENSITIVE).matcher(url);
        return info.find() ? info.group(1) : null;
    }

    public static ParsedPage parse(String html, String listingId) {
        Document doc = Jsoup.parse(html);
        String title = title(doc);
        List<String> lines = PageText.lines(doc);
        int start = title == null ? 0 : PageText.indexOf(lines, title.substring(0, Math.min(25, title.length())));
        Map<String, String> fields = LabelValues.parse(lines, start, LABELS);

        List<List<String>> tokenLists = new ArrayList<>();
        tokenLists.add(lines);
        for (Element script : doc.select("script")) {
            String text = script.data();
            if (EV_HINT.matcher(text).find()) {
                tokenLists.add(EvSpecsExtractor.jsonTokens(text));
            }
        }

        List<String> images = new ArrayList<>(collectImages(html, doc, listingId));
        Integer total = null;
        Matcher g = GALLERY_TOTAL.matcher(html);
        if (g.find()) {
            total = Integer.valueOf(g.group(1));
            if (images.size() > total) {
                images = new ArrayList<>(images.subList(0, total));
            }
        }
        return new ParsedPage(title, fields, priceUsd(lines, start, doc), images, total, tokenLists,
                AutohomeSpecSheet.parse(doc));
    }

    /** Builds our car data from the parsed page and the electric drive data found (possibly with a browser). */
    public static CarSpecs toSpecs(ParsedPage page, EvSpecs ev) {
        Map<String, String> f = page.fields();
        TitleParser.Parts parts = TitleParser.split(page.title());
        FuelType fuel = page.fuelType();

        CarSpecs.Builder b = CarSpecs.builder()
                .title(page.title())
                .make(parts.make())
                .model(parts.model())
                .trim(parts.trim())
                .year(year(f, page.title()))
                .firstRegistration(yearMonth(f.get("reg_date")))
                .mileageKm(ValueParsers.parseMileageKm(f.get("km")))
                .fuelType(fuel)
                .transmission(Transmission.detect(f.get("transmission")))
                .drivetrain(Drivetrain.detect(f.get("drivetrain")))
                .bodyType(value(f.get("body")))
                .seats(intValue(f.get("seats")))
                .doors(intValue(f.get("doors")))
                .color(value(f.get("color")))
                .curbWeightKg(intValue(f.get("weight")))
                .dimensions(dimensions(f.get("dimensions")));

        String engineCc = value(f.get("engine_cc"));
        Integer cc = intValue(engineCc);
        if (cc != null && cc > 100) {
            b.engineCc(cc);
        }
        String engine = value(f.get("engine"));
        if (engine == null && engineCc != null && (cc == null || cc <= 100)) {
            engine = engineCc; // e.g. "1.5T"
        }
        b.engine(engine);

        if (fuel != null && fuel.hasTractionBattery()) {
            b.batteryKwh(ev.batteryKwh())
                    .electricRangeKm(ev.electricRangeKm())
                    .rangeStandard(ev.rangeStandard())
                    .motorPowerKw(ev.motorPowerKw());
            if (fuel == FuelType.ELECTRIC) {
                b.powerHp(ev.powerHp()); // for hybrids the motor alone is not the system power
            }
            if (ev.motorPowerKw() == null && ev.powerHp() != null) {
                b.motorPowerKw((int) Math.round(ev.powerHp() / 1.36));
            }
        }
        fromSpecSheet(b, page.specSheet(), fuel);
        Set<CarFeature> features = EnumSet.noneOf(CarFeature.class);
        features.addAll(FeatureMapper.fromPhrases(page.specSheet().phrases()));
        features.addAll(FeatureMapper.fromSpecs(b.drivetrain(), b.seats(), b.doors(),
                FeatureMapper.rightHandDrive(f.get("steering"))));
        return b.features(features).sheet(page.specSheet().groups()).build();
    }

    /**
     * Fills what the vehicle details left empty from the specifications table: power, displacement, seats,
     * doors, drive, and the emission class mobile.bg asks for.
     */
    private static void fromSpecSheet(CarSpecs.Builder b, AutohomeSpecSheet sheet, FuelType fuel) {
        if (sheet.isEmpty()) {
            return;
        }
        if (b.powerHp() == null) {
            boolean combustionOnly = fuel == FuelType.PETROL || fuel == FuelType.DIESEL || fuel == FuelType.LPG;
            Integer ps = intValue(sheet.param("maximum horsepower (ps)"));
            Integer kw = intValue(sheet.param("maximum power (kw)")); // the system's total for hybrids
            if (combustionOnly && ps != null) {
                b.powerHp(ps);
            } else if (kw != null) {
                b.powerHp((int) Math.round(kw * 1.36));
            } else if (ps != null) {
                b.powerHp(ps);
            }
        }
        if (b.engineCc() == null) {
            Integer ml = intValue(sheet.param("displacement (ml)"));
            if (ml != null && ml > 100) {
                b.engineCc(ml);
            }
        }
        if (b.seats() == null) {
            b.seats(intValue(sheet.param("seating capacity")));
        }
        if (b.doors() == null) {
            b.doors(intValue(sheet.param("number of doors")));
        }
        if (b.drivetrain() == null) {
            b.drivetrain(Drivetrain.detect(sheet.param("drive type")));
        }
        b.euroStandard(FeatureMapper.euroStandard(sheet.param("emission standard")));
    }

    // ---------------------------------------------------------------- images

    /** Car photos of this listing (og:image first), without photos of "similar cars" linked from the page. */
    public static List<String> collectImages(String html, Document doc, String listingId) {
        Set<String> exclude = new HashSet<>();
        for (Element a : doc.select("a[href]")) {
            Matcher m = DETAIL_LINK.matcher(a.attr("href"));
            if (m.find() && !m.group(1).equals(listingId)) {
                for (Element img : a.select("img")) {
                    for (String attr : List.of("src", "data-src", "srcset")) {
                        for (String u : findImageUrls(img.attr(attr))) {
                            exclude.add(imageKey(u));
                        }
                    }
                }
            }
        }
        List<String> candidates = new ArrayList<>();
        Element og = doc.selectFirst("meta[property=og:image]");
        if (og != null) {
            candidates.addAll(findImageUrls(og.attr("content")));
        }
        candidates.addAll(findImageUrls(html));

        Set<String> seen = new HashSet<>();
        List<String> out = new ArrayList<>();
        for (String u : candidates) {
            String key = imageKey(u);
            if (!exclude.contains(key) && seen.add(key)) {
                out.add(u);
            }
        }
        return out;
    }

    /** All car photos from the autoimg.cn CDN, including escaped URLs inside Next.js data. */
    static List<String> findImageUrls(String raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        String txt = raw.replace("\\u002F", "/").replace("\\u002f", "/").replace("\\/", "/").replace("&amp;", "&");
        txt = Parser.unescapeEntities(txt, false);
        Set<String> out = new LinkedHashSet<>();
        Matcher m = IMAGE_URL.matcher(txt);
        while (m.find()) {
            String u = m.group();
            if (u.startsWith("//")) {
                u = "https:" + u;
            }
            String low = u.toLowerCase(Locale.ROOT);
            if (!low.contains("autohomecar__") && !low.contains("/escimg/")) {
                continue;
            }
            if (low.contains("logo") || low.contains("icon") || low.contains("avatar") || low.contains("brand")) {
                continue;
            }
            out.add(u);
        }
        return List.copyOf(out);
    }

    static String imageKey(String url) {
        Matcher m = IMAGE_KEY.matcher(url);
        if (m.find()) {
            return m.group(1);
        }
        String name = url.split("\\?")[0];
        name = name.substring(name.lastIndexOf('/') + 1).replaceFirst("^\\d+x\\d+_[^_]*_", "");
        return name.replaceAll("(?i)(\\.(jpe?g|png|webp))+$", "");
    }

    /** Download variants: large JPEG first, then the original URL. */
    public static List<String> imageCandidates(String url) {
        String clean = url.split("\\?")[0];
        String noWebp = clean.matches("(?i).*\\.(jpe?g|png)\\.webp$") ? clean.substring(0, clean.length() - 5) : clean;
        String big = noWebp.replaceFirst("/\\d+x\\d+_[^/]*?autohomecar__", "/1400x0_c42_autohomecar__");
        String bigWebp = clean.replaceFirst("/\\d+x\\d+_[^/]*?autohomecar__", "/1400x0_c42_autohomecar__");
        return List.copyOf(new LinkedHashSet<>(List.of(big, noWebp, bigWebp, clean, url)));
    }

    // ---------------------------------------------------------------- fields

    static String title(Document doc) {
        Element h1 = doc.selectFirst("h1");
        String title = h1 != null ? h1.text() : "";
        if (title.isBlank()) {
            Element og = doc.selectFirst("meta[property=og:title]");
            title = og != null ? og.attr("content") : doc.title();
            title = title.split("\\s[-|–]\\s")[0];
        }
        title = title.replaceAll("\\s+", " ").trim();
        return title.isEmpty() ? null : title;
    }

    static BigDecimal priceUsd(List<String> lines, int start, Document doc) {
        for (int i = start; i < lines.size(); i++) {
            if (PRICE_LABEL.matcher(lines.get(i)).lookingAt()) {
                BigDecimal sameLine = money(lines.get(i));
                if (sameLine != null) {
                    return sameLine;
                }
                BigDecimal nextLine = i + 1 < lines.size() ? money(lines.get(i + 1)) : null;
                if (nextLine != null) {
                    return nextLine;
                }
            }
        }
        for (String line : lines.subList(start, Math.min(lines.size(), start + 80))) {
            Matcher m = MONEY.matcher(line);
            if (m.find()) {
                BigDecimal v = ValueParsers.parseNumber(m.group(1));
                if (v != null && v.compareTo(BigDecimal.valueOf(500)) >= 0) {
                    return v;
                }
            }
        }
        for (String sel : List.of("meta[property=og:description]", "meta[name=description]")) {
            Element meta = doc.selectFirst(sel);
            if (meta != null) {
                Matcher m = MONEY.matcher(meta.attr("content"));
                if (m.find()) {
                    return ValueParsers.parseNumber(m.group(1));
                }
            }
        }
        return null;
    }

    private static BigDecimal money(String text) {
        Matcher m = MONEY.matcher(text);
        return m.find() ? ValueParsers.parseNumber(m.group(1)) : null;
    }

    private static Integer year(Map<String, String> f, String title) {
        Integer y = ValueParsers.parseYear(f.get("year"));
        if (y == null) {
            y = ValueParsers.parseYear(title);
        }
        if (y == null) {
            y = ValueParsers.parseYear(f.get("reg_date"));
        }
        return y;
    }

    /** "2020.10" -> "2020-10" */
    static String yearMonth(String raw) {
        String v = value(raw);
        if (v == null) {
            return null;
        }
        Matcher m = YEAR_MONTH.matcher(v);
        if (m.find()) {
            return m.group(1) + "-" + String.format("%02d", Integer.parseInt(m.group(2)));
        }
        return v;
    }

    /** "4980*1910*1495" -> "4980 × 1910 × 1495" */
    static String dimensions(String raw) {
        String v = value(raw);
        return v == null ? null : v.replaceAll("\\s*[*xX×]\\s*", " × ");
    }

    private static String value(String raw) {
        if (raw == null || EMPTY_VALUES.contains(raw.trim())) {
            return null;
        }
        return raw.trim();
    }

    private static Integer intValue(String raw) {
        BigDecimal n = ValueParsers.parseNumber(value(raw));
        return n == null ? null : n.intValue();
    }
}
