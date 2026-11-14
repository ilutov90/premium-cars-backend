package com.premiumcars.social;

import java.util.Optional;

import org.springframework.stereotype.Service;

import com.premiumcars.common.BadRequestException;
import com.premiumcars.media.ImageDownloader;
import com.premiumcars.media.ImageStorage;

/** The website's logo, uploaded by the admins. Each change is one SQL statement, so no transaction wraps the file. */
@Service
public class SiteLogoService {

    static final int MAX_BYTES = 10 * 1024 * 1024;

    private final SiteLogoRepository repo;
    private final ImageStorage storage;

    public SiteLogoService(SiteLogoRepository repo, ImageStorage storage) {
        this.repo = repo;
        this.storage = storage;
    }

    public SiteLogo get() {
        return new SiteLogo(repo.findPath().map(storage::publicUrl).orElse(null));
    }

    /** Replaces the logo with the uploaded bytes (JPEG, PNG or WebP, up to 10 MB; the admin panel sends it already reduced to the header's size). */
    public SiteLogo set(byte[] bytes) {
        var image = (bytes == null || bytes.length > MAX_BYTES ? Optional.<ImageDownloader.DownloadedImage>empty()
                : ImageDownloader.accept(bytes))
                .filter(i -> i.extension().equals("jpg") || i.extension().equals("png")
                        || i.extension().equals("webp"))
                .orElseThrow(() -> new BadRequestException("Not a complete JPEG, PNG or WebP image (max 10 MB)"));
        String old = repo.findPath().orElse(null);
        String path = storage.store("site", image.bytes(), image.extension());
        repo.savePath(path);
        if (old != null) {
            storage.delete(old);
        }
        return get();
    }

    public SiteLogo delete() {
        String old = repo.findPath().orElse(null);
        repo.delete();
        if (old != null) {
            storage.delete(old);
        }
        return new SiteLogo(null);
    }
}
