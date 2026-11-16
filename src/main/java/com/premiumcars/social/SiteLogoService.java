package com.premiumcars.social;

import org.springframework.stereotype.Service;

import com.premiumcars.media.ImageStorage;

/** The website's logo, uploaded by the admins (the admin panel sends it already reduced to the header's size). */
@Service
public class SiteLogoService extends SiteImageService {

    private final SiteLogoRepository repo;

    public SiteLogoService(SiteLogoRepository repo, ImageStorage storage) {
        super(repo, storage);
        this.repo = repo;
    }

    public SiteLogo logo() {
        return new SiteLogo(get().url(), repo.findStyle());
    }

    public SiteLogo setLogo(byte[] bytes) {
        set(bytes);
        return logo();
    }

    /** Removes the image; the style stays. */
    public SiteLogo deleteLogo() {
        delete();
        return logo();
    }

    public SiteLogo setStyle(LogoStyle style) {
        repo.saveStyle(style);
        return logo();
    }
}
