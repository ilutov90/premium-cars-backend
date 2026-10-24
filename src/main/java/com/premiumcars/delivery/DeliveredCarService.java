package com.premiumcars.delivery;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.premiumcars.catalog.domain.Car;
import com.premiumcars.catalog.repo.CarRepository;
import com.premiumcars.common.BadRequestException;
import com.premiumcars.common.NotFoundException;
import com.premiumcars.delivery.DeliveredCarDtos.AdminDeliveredCar;
import com.premiumcars.delivery.DeliveredCarDtos.DeliveredCarRequest;
import com.premiumcars.delivery.DeliveredCarDtos.PublicDeliveredCar;
import com.premiumcars.media.ImageDownloader;
import com.premiumcars.media.ImageStorage;

/**
 * "Доставени коли": the admins record handed-over cars with the customer's words and a photo; the site shows the
 * published ones. Publishing needs the customer's consent. Each change is one SQL statement, so there is no
 * transaction around the photo file.
 */
@Service
public class DeliveredCarService {

    private static final Logger log = LoggerFactory.getLogger(DeliveredCarService.class);
    /** The most the public site asks for at once. */
    static final int MAX_PUBLIC = 50;

    private final DeliveredCarRepository repo;
    private final CarRepository carRepo;
    private final ImageStorage storage;

    public DeliveredCarService(DeliveredCarRepository repo, CarRepository carRepo, ImageStorage storage) {
        this.repo = repo;
        this.carRepo = carRepo;
        this.storage = storage;
    }

    public List<PublicDeliveredCar> listPublic(int limit) {
        return repo.findPublished(Math.min(Math.max(limit, 1), MAX_PUBLIC)).stream().map(this::toPublic).toList();
    }

    public List<AdminDeliveredCar> listAdmin() {
        return repo.findAll().stream().map(this::toAdmin).toList();
    }

    public AdminDeliveredCar get(long id) {
        return repo.findById(id).map(this::toAdmin).orElseThrow(() -> notFound(id));
    }

    public AdminDeliveredCar create(DeliveredCarRequest r) {
        long id = repo.insert(fields(r));
        log.info("Delivered car #{} added ({})", id, r.published() ? "published" : "hidden");
        return get(id);
    }

    public AdminDeliveredCar update(long id, DeliveredCarRequest r) {
        if (!repo.update(id, fields(r))) {
            throw notFound(id);
        }
        return get(id);
    }

    public void delete(long id) {
        DeliveredCar d = repo.findById(id).orElseThrow(() -> notFound(id));
        repo.delete(id);
        if (d.photoPath() != null) {
            storage.delete(d.photoPath());
        }
    }

    /** Replaces the photo with the uploaded bytes (JPEG, PNG or WebP). */
    public AdminDeliveredCar setPhoto(long id, byte[] bytes) {
        DeliveredCar d = repo.findById(id).orElseThrow(() -> notFound(id));
        var image = ImageDownloader.accept(bytes)
                .filter(i -> i.extension().equals("jpg") || i.extension().equals("png")
                        || i.extension().equals("webp"))
                .orElseThrow(() -> new BadRequestException("Not a complete JPEG, PNG or WebP photo (max 15 MB)"));
        String path = storage.store("delivered/" + id, image.bytes(), image.extension());
        if (!repo.updatePhoto(id, path)) { // deleted meanwhile
            storage.delete(path);
            throw notFound(id);
        }
        if (d.photoPath() != null) {
            storage.delete(d.photoPath());
        }
        return get(id);
    }

    public AdminDeliveredCar deletePhoto(long id) {
        DeliveredCar d = repo.findById(id).orElseThrow(() -> notFound(id));
        repo.updatePhoto(id, null);
        if (d.photoPath() != null) {
            storage.delete(d.photoPath());
        }
        return get(id);
    }

    private DeliveredCarRepository.Fields fields(DeliveredCarRequest r) {
        if (r.published() && !r.consent()) {
            throw new BadRequestException("Publishing needs the customer's consent");
        }
        String title = trimToNull(r.carTitle());
        if (r.carId() != null) {
            Car car = carRepo.findById(r.carId())
                    .orElseThrow(() -> new NotFoundException("Car not found: " + r.carId()));
            if (title == null) {
                title = car.specs().title();
            }
        }
        if (title == null) {
            throw new BadRequestException("Enter the car (make and model) or pick it from the catalog");
        }
        return new DeliveredCarRepository.Fields(r.carId(), title, r.customerName().trim(), trimToNull(r.city()),
                trimToNull(r.quote()), r.deliveredOn(), r.consent(), r.published());
    }

    private PublicDeliveredCar toPublic(DeliveredCar d) {
        return new PublicDeliveredCar(d.id(), d.carTitle(), d.customerName(), d.city(), d.quote(), d.deliveredOn(),
                photoUrl(d), d.carPublic() ? d.carSlug() : null);
    }

    private AdminDeliveredCar toAdmin(DeliveredCar d) {
        return new AdminDeliveredCar(d.id(), d.carId(), d.carSlug(), d.carTitle(), d.customerName(), d.city(),
                d.quote(), d.deliveredOn(), photoUrl(d), d.consent(), d.published(), d.createdAt(), d.updatedAt());
    }

    private String photoUrl(DeliveredCar d) {
        return d.photoPath() == null ? null : storage.publicUrl(d.photoPath());
    }

    private static NotFoundException notFound(long id) {
        return new NotFoundException("Delivered car not found: " + id);
    }

    private static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
