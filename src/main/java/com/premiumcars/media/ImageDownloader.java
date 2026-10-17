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

/**
 * Downloads one photo and checks it is a complete image (format by magic bytes, not by URL; truncated JPEGs
 * rejected). Returns empty instead of failing the whole import.
 */
@Component
public class ImageDownloader {

    private static final Logger log = LoggerFactory.getLogger(ImageDownloader.class);
    private static final int MAX_BYTES = 15 * 1024 * 1024;
    private static final int MIN_BYTES = 1024;

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
        return download(imageUrl, referer, null);
    }

    /**
     * @param httpClient client to send the request with (e.g. one holding a site's login cookies);
     *                   null = the shared client without cookies
     */
    public Optional<DownloadedImage> download(String imageUrl, String referer, HttpClient httpClient) {
        HttpClient http = httpClient != null ? httpClient : client;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                var request = HttpRequest.newBuilder(URI.create(imageUrl))
                        .timeout(Duration.ofSeconds(props.importer().timeoutSeconds()))
                        .header("User-Agent", props.importer().userAgent())
                        .header("Referer", referer)
                        // Ask for JPEG first: CDNs then avoid AVIF, which some tools cannot open.
                        .header("Accept", "image/jpeg,image/png,image/*;q=0.8")
                        .header("Accept-Language", "en-US,en;q=0.9")
                        .GET()
                        .build();
                HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
                int status = response.statusCode();
                if (status == 429 || status == 503) {
                    Thread.sleep(2000L * attempt);
                    continue;
                }
                String type = response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
                byte[] body = response.body();
                if (status != 200 || !(type.startsWith("image/") || type.contains("octet-stream"))) {
                    log.debug("Skipping image {} (status {}, type {}): {}", imageUrl, status, type, snippet(body));
                    lastRefusal = "HTTP " + status + " " + type + ": " + snippet(body);
                    return Optional.empty();
                }
                String ext = sniff(body);
                String problem = problem(body, ext);
                if (problem == null) {
                    return Optional.of(new DownloadedImage(body, ext));
                }
                log.debug("Image {} attempt {}: {}", imageUrl, attempt, problem);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            } catch (Exception e) {
                log.debug("Could not download image {}: {}", imageUrl, e.getMessage());
            }
        }
        log.warn("Giving up on image {}", imageUrl);
        return Optional.empty();
    }

    /** Why the last photo was refused (status, type and the start of the answer), for one summary log line. */
    private volatile String lastRefusal;

    public String lastRefusal() {
        return lastRefusal;
    }

    private static String snippet(byte[] body) {
        String text = new String(body, 0, Math.min(body.length, 300), java.nio.charset.StandardCharsets.UTF_8);
        return text.replaceAll("\\s+", " ").trim();
    }

    /** Real format by magic bytes, or null. */
    static String sniff(byte[] d) {
        if (d.length >= 3 && (d[0] & 0xff) == 0xff && (d[1] & 0xff) == 0xd8 && (d[2] & 0xff) == 0xff) {
            return "jpg";
        }
        if (d.length >= 8 && (d[0] & 0xff) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G') {
            return "png";
        }
        if (d.length >= 12 && d[0] == 'R' && d[1] == 'I' && d[2] == 'F' && d[3] == 'F'
                && d[8] == 'W' && d[9] == 'E' && d[10] == 'B' && d[11] == 'P') {
            return "webp";
        }
        if (d.length >= 12 && d[4] == 'f' && d[5] == 't' && d[6] == 'y' && d[7] == 'p'
                && d[8] == 'a' && d[9] == 'v' && d[10] == 'i') {
            return "avif";
        }
        if (d.length >= 6 && d[0] == 'G' && d[1] == 'I' && d[2] == 'F') {
            return "gif";
        }
        return null;
    }

    /** Why the bytes are not a usable image, or null when they are. */
    static String problem(byte[] body, String ext) {
        if (body.length < MIN_BYTES) {
            return "too small (" + body.length + " B)";
        }
        if (body.length > MAX_BYTES) {
            return "too large (" + body.length + " B)";
        }
        if (ext == null) {
            return "not an image";
        }
        if (ext.equals("jpg")) {
            int end = body.length;
            while (end > 2 && (body[end - 1] == 0 || body[end - 1] == '\r' || body[end - 1] == '\n')) {
                end--;
            }
            if (!((body[end - 2] & 0xff) == 0xff && (body[end - 1] & 0xff) == 0xd9)) {
                return "truncated JPEG";
            }
        }
        return null;
    }
}
