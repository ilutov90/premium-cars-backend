package com.premiumcars.social;

import org.springframework.stereotype.Service;

import com.premiumcars.media.ImageStorage;

/** The picture shown when a link to the site is shared, uploaded by the admins. */
@Service
public class SiteShareImageService extends SiteImageService {

    public SiteShareImageService(SiteShareImageRepository repo, ImageStorage storage) {
        super(repo, storage);
    }
}
