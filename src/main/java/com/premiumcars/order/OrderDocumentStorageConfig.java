package com.premiumcars.order;

import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.premiumcars.config.AppProperties;

/** Picks where the orders' documents go: the private R2 bucket when it is configured, else a private local folder. */
@Configuration
public class OrderDocumentStorageConfig {

    private static final Logger log = LoggerFactory.getLogger(OrderDocumentStorageConfig.class);

    @Bean
    OrderDocumentStorage orderDocumentStorage(AppProperties props) {
        AppProperties.Media media = props.media();
        if (media.r2Docs().configured()) {
            log.info("Order documents are stored in the private Cloudflare R2 bucket {}", media.r2Docs().bucket());
            return new R2OrderDocumentStorage(media.r2Docs());
        }
        if (media.localDocsDir() == null || media.localDocsDir().isBlank()) {
            log.warn("Order documents: the private R2 bucket is not configured (R2_DOCS_*): uploads are refused");
            return new UnconfiguredOrderDocumentStorage();
        }
        Path docs = Path.of(media.localDocsDir()).toAbsolutePath().normalize();
        Path photos = Path.of(media.dir()).toAbsolutePath().normalize();
        if (docs.startsWith(photos) || photos.startsWith(docs)) {
            throw new IllegalStateException("app.media.local-docs-dir (" + docs + ") must not contain or be inside "
                    + "the public media folder " + photos);
        }
        log.info("Order documents are stored in the local folder {} (private R2 bucket not configured)", docs);
        return new LocalOrderDocumentStorage(docs);
    }
}
