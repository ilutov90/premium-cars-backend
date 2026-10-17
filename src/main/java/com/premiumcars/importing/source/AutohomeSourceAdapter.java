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
 * global.autohome.com.cn (also served as global.che168.com). Prices on the export site are in USD.
 * Car photos come from the autoimg.cn CDN.
 */
@Component
@Order(10)
public class AutohomeSourceAdapter extends HtmlSourceAdapter {

    public AutohomeSourceAdapter(PageFetcher fetcher, GenericPageExtractor extractor, AppProperties props) {
        super(fetcher, extractor, props);
    }

    @Override
    public CarSource source() {
        return CarSource.AUTOHOME;
    }

    @Override
    public boolean supports(URI url) {
        return hostEndsWith(url, "autohome.com.cn", "che168.com");
    }

    @Override
    protected ExtractionHints hints() {
        return new ExtractionHints(
                "USD",
                imageUrl -> imageUrl.contains("autoimg.cn") && !looksLikeDecoration(imageUrl)
                        && !imageUrl.contains("/series/"), // model catalogue pictures, not this car
                props.importer().maxImages());
    }
}
