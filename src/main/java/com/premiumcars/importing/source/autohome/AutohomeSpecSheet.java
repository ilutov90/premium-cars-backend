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
 */
public record AutohomeSpecSheet(Map<String, String> params, List<String> phrases) {

    public static final AutohomeSpecSheet EMPTY = new AutohomeSpecSheet(Map.of(), List.of());

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PUSH = "self.__next_f.push(";
    private static final Set<String> EMPTY_VALUES = Set.of("", "-", "--", "—", "/", "○");
    /** Parameters that are names, not equipment: they could contain words the feature rules look for. */
    private static final Set<String> NOT_PHRASES = Set.of("model name", "manufacturer");

    public AutohomeSpecSheet {
        params = Map.copyOf(params);
        phrases = List.copyOf(phrases);
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
        for (JsonNode group : iterable(paramGroups)) {
            for (JsonNode item : iterable(group.get("paramitems"))) {
                String name = text(item, "name");
                String value = paramValue(item);
                if (name.isEmpty() || value == null) {
                    continue;
                }
                String key = name.toLowerCase(Locale.ROOT);
                params.putIfAbsent(key, value);
                if (!NOT_PHRASES.contains(key)) {
                    phrases.add(name + ": " + value);
                }
            }
        }
        for (JsonNode group : iterable(configGroups)) {
            for (JsonNode item : iterable(group.get("configitems"))) {
                configPhrases(item, phrases);
            }
        }
        return new AutohomeSpecSheet(params, phrases);
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
