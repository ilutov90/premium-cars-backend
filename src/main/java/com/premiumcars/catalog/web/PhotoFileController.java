package com.premiumcars.catalog.web;

import java.time.Duration;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.premiumcars.catalog.repo.CarImageRepository;
import com.premiumcars.common.NotFoundException;
import com.premiumcars.importing.CarPhotoFiles;

/**
 * A car photo from our own origin, stored or linked alike ({@code CarMapper.fileUrl}). The admin panel downloads
 * photos through it and the mobile.bg extension uploads them from it: neither may read the source sites' photos
 * (other origins). Linked photos are fetched from the source at that moment and not kept.
 */
@RestController
public class PhotoFileController {

    private final CarImageRepository images;
    private final CarPhotoFiles files;

    public PhotoFileController(CarImageRepository images, CarPhotoFiles files) {
        this.images = images;
        this.files = files;
    }

    @GetMapping("/media/photo/{imageId}")
    public ResponseEntity<byte[]> photo(@PathVariable long imageId) {
        var image = images.findById(imageId).orElseThrow(() -> new NotFoundException("No photo " + imageId));
        var file = files.read(image)
                .orElseThrow(() -> new NotFoundException("Photo " + imageId + " no longer loads from the source"));
        return ResponseEntity.ok()
                .contentType(mediaType(file.extension()))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic())
                .body(file.bytes());
    }

    static MediaType mediaType(String extension) {
        return switch (extension) {
            case "png" -> MediaType.IMAGE_PNG;
            case "gif" -> MediaType.IMAGE_GIF;
            case "webp" -> MediaType.parseMediaType("image/webp");
            case "avif" -> MediaType.parseMediaType("image/avif");
            default -> MediaType.IMAGE_JPEG;
        };
    }
}
