package com.premiumcars.social;

import java.util.Optional;

import com.premiumcars.common.BadRequestException;
import com.premiumcars.media.ImageDownloader;
import com.premiumcars.media.ImageStorage;

/** An uploaded site image. Each change is one SQL statement, so no transaction wraps the file. */
public abstract class SiteImageService {

    static final int MAX_BYTES = 10 * 1024 * 1024;

    private final SiteImageRepository repo;
    private final ImageStorage storage;

    protected SiteImageService(SiteImageRepository repo, ImageStorage storage) {
        this.repo = repo;
        this.storage = storage;
    }

    public SiteImage get() {
        return new SiteImage(repo.findPath().map(storage::publicUrl).orElse(null));
    }

    /** Replaces the image with the uploaded bytes (JPEG, PNG or WebP, up to 10 MB). */
    public SiteImage set(byte[] bytes) {
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

    public SiteImage delete() {
        String old = repo.findPath().orElse(null);
        repo.delete();
        if (old != null) {
            storage.delete(old);
        }
        return new SiteImage(null);
    }
}
