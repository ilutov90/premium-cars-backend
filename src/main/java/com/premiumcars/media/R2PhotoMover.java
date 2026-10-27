package com.premiumcars.media;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.premiumcars.catalog.domain.CarImage;
import com.premiumcars.catalog.repo.CarImageRepository;

/**
 * Once R2 is configured: moves the car photos still in the local folder to R2 (same key), in the background after
 * startup. Each photo is uploaded, its row switched to "r2:..." and then its local file deleted, so a stop halfway
 * loses nothing; a photo that fails stays local and is tried again on the next start.
 */
@Component
public class R2PhotoMover {

    private static final Logger log = LoggerFactory.getLogger(R2PhotoMover.class);
    private static final int BATCH = 100;

    private final RoutingImageStorage storage;
    private final LocalImageStorage local;
    private final CarImageRepository images;

    public R2PhotoMover(RoutingImageStorage storage, LocalImageStorage local, CarImageRepository images) {
        this.storage = storage;
        this.local = local;
        this.images = images;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        storage.r2().ifPresent(r2 -> Thread.ofVirtual().name("r2-photo-mover").start(() -> run(r2)));
    }

    void run(R2ImageStorage r2) {
        Set<Long> failed = new HashSet<>();
        int moved = 0;
        while (true) {
            List<CarImage> batch = images.findLocal(BATCH + failed.size()).stream()
                    .filter(i -> !failed.contains(i.id())).limit(BATCH).toList();
            if (batch.isEmpty()) {
                break;
            }
            if (moved == 0) {
                log.info("Moving the local car photos to R2");
            }
            for (CarImage image : batch) {
                String path = image.storagePath();
                try {
                    String ext = path.substring(path.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
                    r2.put(path, local.read(path), R2ImageStorage.contentType(ext));
                    if (images.moveStoragePath(image.id(), path, RoutingImageStorage.r2Path(path))) {
                        local.delete(path);
                        moved++;
                    } else {
                        r2.delete(path); // deleted or linked meanwhile: the copy is not needed
                    }
                } catch (RuntimeException e) {
                    failed.add(image.id());
                    log.warn("Could not move photo {} ({}) to R2: {}", image.id(), path, e.toString());
                }
            }
        }
        if (moved > 0 || !failed.isEmpty()) {
            log.info("Moved {} car photos to R2; {} stay local", moved, failed.size());
        }
    }
}
