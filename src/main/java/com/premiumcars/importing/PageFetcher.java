package com.premiumcars.importing;

import java.io.IOException;
import java.net.URI;

import org.jsoup.HttpStatusException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Component;

import com.premiumcars.common.BadRequestException;
import com.premiumcars.config.AppProperties;

/** Downloads and parses a source page the way a normal browser would request it. */
@Component
public class PageFetcher {

    private final AppProperties props;

    public PageFetcher(AppProperties props) {
        this.props = props;
    }

    public Document fetch(URI url) {
        try {
            return Jsoup.connect(url.toString())
                    .userAgent(props.importer().userAgent())
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .timeout(props.importer().timeoutSeconds() * 1000)
                    .maxBodySize(0)
                    .followRedirects(true)
                    .get();
        } catch (HttpStatusException e) {
            throw new BadRequestException("Source page returned HTTP " + e.getStatusCode() + " for " + url, e);
        } catch (IOException e) {
            throw new BadRequestException("Could not load " + url + ": " + e.getMessage(), e);
        }
    }
}
