package com.premiumcars.importing.extract;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds electric drive data (motor power, battery capacity, electric range) in lists of text tokens:
 * visible page lines, browser text of the "Electric Motor" / "Battery && Charging" tabs, or strings pulled
 * out of JSON. Labels are recognised by keywords, not exact text, so small wording changes on the source
 * site do not break it. Port of extract_ev_specs() from autowini_poster_parallel.py.
 */
public final class EvSpecsExtractor {

    /**
     * @param powerHp         total motor power in metric hp (from hp, or kW x 1.36)
     * @param motorPowerKw    total motor power in kW
     * @param rangeStandard   CLTC, NEDC, WLTC, WLTP or EPA when the label names it
     */
    public record EvSpecs(Integer powerHp, Integer motorPowerKw, BigDecimal batteryKwh, Integer electricRangeKm,
                          String rangeStandard) {

        public static final EvSpecs NONE = new EvSpecs(null, null, null, null, null);

        public boolean complete() {
            return powerHp != null && batteryKwh != null && electricRangeKm != null;
        }

        /** Fills the gaps of this result with values from {@code other}. */
        public EvSpecs or(EvSpecs other) {
            return new EvSpecs(
                    powerHp != null ? powerHp : other.powerHp,
                    motorPowerKw != null ? motorPowerKw : other.motorPowerKw,
                    batteryKwh != null ? batteryKwh : other.batteryKwh,
                    electricRangeKm != null ? electricRangeKm : other.electricRangeKm,
                    rangeStandard != null ? rangeStandard : other.rangeStandard);
        }
    }

    private record Rule(String key, Pattern pattern) {
    }

    private record Found(double value, String label) {
    }

    /** Checked in this order; the first matching rule names the label. */
    private static final List<Rule> RULES = List.of(
            rule("hp_total", "(total|combined|system).*(motor|electric).*(\\(ps\\)|\\(hp\\)|horsepower)"
                    + "|(motor|electric).*total.*(\\(ps\\)|\\(hp\\)|horsepower)"),
            rule("hp_front", "front.*motor.*(\\(ps\\)|\\(hp\\)|horsepower)"),
            rule("hp_rear", "rear.*motor.*(\\(ps\\)|\\(hp\\)|horsepower)"),
            rule("hp_any", "(motor|electric).*(\\(ps\\)|\\(hp\\)|horsepower)|^horsepower|^\\(ps\\)"),
            rule("kw_total", "(total|combined|system).*(motor|electric).*power.*\\(kw\\)"
                    + "|(motor|electric).*total.*power.*\\(kw\\)"),
            rule("kw_front", "front.*motor.*power.*\\(kw\\)"),
            rule("kw_rear", "rear.*motor.*power.*\\(kw\\)"),
            rule("kw_any", "motor.*power.*\\(kw\\)"),
            rule("battery_kwh", "batter(y|ies).*(capacity|energy|kwh)|\\(kwh\\).*batter"),
            rule("range_km", "(cltc|nedc|wltc|wltp|epa|cltp).*range"
                    + "|electric.*range|range.*\\((km|cltc|nedc|wltc|wltp)\\)"
                    + "|^range\\b|driving\\s*range|endurance"));

    /** Labels that look like ours but are not (warranty, charging time, speed ...). */
    private static final Pattern EXCLUDE = Pattern.compile(
            "warrant|time|hour|minute|port|location|cool|heat|brand|\\btype\\b|"
                    + "accelerat|0-100|speed|torque|consumption|n·m|n\\.m|nm\\)|"
                    + "density|voltage|current|percent|%|number|layout|weight|extender|engine",
            Pattern.CASE_INSENSITIVE);

    /** Words that are JSON keys, not values. */
    private static final Set<String> JSON_KEYWORDS = Set.of("name", "value", "values", "title", "label", "text",
            "children", "key", "id", "type", "unit", "content", "desc", "item", "items", "classname", "data",
            "list", "groupname", "paramname", "paramvalue");

