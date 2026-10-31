package com.premiumcars.catalog.service;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
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
import com.premiumcars.catalog.web.dto.CarDtos.AdminCarCounts;
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

    /**
     * Saves a site's price formula. It applies to cars imported from now on (and to a car whose price fields
     * change, or that is recalculated by hand); existing prices stay, since they may be posted already.
     */
    @Transactional
    public PricingProfile updatePricingProfile(PriceProfile id, PricingProfile values) {
        if (!profiles.update(id, values)) {
            throw new NotFoundException("Price profile not found: " + id);
        }
        return profiles.find(id).orElseThrow();
    }

    /**
     * Cars saved before prices were kept with their breakdown: keep their price, store the breakdown of today's
     * calculation next to it (the price itself does not change).
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void keepBreakdownsOfOlderCars() {
        List<Car> older = cars.findWithoutPriceBreakdown();
        for (Car c : older) {
            PriceBreakdown b = mapper.breakdown(c.pricing());
            if (b != null && c.totalPriceEur() != null && b.totalEur().compareTo(c.totalPriceEur()) != 0) {
                log.info("Car {}: kept price {} € (today's calculation gives {} €)", c.id(), c.totalPriceEur(),
                        b.totalEur());
                b = withTotal(b, c.totalPriceEur());
            }
            cars.savePrice(c.id(), c.totalPriceEur() != null ? c.totalPriceEur() : b == null ? null : b.totalEur(), b);
        }
        if (!older.isEmpty()) {
            log.info("Stored the price breakdown of {} older car(s); their prices are unchanged", older.size());
        }
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
        return new FilterOptions(cars.findMakes(PUBLIC_STATUSES), cars.findModels(PUBLIC_STATUSES),
                Arrays.asList(FuelType.values()),
                Arrays.asList(Transmission.values()), Arrays.asList(Origin.values()));
    }

    // ---------------------------------------------------------------- admin

    @Transactional(readOnly = true)
    /** The admin list; {@code q} matches the title, make, model and the source listing's URL or id. */
    public PageResponse<AdminCarSummary> searchAdmin(CarStatus status, String q, int page, int size) {
        return searchAdmin(status, q, null, CarSort.UPDATED, page, size);
    }

    /** The admin list: by status, text, source site, in the chosen order, a page at a time. */
    @Transactional(readOnly = true)
    public PageResponse<AdminCarSummary> searchAdmin(CarStatus status, String q, CarSource source, CarSort sort,
                                                     int page, int size) {
        Set<CarStatus> statuses = status == null ? EnumSet.allOf(CarStatus.class) : EnumSet.of(status);
        var filter = new CarFilter(q, null, null, null, null, null, null, null, null, null, null);
        int p = Math.max(page, 0);
        int s = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        CarPage result = cars.searchAdmin(filter, statuses, source, sort, p, s);
        return PageResponse.of(result.rows(), p, s, result.total(), mapper::toAdminSummary);
    }

    /** How many cars the admin list's search and source match, in all and per status (the filter buttons). */
    @Transactional(readOnly = true)
    public AdminCarCounts adminCounts(String q, CarSource source) {
        var filter = new CarFilter(q, null, null, null, null, null, null, null, null, null, null);
        Map<CarStatus, Long> byStatus = cars.countAdminByStatus(filter, source);
        return new AdminCarCounts(byStatus.values().stream().mapToLong(Long::longValue).sum(), byStatus);
    }

    @Transactional(readOnly = true)
    public AdminCar getAdmin(long id) {
        return loadAdmin(id);
    }

    @Transactional(readOnly = true)
    public Optional<AdminCar> findBySourceUrl(String sourceUrl) {
        return cars.findBySourceUrl(sourceUrl).map(this::toAdmin);
    }

    /** Creates a DRAFT from imported data. The slug needs the generated id, so it is set right after the insert. */
    @Transactional
    public AdminCar createDraft(ImportedCar data, CarSource source, String sourceUrl) {
        CarSpecs specs = data.specs().toBuilder().build();
        PricingInputs pricing = PricingInputs.EMPTY.withSourcePrice(data.price(), data.currency())
                .withProfile(defaultProfile(source));
        PriceBreakdown price = mapper.breakdown(pricing);
        long id = cars.insert("tmp-" + UUID.randomUUID(), CarStatus.DRAFT, source, sourceUrl,
                truncate(data.sourceRef(), 255), truncate(data.sourceLocation(), 100), specs, pricing,
                price == null ? null : price.totalEur());
        cars.savePrice(id, price == null ? null : price.totalEur(), price);
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
        if (samePricing(pricing, existing.pricing())) {
            // other fields only: the price stays as it is (it may be posted already)
            cars.update(id, specs, pricing, existing.totalPriceEur());
        } else {
            PriceBreakdown price = mapper.breakdown(pricing);
            cars.update(id, specs, pricing, price == null ? null : price.totalEur());
            cars.savePrice(id, price == null ? null : price.totalEur(), price);
        }
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
        photos.stream().filter(i -> !i.linked()).forEach(i -> storage.delete(i.storagePath()));
    }

    @Transactional
    public AdminCar deleteImage(long carId, long imageId) {
        CarImage image = findImage(carId, imageId);
        images.delete(carId, imageId);
        if (!image.linked()) {
            storage.delete(image.storagePath());
        }
        return loadAdmin(carId);
    }

    @Transactional
    public AdminCar makeCover(long carId, long imageId) {
        findImage(carId, imageId);
        images.makeCover(carId, imageId);
        return loadAdmin(carId);
    }

    /** One car with today's exchange rate and its formula as it is now (the admin's explicit choice). */
    @Transactional
    public AdminCar recalculatePrice(long id) {
        Car c = find(id);
        PriceBreakdown b = mapper.breakdown(c.pricing());
        cars.savePrice(id, b == null ? null : b.totalEur(), b);
        log.info("Car {} recalculated by hand: {} € (was {} €)", id, b == null ? null : b.totalEur(), c.totalPriceEur());
        return loadAdmin(id);
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
        return toAdmin(find(id));
    }

    /** With the breakdown the price was calculated from (today's calculation only for a car without one). */
    private AdminCar toAdmin(Car c) {
        PriceBreakdown kept = cars.findPriceBreakdown(c.id()).orElseGet(() -> mapper.breakdown(c.pricing()));
        return mapper.toAdmin(c, images.findByCarId(c.id()), kept);
    }

    /** Same values (amounts compared by value: 25000 equals 25000.00 from the database). */
    static boolean samePricing(PricingInputs a, PricingInputs b) {
        return sameAmount(a.sourcePrice(), b.sourcePrice())
                && java.util.Objects.equals(a.sourceCurrency(), b.sourceCurrency())
                && a.profile() == b.profile()
                && sameAmount(a.profitEur(), b.profitEur())
                && sameAmount(a.fixedTotalEur(), b.fixedTotalEur());
    }

    private static boolean sameAmount(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    private static PriceBreakdown withTotal(PriceBreakdown b, BigDecimal total) {
        return b.withTotal(total);
    }

    private Car find(long id) {
        return cars.findById(id).orElseThrow(() -> new NotFoundException("Car not found: " + id));
    }

    private CarImage findImage(long carId, long imageId) {
        return images.find(carId, imageId)
                .orElseThrow(() -> new NotFoundException("Image not found: " + imageId));
    }

    /** Imported cars are priced with their site's formula; other cars get one chosen in the editor. */
    static PriceProfile defaultProfile(CarSource source) {
        return switch (source) {
            case AUTOHOME, GUAZI -> PriceProfile.AUTOHOME; // both sell cars in China, priced in USD
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
