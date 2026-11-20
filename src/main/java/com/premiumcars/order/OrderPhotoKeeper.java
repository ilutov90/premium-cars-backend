package com.premiumcars.order;

import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.premiumcars.catalog.domain.CarImage;
import com.premiumcars.catalog.repo.CarImageRepository;
import com.premiumcars.importing.CarPhotoFiles;
import com.premiumcars.media.ImageDownloader.DownloadedImage;
import com.premiumcars.media.ImageStorage;

/**
 * Copies the linked photos of a car that got an order (guazi, autohome, autotrader.ca: shown from the source's CDN)
 * into our storage, so the order's car keeps its photos once the listing is gone; the site then shows the stored
 * copies. After the order's commit, on another thread, outside any transaction; best effort: a photo that does not
 * download stays linked, failures are only logged.
 */
@Component
public class OrderPhotoKeeper {

    private static final Logger log = LoggerFactory.getLogger(OrderPhotoKeeper.class);

    private final CarImageRepository images;
    private final CarPhotoFiles photoFiles;
    private final ImageStorage storage;
    private final OrderRepository orders;

    public OrderPhotoKeeper(CarImageRepository images, CarPhotoFiles photoFiles, ImageStorage storage,
                            OrderRepository orders) {
        this.orders = orders;
        this.images = images;
        this.photoFiles = photoFiles;
        this.storage = storage;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCarAssigned(OrderCarAssigned e) {
        int kept = keep(e.carId());
        try {
            listPhotos(e.orderId(), e.carId());
        } catch (RuntimeException ex) {
            log.warn("Order {}: could not note the photos of car {}: {}", e.orderId(), e.carId(), ex.toString());
        }
    }

    /** The order keeps the storage paths of all the car's stored photos, in gallery order. */
    void listPhotos(long orderId, long carId) {
        List<String> paths = images.findByCarId(carId).stream().filter(i -> !i.linked())
                .map(CarImage::storagePath).toList();
        orders.saveCarPhotos(orderId, paths);
    }

    /** @return how many photos were copied */
    public int keep(long carId) {
        int kept = 0;
        int failed = 0;
        for (CarImage image : images.findByCarId(carId)) {
            if (!image.linked() || image.sourceUrl() == null) {
                continue;
            }
            try {
                Optional<DownloadedImage> file = photoFiles.read(image);
                if (file.isEmpty()) {
                    failed++;
                    continue;
                }
                String path = storage.store("cars/" + carId, file.get().bytes(), file.get().extension());
                if (images.storeLinked(image.id(), path)) {
                    kept++;
                } else {
                    storage.delete(path); // deleted or stored meanwhile
                }
            } catch (RuntimeException ex) {
                failed++;
                log.warn("Car {}: could not keep photo {}: {}", carId, image.id(), ex.toString());
            }
        }
        if (kept > 0 || failed > 0) {
            log.info("Car {} has an order: {} linked photos stored, {} could not be", carId, kept, failed);
        }
        return kept;
    }
}