    private static final Set<String> EMPTY_VALUES = Set.of("-", "--", "—", "/", "●", "○");
    private static final Pattern INLINE = Pattern.compile("^(.{3,80}?)\\s*[:：]\\s*(\\S.*)$");
    private static final Pattern STANDARD = Pattern.compile("(cltc|nedc|wltc|wltp|epa)", Pattern.CASE_INSENSITIVE);
    private static final Pattern THOUSANDS = Pattern.compile("\\d,\\d{3}\\b");
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:[.,]\\d+)?");
    private static final Pattern HAS_LETTER = Pattern.compile("[a-z]");
    private static final Pattern SIGNED_NUMBER = Pattern.compile("-?\\d+(?:\\.\\d+)?");

    private EvSpecsExtractor() {
    }

    private static Rule rule(String key, String regex) {
        return new Rule(key, Pattern.compile(regex, Pattern.CASE_INSENSITIVE));
    }

    /** The rule key for a label, or null when the text is not one of our labels. */
    static String labelKey(String label) {
        String low = label.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        if (low.length() > 90 || !HAS_LETTER.matcher(low).find() || EXCLUDE.matcher(low).find()) {
            return null;
        }
        for (Rule r : RULES) {
            if (r.pattern().matcher(low).find()) {
                return r.key();
            }
        }
        return null;
    }

    static Double firstNumber(String value) {
        String s = value;
        if (THOUSANDS.matcher(s).find()) {
            s = s.replace(",", "");
        }
        Matcher m = NUMBER.matcher(s);
        if (!m.find()) {
            return null;
        }
        try {
            return Double.parseDouble(m.group().replace(",", "."));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static EvSpecs extract(List<List<String>> tokenLists) {
        Map<String, Found> found = new HashMap<>();
        for (List<String> tokens : tokenLists) {
            int n = tokens.size();
            for (int i = 0; i < n; i++) {
                String tok = tokens.get(i);
                String key;
                String label;
                List<String> cand = new ArrayList<>();
                Matcher inline = INLINE.matcher(tok);
                if (inline.matches() && labelKey(inline.group(1)) != null) {
                    label = inline.group(1);
                    key = labelKey(label);
                    cand.add(inline.group(2));
                } else {
                    key = labelKey(tok);
                    if (key == null) {
                        continue;
                    }
                    label = tok;
                    for (int j = i + 1; j < Math.min(i + 7, n); j++) {
                        String next = tokens.get(j);
                        if (next.isEmpty() || JSON_KEYWORDS.contains(next.toLowerCase(Locale.ROOT))) {
                            continue;
                        }
                        if (labelKey(next) != null) {
                            break; // the next label starts: this one has no value
                        }
                        cand.add(next);
                        break;
                    }
                }
                if (found.containsKey(key) || cand.isEmpty()) {
                    continue;
                }
                String val = cand.getFirst().trim();
                if (EMPTY_VALUES.contains(val)) {
                    continue;
                }
                Double num = firstNumber(val);
                if (num == null || num <= 0) {
                    continue;
                }
                found.put(key, new Found(num, label));
            }
        }

        Double hp = pick(found, "hp_total", "hp_front", "hp_rear", "hp_any");
        Double kw = pick(found, "kw_total", "kw_front", "kw_rear", "kw_any");
        if (hp == null && kw != null) {
            hp = kw * 1.36; // 1 kW ≈ 1.36 metric hp
        }
        Integer powerHp = hp != null && hp >= 20 && hp <= 3000 ? (int) Math.round(hp) : null;
        Integer powerKw = kw != null && kw >= 10 && kw <= 2500 ? (int) Math.round(kw) : null;

        BigDecimal battery = null;
        if (found.containsKey("battery_kwh")) {
            double b = found.get("battery_kwh").value();
            if (b >= 5 && b <= 300) {
                BigDecimal bd = BigDecimal.valueOf(b).stripTrailingZeros();
                battery = bd.scale() < 0 ? bd.setScale(0) : bd; // 100, not 1E+2
            }
        }
        Integer range = null;
        String standard = null;
        if (found.containsKey("range_km")) {
            Found r = found.get("range_km");
            if (r.value() >= 30 && r.value() <= 2000) {
                range = (int) Math.round(r.value());
                Matcher m = STANDARD.matcher(r.label());
                if (m.find()) {
                    standard = m.group(1).toUpperCase(Locale.ROOT);
                }
            }
        }
        return new EvSpecs(powerHp, powerKw, battery, range, standard);
    }

    private static Double pick(Map<String, Found> found, String total, String front, String rear, String any) {
        if (found.containsKey(total)) {
            return found.get(total).value();
        }
        if (found.containsKey(front) || found.containsKey(rear)) {
            double sum = 0;
            if (found.containsKey(front)) {
                sum += found.get(front).value();
            }
            if (found.containsKey(rear)) {
                sum += found.get(rear).value();
            }
            return sum;
        }
        return found.containsKey(any) ? found.get(any).value() : null;
    }

    /**
     * All strings and numbers of a JSON / Next.js payload in order. It does not need valid JSON: Next.js
     * escapes quotes on several levels, so the text is simply cut at the quotes.
     */
    public static List<String> jsonTokens(String text) {
        String t = text;
        for (int i = 0; i < 3; i++) {
            t = t.replace("\\\"", "\"");
        }
        t = t.replace("\\u0026", "&").replace("\\u002F", "/").replace("\\/", "/");
        List<String> out = new ArrayList<>();
        for (String raw : t.split("\"")) {
            String seg = raw.trim();
            if (seg.isEmpty() || seg.matches("[\\s:,\\[\\]{}()\\\\]*")) {
                continue;
            }
            char c = seg.charAt(0);
            if (":,[]{}".indexOf(c) >= 0) {
                Matcher m = SIGNED_NUMBER.matcher(seg); // e.g. :82.0},{
                while (m.find()) {
                    out.add(m.group());
                }
                continue;
            }
            if (seg.length() <= 200) {
                out.add(seg);
            }
        }
        return out;
    }
}
