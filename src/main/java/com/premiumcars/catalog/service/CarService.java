package com.premiumcars.catalog.service;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.premiumcars.catalog.domain.Car;
import com.premiumcars.catalog.domain.CarImage;
import com.premiumcars.catalog.domain.CarSort;
import com.premiumcars.catalog.domain.CarSource;
import com.premiumcars.catalog.domain.CarSpecs;
import com.premiumcars.catalog.domain.CarStatus;
import com.premiumcars.catalog.domain.FuelType;
import com.premiumcars.catalog.domain.NewCarImage;
import com.premiumcars.catalog.domain.Origin;
import com.premiumcars.catalog.domain.PricingInputs;
import com.premiumcars.catalog.domain.Transmission;
import com.premiumcars.catalog.repo.CarImageRepository;
import com.premiumcars.catalog.repo.CarRepository;
import com.premiumcars.catalog.repo.CarRepository.CarPage;
import com.premiumcars.catalog.web.dto.CarDtos.AdminCar;
import com.premiumcars.catalog.web.dto.CarDtos.AdminCarSummary;
import com.premiumcars.catalog.web.dto.CarDtos.CarDetail;
import com.premiumcars.catalog.web.dto.CarDtos.CarSummary;
import com.premiumcars.catalog.web.dto.CarDtos.CarUpdateRequest;
import com.premiumcars.catalog.web.dto.CarDtos.FilterOptions;
import com.premiumcars.catalog.web.dto.CarFilter;
import com.premiumcars.common.NotFoundException;
import com.premiumcars.common.PageResponse;
import com.premiumcars.importing.ImportedCar;
import com.premiumcars.media.ImageStorage;
import com.premiumcars.pricing.EcbExchangeRateProvider;
import com.premiumcars.pricing.PriceBreakdown;
import com.premiumcars.pricing.PriceProfile;
import com.premiumcars.pricing.PricingProfile;
import com.premiumcars.pricing.PricingProfileRepository;

