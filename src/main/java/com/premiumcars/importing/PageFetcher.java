package com.premiumcars.importing;

import java.io.IOException;
import java.net.URI;

import org.jsoup.HttpStatusException;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;

import com.premiumcars.common.BadRequestException;
import com.premiumcars.config.AppProperties;

/** Downloads a public page the way a normal browser would request it (no login, no JavaScript). */
@Component
public class PageFetcher {

    private final AppProperties props;

    public PageFetcher(AppProperties props) {
        this.props = props;
    }

    /** Raw HTML of the page, including scripts (Next.js data lives there). */
    public String fetchHtml(URI url) {
        try {
            return Jsoup.connect(url.toString())
                    .userAgent(props.importer().userAgent())
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .timeout(props.importer().timeoutSeconds() * 1000)
                    .maxBodySize(0)
                    .followRedirects(true)
                    .execute()
                    .body();
        } catch (HttpStatusException e) {
            throw new BadRequestException("Source page returned HTTP " + e.getStatusCode() + " for " + url, e);
        } catch (IOException e) {
            throw new BadRequestException("Could not load " + url + ": " + e.getMessage(), e);
        }
    }
}
