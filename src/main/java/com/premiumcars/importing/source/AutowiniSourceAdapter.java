package com.premiumcars.importing.source;

import java.net.URI;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.premiumcars.catalog.domain.CarSource;
import com.premiumcars.config.AppProperties;
import com.premiumcars.importing.HtmlSourceAdapter;
import com.premiumcars.importing.PageFetcher;
import com.premiumcars.importing.extract.ExtractionHints;
import com.premiumcars.importing.extract.GenericPageExtractor;

/**
 * autowini.com (South Korea). Uses the public page as-is.
 * TODO: some details need a logged-in session (OAuth2/Keycloak) — port the login from autowini_poster.py
 * into a custom PageFetcher for this adapter when needed.
 */
@Component
@Order(20)
public class AutowiniSourceAdapter extends HtmlSourceAdapter {

    public AutowiniSourceAdapter(PageFetcher fetcher, GenericPageExtractor extractor, AppProperties props) {
        super(fetcher, extractor, props);
    }

    @Override
    public CarSource source() {
        return CarSource.AUTOWINI;
    }

    @Override
    public boolean supports(URI url) {
        return hostEndsWith(url, "autowini.com");
    }

    @Override
    protected ExtractionHints hints() {
        return new ExtractionHints("USD", imageUrl -> !looksLikeDecoration(imageUrl), props.importer().maxImages());
    }
}
