package com.premiumcars.importing.source.autohome;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.premiumcars.catalog.domain.SpecGroup;

/**
 * The "Specifications" table of an autohome listing: the parameters of the model version (engine, body, chassis
 * ...) and its equipment (safety, comfort, lights, seats ...).
 * <p>
 * The page ships both as JSON inside its Next.js data ({@code self.__next_f.push([1, "..."])} scripts), under
 * {@code ssrSpecParam} and {@code ssrSpecConfig}, so no browser is needed. Equipment values are "●" (standard),
 * "○" (optional, extra cost), "-" (none), parts like "Front ●/Rear -", or a list of variants where
 * {@code subvalue} 1 is standard and 2 optional. Only standard equipment counts: the table describes the model
 * version, not the car on sale, so an option cannot be assumed to be fitted.
 *
 * @param params  parameter name (lowercase) -> value, first occurrence wins
 *                (e.g. "maximum horsepower (ps)" -> "300", "emission standard" -> "China VI")
 * @param phrases standard equipment as {@code "item"} / {@code "item: detail"} for {@code FeatureMapper}, plus
 *                {@code "parameter: value"} lines (e.g. "Power Steering Type: Electric power steering")
 * @param groups  the whole table for the car page, in the page's order: every parameter with its value and every
 *                piece of standard equipment (value "" when it is just fitted, else its details, e.g. "Front
 *                Seat Features" -> "Heating, Ventilation"); missing and optional ("○") equipment is left out
 */
public record AutohomeSpecSheet(Map<String, String> params, List<String> phrases, List<SpecGroup> groups) {

    public static final AutohomeSpecSheet EMPTY = new AutohomeSpecSheet(Map.of(), List.of(), List.of());
    /** Rows not shown on the car page: China's list price would confuse customers, the model name repeats the title. */
    private static final Set<String> NOT_SHOWN = Set.of("model name", "manufacturer suggested retail price (¥)");

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PUSH = "self.__next_f.push(";
    private static final Set<String> EMPTY_VALUES = Set.of("", "-", "--", "—", "/", "○");
    /** Parameters that are names, not equipment: they could contain words the feature rules look for. */
    private static final Set<String> NOT_PHRASES = Set.of("model name", "manufacturer");

    public AutohomeSpecSheet {
        params = Map.copyOf(params);
        phrases = List.copyOf(phrases);
        groups = List.copyOf(groups);
    }

    public String param(String name) {
        return params.get(name.toLowerCase(Locale.ROOT));
    }

    public boolean isEmpty() {
        return params.isEmpty() && phrases.isEmpty();
    }

    public static AutohomeSpecSheet parse(Document doc) {
        String data = nextData(doc);
        JsonNode paramGroups = array(data, "ssrSpecParam");
        JsonNode configGroups = array(data, "ssrSpecConfig");
        if (paramGroups == null && configGroups == null) {
            return EMPTY;
        }
        Map<String, String> params = new LinkedHashMap<>();
        List<String> phrases = new ArrayList<>();
        List<SpecGroup> groups = new ArrayList<>();
        for (JsonNode group : iterable(paramGroups)) {
            List<SpecGroup.Item> rows = new ArrayList<>();
            for (JsonNode item : iterable(group.get("paramitems"))) {
                String name = text(item, "name");
                String value = paramValue(item);
                if (name.isEmpty() || value == null) {
                    continue;
                }
                String key = name.toLowerCase(Locale.ROOT);
                // some parameters repeat in several groups (energy type, emission standard): shown once
                if (params.putIfAbsent(key, value) == null && !NOT_SHOWN.contains(key)) {
                    rows.add(new SpecGroup.Item(name, value));
                }
                if (!NOT_PHRASES.contains(key)) {
                    phrases.add(name + ": " + value);
                }
            }
            groups.add(new SpecGroup(text(group, "name"), rows));
        }
        for (JsonNode group : iterable(configGroups)) {
            List<SpecGroup.Item> rows = new ArrayList<>();
            for (JsonNode item : iterable(group.get("configitems"))) {
                configPhrases(item, phrases);
                SpecGroup.Item row = configRow(item);
                if (row != null) {
                    rows.add(row);
                }
            }
            groups.add(new SpecGroup(text(group, "name"), rows));
        }
        return new AutohomeSpecSheet(params, phrases, SpecGroup.clean(groups));
    }

