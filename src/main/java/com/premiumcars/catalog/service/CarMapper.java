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
import com.premiumcars.catalog.web.dto.CarDtos.AdminCarSummary;
import com.premiumcars.catalog.web.dto.CarDtos.AdminImage;
import com.premiumcars.catalog.web.dto.CarDtos.CarDetail;
import com.premiumcars.catalog.web.dto.CarDtos.CarSummary;
import com.premiumcars.media.ImageStorage;
import com.premiumcars.pricing.PriceBreakdown;
import com.premiumcars.pricing.PriceCalculator;
import com.premiumcars.pricing.PricingProfile;
import com.premiumcars.pricing.PricingProfileRepository;

/** Domain records -> API DTOs. */
@Component
public class CarMapper {

    private final ImageStorage storage;
    private final PriceCalculator priceCalculator;
    private final PricingProfileRepository profiles;

    public CarMapper(ImageStorage storage, PriceCalculator priceCalculator, PricingProfileRepository profiles) {
        this.storage = storage;
        this.priceCalculator = priceCalculator;
        this.profiles = profiles;
    }

    public CarSummary toSummary(CarListRow row) {
        Car c = row.car();
        CarSpecs s = c.specs();
        List<String> photos = photoUrls(row);
        return new CarSummary(c.id(), c.slug(), s.title(), s.make(), s.model(), s.year(), s.mileageKm(),
                s.fuelType(), s.transmission(), s.powerHp(), s.batteryKwh(), s.electricRangeKm(),
                Origin.of(c.source()), c.status(), c.totalPriceEur(), photos.isEmpty() ? null : photos.getFirst(),
                photos);
    }

    public AdminCarSummary toAdminSummary(CarListRow row) {
        Car c = row.car();
        CarSpecs s = c.specs();
        List<String> photos = photoUrls(row);
        String cover = photos.isEmpty() ? null : photos.getFirst();
        return new AdminCarSummary(c.id(), c.slug(), s.title(), s.year(), s.mileageKm(), c.status(),
                c.totalPriceEur(), cover, c.source(), c.sourceRef(), c.sourceUrl());
    }

    /** Where browsers load the photo from: our media storage, or the source's CDN for a linked photo. */
    public String url(CarImage image) {
        return image.linked() ? image.sourceUrl() : storage.publicUrl(image.storagePath());
    }

    /**
     * The photo's bytes from our own origin, stored or linked alike: for the admin panel and its mobile.bg extension,
     * which cannot read other sites' photos (see PhotoFileController).
     */
    public static String fileUrl(long imageId) {
        return "/media/photo/" + imageId;
    }

    /** Where browsers load a list row's photos from (see {@link CarListRow}). */
    private List<String> photoUrls(CarListRow row) {
        return row.photoRefs().stream()
                .map(ref -> ref.startsWith("s:") ? storage.publicUrl(ref.substring(2)) : ref.substring(2))
                .toList();
    }

    public CarDetail toDetail(Car c, List<CarImage> images) {
        return new CarDetail(c.id(), c.slug(), c.status(), Origin.of(c.source()), c.specs(),
                images.stream().map(this::url).toList(),
                c.totalPriceEur());
    }

    /** @param price the breakdown the car's price was calculated from */
    public AdminCar toAdmin(Car c, List<CarImage> images, PriceBreakdown price) {
        return new AdminCar(c.id(), c.slug(), c.status(), c.source(), c.sourceUrl(), c.sourceRef(),
                c.sourceLocation(), c.specs(), c.pricing(), price,
                images.stream()
                        .map(i -> new AdminImage(i.id(), url(i), i.sourceUrl(), i.position(), i.linked(),
                                fileUrl(i.id())))
                        .toList(),
                c.createdAt(), c.updatedAt(), c.publishedAt());
    }

    /**
     * Price breakdown from today's rates, the current profile and the car's corrections; null when there is no
     * price at all. Used when a price is (re)calculated; the admin panel shows the kept one.
     */
    public PriceBreakdown breakdown(PricingInputs p) {
        PricingProfile profile = p.profile() == null ? null : profiles.find(p.profile()).orElse(null);
        return breakdown(p, profile);
    }

    public PriceBreakdown breakdown(PricingInputs p, PricingProfile profile) {
        return priceCalculator.calculate(p.sourcePrice(), p.sourceCurrency(), profile, p.profitEur(), p.fixedTotalEur());
    }
}
