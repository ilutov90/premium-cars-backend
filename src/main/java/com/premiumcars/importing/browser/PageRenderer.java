package com.premiumcars.importing.browser;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Renders a page in a real (headless) browser, for data that a site loads with JavaScript:
 * the full photo gallery and the "Electric Motor" / "Battery && Charging" tabs on autohome.
 * Slow (seconds), so adapters call it only when the plain HTML misses something.
 */
public interface PageRenderer {

    /**
     * @param html  the final DOM
     * @param texts the body text after each click, plus bodies of JSON responses whose text matches the hint
     */
    record RenderedPage(String html, List<String> texts) {
    }

    /**
     * @param url          page to open
     * @param clickTexts   button/tab captions to click one after another (e.g. "More", "Electric Motor");
     *                     the body text is captured after every click
     * @param responseHint keep JSON/RSC response bodies that match this pattern; null keeps none
     * @return empty when rendering is disabled or failed (the import then continues with what it has)
     */
    Optional<RenderedPage> render(String url, List<Pattern> clickTexts, Pattern responseHint);

    /** A cookie to put into the browser before opening a page (e.g. a logged-in session). */
    record BrowserCookie(String name, String value, String domain, String path) {
    }

    /**
     * Downloads photos the way a visitor's browser does, for photo servers that refuse plain HTTP clients
     * (autowini answers 403 even with the session cookies): opens {@code pageUrl}, then loads the photos as
     * {@code <img>} elements on that page and keeps the response bytes.
     *
     * @param candidates per photo, the URLs to try in order (e.g. 1024 px, then 320 px)
     * @param cookies    put into the browser first
     * @return photo index -> image bytes, for the photos that loaded; empty when rendering is disabled or failed
     */
    Map<Integer, byte[]> loadImages(String pageUrl, List<List<String>> candidates, List<BrowserCookie> cookies);

    boolean enabled();
}