    /** "1997", or the variants of a "--" value joined: "Front Engine, All-Wheel Drive". */
    private static String paramValue(JsonNode item) {
        String value = text(item, "value");
        if (!EMPTY_VALUES.contains(value)) {
            return value;
        }
        List<String> parts = new ArrayList<>();
        for (JsonNode sub : iterable(item.get("sublist"))) {
            String v = sub.path("subvalue").asText("").trim();
            if (!EMPTY_VALUES.contains(v)) {
                parts.add(v);
            }
        }
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    /**
     * An equipment row for the car page when the model version has it as standard: "" for "●", the standard
     * variants ("Heating, Ventilation") or parts ("Front") otherwise, or a plain value ("12.3-inch"); null when
     * the car does not have it as standard.
     */
    static SpecGroup.Item configRow(JsonNode item) {
        String name = text(item, "name");
        JsonNode values = item.path("valueitems");
        if (name.isEmpty() || !values.isArray() || values.isEmpty()) {
            return null;
        }
        JsonNode v = values.get(0);
        JsonNode sublist = v.path("sublist");
        if (sublist.isArray() && !sublist.isEmpty()) {
            List<String> standard = new ArrayList<>();
            for (JsonNode sub : sublist) {
                String sn = text(sub, "subname");
                if (sub.path("subvalue").asInt(0) == 1 && !sn.isEmpty()) {
                    standard.add(sn);
                }
            }
            return standard.isEmpty() ? null : new SpecGroup.Item(name, String.join(", ", standard));
        }
        String value = text(v, "value");
        if (value.equals("●")) {
            return new SpecGroup.Item(name, "");
        }
        if (value.contains("●")) {
            List<String> parts = new ArrayList<>();
            for (String part : value.split("/")) {
                if (part.contains("●")) {
                    String label = part.replace("●", "").trim();
                    if (!label.isEmpty()) {
                        parts.add(label);
                    }
                }
            }
            return new SpecGroup.Item(name, String.join(", ", parts));
        }
        if (EMPTY_VALUES.contains(value) || value.contains("○")) {
            return null;
        }
        return new SpecGroup.Item(name, value);
    }

    private static void configPhrases(JsonNode item, List<String> out) {
        String name = text(item, "name");
        JsonNode values = item.path("valueitems");
        if (name.isEmpty() || !values.isArray() || values.isEmpty()) {
            return;
        }
        JsonNode v = values.get(0);
        JsonNode sublist = v.path("sublist");
        if (sublist.isArray() && !sublist.isEmpty()) {
            for (JsonNode sub : sublist) {
                // subvalue 1 = standard, 2 = optional
                if (sub.path("subvalue").asInt(0) == 1) {
                    String sn = text(sub, "subname");
                    out.add(sn.isEmpty() ? name : name + ": " + sn);
                }
            }
            return;
        }
        String value = text(v, "value");
        if (value.equals("●")) {
            out.add(name);
        } else if (value.contains("●")) {
            // "Front ●/Rear -", "Primary ●/Secondary ●"
            for (String part : value.split("/")) {
                if (part.contains("●")) {
                    String label = part.replace("●", "").trim();
                    out.add(label.isEmpty() ? name : name + ": " + label);
                }
            }
        } else if (!EMPTY_VALUES.contains(value) && !value.contains("○")) {
            out.add(name + ": " + value); // plain text, e.g. a screen size
        }
    }

    // ---------------------------------------------------------------- Next.js data

    /** The text of all {@code self.__next_f.push([1, "..."])} chunks, in page order; the raw HTML when none. */
    static String nextData(Document doc) {
        StringBuilder out = new StringBuilder();
        for (Element script : doc.select("script")) {
            String code = script.data().trim();
            if (!code.startsWith(PUSH)) {
                continue;
            }
            int end = code.lastIndexOf(')');
            if (end <= PUSH.length()) {
                continue;
            }
            try {
                JsonNode chunk = JSON.readTree(code.substring(PUSH.length(), end));
                if (chunk.isArray() && chunk.size() > 1 && chunk.get(1).isTextual()) {
                    out.append(chunk.get(1).asText());
                }
            } catch (JsonProcessingException e) {
                // not a data chunk (e.g. a function call); skip it
            }
        }
        return out.isEmpty() ? doc.outerHtml() : out.toString();
    }

    /** The JSON array after {@code "key":}, or null when it is not there or not valid JSON. */
    static JsonNode array(String data, String key) {
        int at = data.indexOf("\"" + key + "\":");
        if (at < 0) {
            return null;
        }
        int start = data.indexOf('[', at);
        if (start < 0) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        for (int i = start; i < data.length(); i++) {
            char c = data.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
            } else if (c == '[') {
                depth++;
            } else if (c == ']' && --depth == 0) {
                try {
                    JsonNode node = JSON.readTree(data.substring(start, i + 1));
                    return node.isArray() ? node : null;
                } catch (JsonProcessingException e) {
                    return null;
                }
            }
        }
        return null;
    }

    private static Iterable<JsonNode> iterable(JsonNode node) {
        return node != null && node.isArray() ? node : List.of();
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? "" : v.asText("").replace(' ', ' ').trim();
    }
}
