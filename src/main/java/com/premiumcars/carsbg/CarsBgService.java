package com.premiumcars.carsbg;

import java.time.Clock;
import java.time.Instant;
import java.time.Year;
import java.time.ZoneId;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.premiumcars.catalog.domain.Car;
import com.premiumcars.catalog.repo.CarRepository;
import com.premiumcars.common.BadRequestException;
import com.premiumcars.common.NotFoundException;
import com.premiumcars.marketplace.MarketplaceService;
import com.premiumcars.marketplace.ModelMatch;
import com.premiumcars.marketplace.Site;

/** The admin panel's cars.bg module: the form filled in from a car, cars.bg's lists, where a car was posted. */
@Service
public class CarsBgService {

    private static final Logger log = LoggerFactory.getLogger(CarsBgService.class);
    private static final ZoneId SOFIA = ZoneId.of("Europe/Sofia");
    /** https://www.cars.bg/offer/68f0c1e2a4b5c6d7e8f90123 */
    private static final Pattern OFFER_URL =
            Pattern.compile("^https://(?:www\\.)?cars\\.bg/offer/([0-9a-fA-F]{24})(?:[?#].*)?$");
    private static final Pattern OFFER_ID = Pattern.compile("^[0-9a-fA-F]{24}$");

    /**
     * @param listing where the car is posted already (null when it is not): the panel does not post it twice
     */
    public record Form(CarsBgDraft draft, List<CarsBgMapper.Warning> warnings, ModelMatch.Status match,
                       CarsBgListing listing, Instant catalogCapturedAt) {
    }

    private final CarRepository cars;
    private final CarsBgRepository repo;
    private final MarketplaceService marketplace;
    private final Clock clock;

    public CarsBgService(CarRepository cars, CarsBgRepository repo, MarketplaceService marketplace, Clock clock) {
        this.cars = cars;
        this.repo = repo;
        this.marketplace = marketplace;
        this.clock = clock;
    }

    public Form form(long carId) {
        Car car = cars.findById(carId).orElseThrow(() -> new NotFoundException("Car not found: " + carId));
        CarsBgCatalog catalog = repo.catalog();
        ModelMatch match = marketplace.match(Site.CARS_BG, catalog.toSiteCatalog(), car.specs().make(),
                car.specs().model(), car.specs().trim());
        CarsBgMapper.Result r = CarsBgMapper.map(car, match, Year.now(clock.withZone(SOFIA)).getValue());
        return new Form(r.draft(), r.warnings(), match.status(), repo.listing(carId).orElse(null),
                catalog.capturedAt());
    }

    public CarsBgCatalog catalog() {
        return repo.catalog();
    }

    /** Keeps the lists an admin's browser read from cars.bg, unless older than the stored ones. */
    public CarsBgCatalog saveCatalog(CarsBgCatalog catalog, String by) {
        if (catalog.isEmpty()) {
            throw new BadRequestException("The cars.bg lists are empty");
        }
        CarsBgCatalog current = repo.catalog();
        if (current.capturedAt() != null && catalog.capturedAt() != null
                && !catalog.capturedAt().isAfter(current.capturedAt())) {
            return current;
        }
        CarsBgCatalog stamped = catalog.capturedAt() != null ? catalog
                : new CarsBgCatalog(catalog.brands(), clock.instant());
        repo.saveCatalog(stamped, by);
        log.info("Admin '{}' sent cars.bg's lists ({} makes, {} models)", by, stamped.brands().size(),
                stamped.brands().stream().mapToInt(b -> b.models().size()).sum());
        marketplace.catalogChanged(Site.CARS_BG);
        return stamped;
    }

    /** The extension saw the offer published (the admin pressed cars.bg's button). */
    public CarsBgListing recordListing(long carId, String offerId, String url, String by) {
        if (cars.findById(carId).isEmpty()) {
            throw new NotFoundException("Car not found: " + carId);
        }
        String id = offerId == null || offerId.isBlank() ? null : offerId.trim();
        if (url != null && !url.isBlank()) {
            Matcher m = OFFER_URL.matcher(url.trim());
            if (!m.matches()) {
                throw new BadRequestException("Not a cars.bg offer link: " + url);
            }
            id = id == null ? m.group(1) : id;
        }
        if (id == null) {
            throw new BadRequestException("An offer id or link is needed");
        }
        if (!OFFER_ID.matcher(id).matches()) {
            throw new BadRequestException("Not a cars.bg offer id: " + id);
        }
        id = id.toLowerCase(java.util.Locale.ROOT);
        CarsBgListing l = repo.saveListing(carId, id, "https://www.cars.bg/offer/" + id, clock.instant(), by);
        log.info("Admin '{}' posted car {} to cars.bg ({})", by, carId, l.url());
        return l;
    }

    /** The admin deleted the offer on cars.bg: forget it, so the car can be posted again. */
    public void forgetListing(long carId, String by) {
        if (repo.deleteListing(carId)) {
            log.info("Admin '{}' says car {} is no longer on cars.bg", by, carId);
        }
    }
}
