package com.premiumcars.catalog.service;

import java.util.List;

import org.springframework.stereotype.Component;

import com.premiumcars.catalog.domain.Car;
import com.premiumcars.catalog.domain.CarDetails;
import com.premiumcars.catalog.domain.CarImage;
import com.premiumcars.catalog.domain.Origin;
import com.premiumcars.catalog.repo.CarRepository.CarListRow;
import com.premiumcars.catalog.web.dto.CarDtos.AdminCar;
import com.premiumcars.catalog.web.dto.CarDtos.AdminImage;
import com.premiumcars.catalog.web.dto.CarDtos.CarDetail;
import com.premiumcars.catalog.web.dto.CarDtos.CarSummary;
import com.premiumcars.catalog.web.dto.CarDtos.PublicPrice;
import com.premiumcars.media.ImageStorage;
import com.premiumcars.pricing.PriceBreakdown;
import com.premiumcars.pricing.PriceCalculator;

/** Domain records -> API DTOs. */
@Component
public class CarMapper {

    private final ImageStorage storage;
    private final PriceCalculator priceCalculator;

    public CarMapper(ImageStorage storage, PriceCalculator priceCalculator) {
        this.storage = storage;
        this.priceCalculator = priceCalculator;
    }

    public CarSummary toSummary(CarListRow row) {
        Car c = row.car();
        CarDetails d = c.details();
        String cover = row.coverPath() == null ? null : storage.publicUrl(row.coverPath());
        return new CarSummary(c.id(), c.slug(), d.title(), d.make(), d.model(), d.year(), d.mileageKm(),
                d.fuelType(), d.transmission(), Origin.of(c.source()), c.status(), c.totalPriceEur(), cover);
    }

    public CarDetail toDetail(Car c, List<CarImage> images) {
        CarDetails d = c.details();
        return new CarDetail(c.id(), c.slug(), d.title(), d.make(), d.model(), d.trim(), d.year(), d.mileageKm(),
                d.fuelType(), d.transmission(), d.bodyType(), d.engine(), d.powerHp(), d.color(), d.description(),
                d.specs(), Origin.of(c.source()), c.status(),
                images.stream().map(i -> storage.publicUrl(i.storagePath())).toList(),
                PublicPrice.of(breakdown(d)));
    }

    public AdminCar toAdmin(Car c, List<CarImage> images) {
        CarDetails d = c.details();
        return new AdminCar(c.id(), c.slug(), c.status(), c.source(), c.sourceUrl(), c.sourceRef(), d.title(),
                d.make(), d.model(), d.trim(), d.year(), d.mileageKm(), d.fuelType(), d.transmission(),
                d.bodyType(), d.engine(), d.powerHp(), d.color(), d.description(), d.specs(), d.sourcePrice(),
                d.sourceCurrency(), d.shippingEur(), d.marginEur(), d.extraDutyRate(), breakdown(d),
                images.stream()
                        .map(i -> new AdminImage(i.id(), storage.publicUrl(i.storagePath()), i.sourceUrl(),
                                i.position()))
                        .toList(),
                c.createdAt(), c.updatedAt(), c.publishedAt());
    }

    /** Price breakdown from the current settings; null when the source price is unknown. */
    public PriceBreakdown breakdown(CarDetails d) {
        return priceCalculator.calculate(d.sourcePrice(), d.sourceCurrency(), d.shippingEur(), d.marginEur(),
                d.extraDutyRate());
    }
}
