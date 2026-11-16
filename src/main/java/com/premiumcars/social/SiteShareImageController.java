package com.premiumcars.social;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** The site's share image: public for the site, uploaded and removed by the admins. */
@RestController
public class SiteShareImageController {

    private final SiteShareImageService service;

    public SiteShareImageController(SiteShareImageService service) {
        this.service = service;
    }

    @GetMapping("/api/public/share-image")
    public SiteImage publicShareImage() {
        return service.get();
    }

    @GetMapping("/api/admin/share-image")
    public SiteImage get() {
        return service.get();
    }

    /** The image as the request body (Content-Type image/jpeg, image/png or image/webp); replaces the old one. */
    @PutMapping(value = "/api/admin/share-image",
            consumes = {MediaType.IMAGE_JPEG_VALUE, MediaType.IMAGE_PNG_VALUE, "image/webp",
                    MediaType.APPLICATION_OCTET_STREAM_VALUE})
    public SiteImage set(@RequestBody byte[] image) {
        return service.set(image);
    }

    @DeleteMapping("/api/admin/share-image")
    public SiteImage delete() {
        return service.delete();
    }
}
