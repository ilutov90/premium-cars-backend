package com.premiumcars.catalog.service;

import java.util.List;

import org.springframework.stereotype.Component;

import com.premiumcars.catalog.domain.Car;
import com.premiumcars.catalog.domain.CarImage;
import com.premiumcars.catalog.domain.CarSpecs;
import com.premiumcars.catalog.domain.Origin;
import com.premiumcars.catalog.domain.PricingInputs;
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
        CarSpecs s = c.specs();
        String cover = row.coverPath() == null ? null : storage.publicUrl(row.coverPath());
        return new CarSummary(c.id(), c.slug(), s.title(), s.make(), s.model(), s.year(), s.mileageKm(),
                s.fuelType(), s.transmission(), s.powerHp(), s.batteryKwh(), s.electricRangeKm(),
                Origin.of(c.source()), c.status(), c.totalPriceEur(), cover);
    }

    public CarDetail toDetail(Car c, List<CarImage> images) {
        return new CarDetail(c.id(), c.slug(), c.status(), Origin.of(c.source()), c.specs(),
                images.stream().map(i -> storage.publicUrl(i.storagePath())).toList(),
                PublicPrice.of(breakdown(c.pricing())));
    }

    public AdminCar toAdmin(Car c, List<CarImage> images) {
        return new AdminCar(c.id(), c.slug(), c.status(), c.source(), c.sourceUrl(), c.sourceRef(),
                c.sourceLocation(), c.specs(), c.pricing(), breakdown(c.pricing()),
                images.stream()
                        .map(i -> new AdminImage(i.id(), storage.publicUrl(i.storagePath()), i.sourceUrl(),
                                i.position()))
                        .toList(),
                c.createdAt(), c.updatedAt(), c.publishedAt());
    }

    /** Price breakdown from the current settings; null when the source price is unknown. */
    public PriceBreakdown breakdown(PricingInputs p) {
        return priceCalculator.calculate(p.sourcePrice(), p.sourceCurrency(), p.shippingEur(), p.marginEur(),
                p.extraDutyRate());
    }
}