@Service
public class CarService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CarService.class);

    /** Statuses visible on the public site. */
    public static final Set<CarStatus> PUBLIC_STATUSES = EnumSet.of(CarStatus.PUBLISHED, CarStatus.RESERVED);
    private static final int MAX_PAGE_SIZE = 48;

    private final CarRepository cars;
    private final CarImageRepository images;
    private final CarMapper mapper;
    private final ImageStorage storage;
    private final PricingProfileRepository profiles;

    public CarService(CarRepository cars, CarImageRepository images, CarMapper mapper, ImageStorage storage,
                      PricingProfileRepository profiles) {
        this.cars = cars;
        this.images = images;
        this.mapper = mapper;
        this.storage = storage;
        this.profiles = profiles;
    }

    // ---------------------------------------------------------------- price profiles

    public List<PricingProfile> pricingProfiles() {
        return profiles.findAll();
    }

    /** Saves a site's price formula and reprices every car. */
    @Transactional
    public PricingProfile updatePricingProfile(PriceProfile id, PricingProfile values) {
        if (!profiles.update(id, values)) {
            throw new NotFoundException("Price profile not found: " + id);
        }
        recalculateAllPrices();
        return profiles.find(id).orElseThrow();
    }

    /** Prices follow the exchange rates and the formulas: recalculated on every start. */
    @EventListener(ApplicationReadyEvent.class)
    public void recalculatePricesOnStartup() {
        recalculateAllPrices();
    }

    /** The ECB rates of a new day (or the first ones after a start) reprice every car. */
    @EventListener
    public void onExchangeRatesUpdated(EcbExchangeRateProvider.ExchangeRatesUpdated event) {
        int n = recalculateAllPrices();
        log.info("Repriced {} cars with the exchange rates of {}", n, event.date());
    }

    // ---------------------------------------------------------------- public

    @Transactional(readOnly = true)
    public PageResponse<CarSummary> searchPublic(CarFilter filter, String sort, int page, int size) {
        return search(filter, PUBLIC_STATUSES, CarSort.fromParam(sort), page, size);
    }

    @Transactional(readOnly = true)
    public CarDetail getPublic(String slug) {
        Car car = cars.findBySlug(slug)
                .filter(Car::isPubliclyVisible)
                .orElseThrow(() -> new NotFoundException("Car not found: " + slug));
        return mapper.toDetail(car, images.findByCarId(car.id()));
    }

    @Transactional(readOnly = true)
    public FilterOptions filterOptions() {
        return new FilterOptions(cars.findMakes(PUBLIC_STATUSES), Arrays.asList(FuelType.values()),
                Arrays.asList(Transmission.values()), Arrays.asList(Origin.values()));
    }

    // ---------------------------------------------------------------- admin

    @Transactional(readOnly = true)
    /** The admin list; {@code q} matches the title, make, model and the source listing's URL or id. */
    public PageResponse<AdminCarSummary> searchAdmin(CarStatus status, String q, int page, int size) {
        Set<CarStatus> statuses = status == null ? EnumSet.allOf(CarStatus.class) : EnumSet.of(status);
        var filter = new CarFilter(q, null, null, null, null, null, null, null, null, null);
        int p = Math.max(page, 0);
        int s = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        CarPage result = cars.searchAdmin(filter, statuses, CarSort.UPDATED, p, s);
        return PageResponse.of(result.rows(), p, s, result.total(), mapper::toAdminSummary);
    }

    @Transactional(readOnly = true)
    public AdminCar getAdmin(long id) {
        return loadAdmin(id);
    }

    @Transactional(readOnly = true)
    public Optional<AdminCar> findBySourceUrl(String sourceUrl) {
        return cars.findBySourceUrl(sourceUrl).map(c -> mapper.toAdmin(c, images.findByCarId(c.id())));
    }

    /** Creates a DRAFT from imported data. The slug needs the generated id, so it is set right after the insert. */
    @Transactional
    public AdminCar createDraft(ImportedCar data, CarSource source, String sourceUrl) {
        CarSpecs specs = data.specs().toBuilder().build();
        PricingInputs pricing = PricingInputs.EMPTY.withSourcePrice(data.price(), data.currency())
                .withProfile(defaultProfile(source));
        long id = cars.insert("tmp-" + UUID.randomUUID(), CarStatus.DRAFT, source, sourceUrl,
                truncate(data.sourceRef(), 255), truncate(data.sourceLocation(), 100), specs, pricing,
                totalPrice(pricing));
        cars.updateSlug(id, slugFor(specs, id));
        return loadAdmin(id);
    }

    @Transactional
    public AdminCar attachImages(long carId, List<NewCarImage> newImages) {
        find(carId);
        newImages.forEach(i -> images.append(carId, i.storagePath(), i.sourceUrl()));
        return loadAdmin(carId);
    }

    @Transactional
    public AdminCar update(long id, CarUpdateRequest r) {
        Car existing = find(id);
        CarSpecs specs = r.specs().toBuilder().build();
        PricingInputs pricing = r.pricing().profile() != null
                ? r.pricing() : r.pricing().withProfile(defaultProfile(existing.source()));
        cars.update(id, specs, pricing, totalPrice(pricing));
        if (existing.status() == CarStatus.DRAFT) {
            // Keep links of published cars stable (SEO, shared links); drafts may still be renamed.
            cars.updateSlug(id, slugFor(specs, id));
        }
        return loadAdmin(id);
    }

    @Transactional
    public AdminCar changeStatus(long id, CarStatus status) {
        find(id);
        cars.updateStatus(id, status);
        return loadAdmin(id);
    }

    @Transactional
    public void delete(long id) {
        find(id);
        List<CarImage> photos = images.findByCarId(id);
        cars.delete(id);
        photos.forEach(i -> storage.delete(i.storagePath()));
    }

    @Transactional
    public AdminCar deleteImage(long carId, long imageId) {
        CarImage image = findImage(carId, imageId);
        images.delete(carId, imageId);
        storage.delete(image.storagePath());
        return loadAdmin(carId);
    }

    @Transactional
    public AdminCar makeCover(long carId, long imageId) {
        findImage(carId, imageId);
        images.makeCover(carId, imageId);
        return loadAdmin(carId);
    }

    /** Re-applies current exchange rates and price profiles to every car. Returns the number of cars. */
    @Transactional
    public int recalculateAllPrices() {
        Map<PriceProfile, PricingProfile> byId = profiles.findAll().stream()
                .collect(Collectors.toMap(PricingProfile::id, Function.identity()));
        List<Car> all = cars.findAll();
        for (Car c : all) {
            PricingProfile profile = c.pricing().profile() == null ? null : byId.get(c.pricing().profile());
            PriceBreakdown b = mapper.breakdown(c.pricing(), profile);
            cars.updateTotalPrice(c.id(), b == null ? null : b.totalEur());
        }
        return all.size();
    }

    // ---------------------------------------------------------------- helpers

    private PageResponse<CarSummary> search(CarFilter filter, Set<CarStatus> statuses, CarSort sort,
                                            int page, int size) {
        int p = Math.max(page, 0);
        int s = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        CarPage result = cars.search(filter, statuses, sort, p, s);
        return PageResponse.of(result.rows(), p, s, result.total(), mapper::toSummary);
    }

    private AdminCar loadAdmin(long id) {
        return mapper.toAdmin(find(id), images.findByCarId(id));
    }

    private Car find(long id) {
        return cars.findById(id).orElseThrow(() -> new NotFoundException("Car not found: " + id));
    }

    private CarImage findImage(long carId, long imageId) {
        return images.find(carId, imageId)
                .orElseThrow(() -> new NotFoundException("Image not found: " + imageId));
    }

    private BigDecimal totalPrice(PricingInputs pricing) {
        PriceBreakdown b = mapper.breakdown(pricing);
        return b == null ? null : b.totalEur();
    }

    /** Imported cars are priced with their site's formula; other cars get one chosen in the editor. */
    static PriceProfile defaultProfile(CarSource source) {
        return switch (source) {
            case AUTOHOME -> PriceProfile.AUTOHOME;
            case AUTOWINI -> PriceProfile.AUTOWINI;
            case OTHER, MANUAL -> null;
        };
    }

    private static String slugFor(CarSpecs s, long id) {
        return Slugs.forCar(s.make(), s.model(), s.title(), s.year(), id);
    }

    private static String truncate(String s, int max) {
        return s == null ? null : (s.length() > max ? s.substring(0, max) : s);
    }
}
