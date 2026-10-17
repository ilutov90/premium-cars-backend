package com.premiumcars.importing.browser;

import java.util.List;
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

    boolean enabled();
}
