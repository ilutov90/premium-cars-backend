package com.premiumcars.importing.source;

import java.net.URI;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.premiumcars.catalog.domain.CarSource;
import com.premiumcars.config.AppProperties;
import com.premiumcars.importing.HtmlSourceAdapter;
import com.premiumcars.importing.PageFetcher;
import com.premiumcars.importing.extract.ExtractionHints;
import com.premiumcars.importing.extract.GenericPageExtractor;

/** Fallback for any other site: best-effort extraction, the admin fills in the rest. Always last. */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class GenericSourceAdapter extends HtmlSourceAdapter {

    public GenericSourceAdapter(PageFetcher fetcher, GenericPageExtractor extractor, AppProperties props) {
        super(fetcher, extractor, props);
    }

    @Override
    public CarSource source() {
        return CarSource.OTHER;
    }

    @Override
    public boolean supports(URI url) {
        return true;
    }

    @Override
    protected ExtractionHints hints() {
        return new ExtractionHints("USD", imageUrl -> !looksLikeDecoration(imageUrl), props.importer().maxImages());
    }
}
