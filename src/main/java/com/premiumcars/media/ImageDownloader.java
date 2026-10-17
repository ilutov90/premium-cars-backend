package com.premiumcars.media;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.premiumcars.config.AppProperties;

/** Downloads a photo from the source site. Returns empty instead of failing the whole import. */
@Component
public class ImageDownloader {

    private static final Logger log = LoggerFactory.getLogger(ImageDownloader.class);
    private static final int MAX_BYTES = 15 * 1024 * 1024;

    public record DownloadedImage(byte[] bytes, String extension) {
    }

    private final HttpClient client;
    private final AppProperties props;

    public ImageDownloader(AppProperties props) {
        this.props = props;
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(props.importer().timeoutSeconds()))
                .build();
    }

    public Optional<DownloadedImage> download(String imageUrl, String referer) {
        try {
            var request = HttpRequest.newBuilder(URI.create(imageUrl))
                    .timeout(Duration.ofSeconds(props.importer().timeoutSeconds()))
                    .header("User-Agent", props.importer().userAgent())
                    .header("Referer", referer)
                    .header("Accept", "image/avif,image/webp,image/png,image/jpeg,*/*")
                    .GET()
                    .build();
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            String contentType = response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
            byte[] body = response.body();
            if (response.statusCode() != 200 || !contentType.startsWith("image/")
                    || body.length == 0 || body.length > MAX_BYTES) {
                log.warn("Skipping image {} (status {}, type {}, {} bytes)",
                        imageUrl, response.statusCode(), contentType, body.length);
                return Optional.empty();
            }
            return Optional.of(new DownloadedImage(body, extensionFor(contentType)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Could not download image {}: {}", imageUrl, e.getMessage());
            return Optional.empty();
        }
    }

    private static String extensionFor(String contentType) {
        if (contentType.contains("png")) {
            return "png";
        }
        if (contentType.contains("webp")) {
            return "webp";
        }
        if (contentType.contains("avif")) {
            return "avif";
        }
        return "jpg";
    }
}
