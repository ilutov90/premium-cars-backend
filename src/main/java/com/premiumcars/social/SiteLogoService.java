package com.premiumcars.social;

import org.springframework.stereotype.Service;

import com.premiumcars.media.ImageStorage;

/** The website's logo, uploaded by the admins (the admin panel sends it already reduced to the header's size). */
@Service
public class SiteLogoService extends SiteImageService {

    public SiteLogoService(SiteLogoRepository repo, ImageStorage storage) {
        super(repo, storage);
    }
}
