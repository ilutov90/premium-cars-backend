package com.premiumcars.importing.extract;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds known "label -> value" pairs in page lines. Handles both "Mileage (km): 60,600" on one line and
 * "Mileage (km)" followed by "60,600" on the next line. The first occurrence of a label wins.
 */
public final class LabelValues {

    private static final Pattern INLINE = Pattern.compile("^(.{3,25}?)\\s*[:：]\\s*(.+)$");

    private LabelValues() {
    }

    /**
     * @param labels lowercase label text -> our field key (several labels may map to the same key)
     * @return field key -> raw value
     */
    public static Map<String, String> parse(List<String> lines, int start, Map<String, String> labels) {
        Map<String, String> data = new LinkedHashMap<>();
        int n = lines.size();
        for (int i = Math.max(start, 0); i < n; i++) {
            String line = lines.get(i);
            Matcher m = INLINE.matcher(line);
            if (m.matches()) {
                String key = labels.get(normalize(m.group(1)));
                if (key != null) {
                    data.putIfAbsent(key, m.group(2).trim());
                    continue;
                }
            }
            String key = labels.get(normalize(line));
            if (key == null || data.containsKey(key) || i + 1 >= n) {
                continue;
            }
            String value = lines.get(i + 1).trim();
            if (labels.containsKey(normalize(value))) {
                continue; // no value: the next line is another label
            }
            data.put(key, value);
        }
        return data;
    }

    static String normalize(String label) {
        return label.toLowerCase(Locale.ROOT).replaceAll("[:：]+$", "").trim();
    }
}
