package com.premiumcars.media;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import com.premiumcars.config.AppProperties;

/**
 * The {@link ImageStorage} everything uses: new photos go to Cloudflare R2 when it is configured
 * ({@code app.media.r2}), otherwise to the local folder. A storage path says where a photo is: "r2:cars/15/a.jpg"
 * is in R2, "cars/15/a.jpg" in the local folder (photos stored before R2, until {@link R2PhotoMover} moves them).
 */
@Primary
@Component
public class RoutingImageStorage implements ImageStorage {

    private static final Logger log = LoggerFactory.getLogger(RoutingImageStorage.class);
    static final String R2_PREFIX = "r2:";

    private final LocalImageStorage local;
    private final R2ImageStorage r2;

    public RoutingImageStorage(LocalImageStorage local, AppProperties props) {
        this.local = local;
        this.r2 = props.media().r2().configured() ? new R2ImageStorage(props.media().r2()) : null;
        log.info("New photos are stored in {}", r2 != null
                ? "Cloudflare R2 (bucket " + props.media().r2().bucket() + ")" : "the local folder");
    }

    /** R2, when configured. */
    public Optional<R2ImageStorage> r2() {
        return Optional.ofNullable(r2);
    }

    public static boolean inR2(String storagePath) {
        return storagePath.startsWith(R2_PREFIX);
    }

    public static String r2Path(String key) {
        return R2_PREFIX + key;
    }

    @Override
    public String store(String folder, byte[] bytes, String extension) {
        return r2 != null ? r2Path(r2.store(folder, bytes, extension)) : local.store(folder, bytes, extension);
    }

    @Override
    public void delete(String storagePath) {
        if (inR2(storagePath)) {
            r2OrFail(storagePath).delete(key(storagePath));
        } else {
            local.delete(storagePath);
        }
    }

    @Override
    public byte[] read(String storagePath) {
        return inR2(storagePath) ? r2OrFail(storagePath).read(key(storagePath)) : local.read(storagePath);
    }

    @Override
    public String publicUrl(String storagePath) {
        return inR2(storagePath) ? r2OrFail(storagePath).publicUrl(key(storagePath)) : local.publicUrl(storagePath);
    }

    private R2ImageStorage r2OrFail(String storagePath) {
        if (r2 == null) {
            throw new IllegalStateException("Photo " + storagePath + " is in R2, which is not configured");
        }
        return r2;
    }

    private static String key(String storagePath) {
        return storagePath.substring(R2_PREFIX.length());
    }
}
