package com.premiumcars.mobilebg;

import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.premiumcars.catalog.domain.Car;
import com.premiumcars.catalog.domain.CarFeature;
import com.premiumcars.catalog.repo.CarRepository;
import com.premiumcars.common.BadRequestException;
import com.premiumcars.common.NotFoundException;

/** The admin panel's mobile.bg module: the form filled in from a car, mobile.bg's lists, where a car was posted. */
@Service
public class MobileBgService {

    private static final Logger log = LoggerFactory.getLogger(MobileBgService.class);
    /** www.mobile.bg/obiava-11792827040577366-toyota-corolla... */
    private static final Pattern LISTING_URL = Pattern.compile("^https://(www\\.)?mobile\\.bg/[^\\s]{1,500}$");

    /**
     * @param extraValues mobile.bg's checkbox value of every extra, for the admin panel to send
     * @param listing     where the car is posted already; null when it is not
     */
    public record Form(MobileBgDraft draft, List<MobileBgMapper.Warning> warnings,
                       Map<CarFeature, String> extraValues, MobileBgListing listing,
                       Instant catalogCapturedAt) {
    }

    private final CarRepository cars;
    private final MobileBgRepository repo;
    private final Clock clock;

    public MobileBgService(CarRepository cars, MobileBgRepository repo, Clock clock) {
        this.cars = cars;
        this.repo = repo;
        this.clock = clock;
    }

    public Form form(long carId) {
        Car car = cars.findById(carId).orElseThrow(() -> new NotFoundException("Car not found: " + carId));
        MobileBgCatalog catalog = repo.catalog();
        MobileBgMapper.Result r = MobileBgMapper.map(car, catalog);
        Map<CarFeature, String> values = new LinkedHashMap<>();
        Arrays.stream(CarFeature.values()).forEach(f -> values.put(f, f.mobileBgLabel()));
        return new Form(r.draft(), r.warnings(), values, repo.listing(carId).orElse(null), catalog.capturedAt());
    }

    public MobileBgCatalog catalog() {
        return repo.catalog();
    }

    /** Keeps the lists an admin's browser read from mobile.bg, unless older than the stored ones. */
    public MobileBgCatalog saveCatalog(MobileBgCatalog catalog, String by) {
        if (catalog.isEmpty()) {
            throw new BadRequestException("The mobile.bg lists are empty");
        }
        MobileBgCatalog current = repo.catalog();
        if (current.capturedAt() != null && catalog.capturedAt() != null
                && !catalog.capturedAt().isAfter(current.capturedAt())) {
            return current;
        }
        MobileBgCatalog stamped = catalog.capturedAt() != null ? catalog
                : new MobileBgCatalog(catalog.brands(), catalog.selects(), catalog.extras(), clock.instant());
        repo.saveCatalog(stamped, by);
        log.info("Admin '{}' sent mobile.bg's lists ({} brands)", by, stamped.brands().size());
        return stamped;
    }

    public MobileBgListing recordListing(long carId, String listingId, String url, String by) {
        if (cars.findById(carId).isEmpty()) {
            throw new NotFoundException("Car not found: " + carId);
        }
        if ((listingId == null || listingId.isBlank()) && (url == null || url.isBlank())) {
            throw new BadRequestException("A listing number or link is needed");
        }
        if (url != null && !url.isBlank() && !LISTING_URL.matcher(url).matches()) {
            throw new BadRequestException("Not a mobile.bg link: " + url);
        }
        MobileBgListing l = repo.saveListing(carId, blankToNull(listingId), blankToNull(url), clock.instant(), by);
        log.info("Admin '{}' posted car {} to mobile.bg ({})", by, carId, l.url() != null ? l.url() : l.listingId());
        return l;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
