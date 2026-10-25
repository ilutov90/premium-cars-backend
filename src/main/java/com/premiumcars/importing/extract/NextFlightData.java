package com.premiumcars.importing.extract;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The data a Next.js App Router page ships inside its HTML: the {@code self.__next_f.push([1, "..."])} scripts
 * (the "flight" stream). Their string parts, joined in page order, contain the page's data as JSON fragments, so a
 * listing can be read without a browser. Used by the autohome and guazi parsers.
 */
public final class NextFlightData {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PUSH = "self.__next_f.push(";

    private NextFlightData() {
    }

    /** The text of all {@code self.__next_f.push([1, "..."])} chunks, in page order; "" when there are none. */
    public static String text(Document doc) {
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
        return out.toString();
    }

    /**
     * The JSON value ({@code {...}} or {@code [...]}) that starts at or after {@code from}, read up to its matching
     * bracket; null when there is none or it is not valid JSON.
     */
    public static JsonNode valueAt(String data, int from) {
        int start = -1;
        for (int i = from; i < data.length(); i++) {
            char c = data.charAt(i);
            if (c == '{' || c == '[') {
                start = i;
                break;
            }
            if (!Character.isWhitespace(c) && c != ':') {
                return null; // a string or number, not an object or array
            }
        }
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
            } else if (c == '{' || c == '[') {
                depth++;
            } else if ((c == '}' || c == ']') && --depth == 0) {
                try {
                    return JSON.readTree(data.substring(start, i + 1));
                } catch (JsonProcessingException e) {
                    return null;
                }
            }
        }
        return null;
    }
}
