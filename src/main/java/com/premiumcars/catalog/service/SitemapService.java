package com.premiumcars.catalog.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.premiumcars.catalog.repo.SitemapRepository;
import com.premiumcars.catalog.web.dto.SitemapDtos.SitemapCar;
import com.premiumcars.catalog.web.dto.SitemapDtos.SitemapPage;
import com.premiumcars.common.BadRequestException;
import com.premiumcars.media.ImageStorage;

/** One page of the public cars for the sitemap, with the cover photo's URL as the public list builds it. */
@Service
public class SitemapService {

    public static final int MAX_SIZE = 10_000;

    private final SitemapRepository repo;
    private final ImageStorage storage;

    public SitemapService(SitemapRepository repo, ImageStorage storage) {
        this.repo = repo;
        this.storage = storage;
    }

    @Transactional(readOnly = true)
    public SitemapPage cars(int page, int size) {
        if (page < 0 || size < 1 || size > MAX_SIZE) {
            throw new BadRequestException("page must be >= 0 and size between 1 and " + MAX_SIZE);
        }
        var items = repo.findPage(page, size).stream()
                .map(r -> new SitemapCar(r.slug(), r.updatedAt(), imageUrl(r.coverRef())))
                .toList();
        return new SitemapPage(repo.countPublic(), items);
    }

    private String imageUrl(String ref) {
        if (ref == null) {
            return null;
        }
        return ref.startsWith("s:") ? storage.publicUrl(ref.substring(2)) : ref.substring(2);
    }
}
