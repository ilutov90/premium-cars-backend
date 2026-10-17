package com.premiumcars.importing.extract;

import java.util.ArrayList;
import java.util.List;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;

/**
 * Visible text of a page as a list of lines: one line per text node, whitespace collapsed, blanks dropped.
 * Label/value pairs that sit in separate elements ("Mileage (km)" / "102300") become consecutive lines.
 */
public final class PageText {

    private PageText() {
    }

    public static List<String> lines(String html) {
        return lines(Jsoup.parse(html));
    }

    public static List<String> lines(Document original) {
        Document doc = original.clone();
        doc.select("head, script, style, noscript, svg, template").remove();
        List<String> out = new ArrayList<>();
        Element root = doc.body() != null ? doc.body() : doc;
        NodeTraversor.traverse((NodeVisitor) (node, depth) -> {
            if (node instanceof TextNode text) {
                for (String line : text.getWholeText().split("\\R")) {
                    String clean = line.replace(' ', ' ').replaceAll("\\s+", " ").trim();
                    if (!clean.isEmpty()) {
                        out.add(clean);
                    }
                }
            }
        }, root);
        return out;
    }

    /** Splits a block of text (e.g. a browser's innerText) into clean lines. */
    public static List<String> splitLines(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        for (String line : text.split("\\R")) {
            String clean = line.replace(' ', ' ').replaceAll("\\s+", " ").trim();
            if (!clean.isEmpty()) {
                out.add(clean);
            }
        }
        return out;
    }

    /** Index of the first line that contains {@code needle} (case-insensitive), or 0. */
    public static int indexOf(List<String> lines, String needle) {
        if (needle == null || needle.isBlank()) {
            return 0;
        }
        String n = needle.toLowerCase();
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).toLowerCase().contains(n)) {
                return i;
            }
        }
        return 0;
    }
}
