package com.premiumcars.importing;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.premiumcars.catalog.domain.Car;
import com.premiumcars.catalog.domain.CarImage;
import com.premiumcars.catalog.domain.CarSource;
import com.premiumcars.catalog.repo.CarRepository;
import com.premiumcars.media.ImageDownloader;
import com.premiumcars.media.ImageDownloader.DownloadedImage;
import com.premiumcars.media.ImageStorage;

/**
 * The bytes of a car's photo, wherever it is: read from our storage, or for a linked photo downloaded from the
 * source's CDN at that moment (publishing to Facebook/Instagram/Viber, the mobile.bg extension). Nothing is kept.
 */
@Component
public class CarPhotoFiles {

    private final ImageStorage storage;
    private final ImageDownloader downloader;
    private final CarRepository cars;
    private final List<SourceAdapter> adapters;

    public CarPhotoFiles(ImageStorage storage, ImageDownloader downloader, CarRepository cars,
                         List<SourceAdapter> adapters) {
        this.storage = storage;
        this.downloader = downloader;
        this.cars = cars;
        this.adapters = adapters;
    }

    /** @return empty when a linked photo no longer loads (the listing was removed at the source) */
    public Optional<DownloadedImage> read(CarImage image) {
        if (!image.linked()) {
            String path = image.storagePath();
            return Optional.of(new DownloadedImage(storage.read(path),
                    path.substring(path.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT)));
        }
        CarSource source = cars.findById(image.carId()).map(Car::source).orElse(null);
        Optional<SourceAdapter> adapter = adapters.stream().filter(a -> a.source() == source).findFirst();
        String referer = adapter.map(a -> a.referer(URI.create(image.sourceUrl()))).orElse(image.sourceUrl());
        return downloader.download(image.sourceUrl(), referer, adapter.map(SourceAdapter::imageClient).orElse(null));
    }
}
