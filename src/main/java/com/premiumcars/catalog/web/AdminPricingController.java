package com.premiumcars.catalog.web;

import java.util.List;

import java.security.Principal;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.premiumcars.catalog.domain.CarSource;
import com.premiumcars.catalog.repo.PriceRecalcRepository.Item;
import com.premiumcars.catalog.repo.PriceRecalcRepository.Run;
import com.premiumcars.catalog.service.CarService;
import com.premiumcars.catalog.service.PriceRecalcService;
import com.premiumcars.common.PageResponse;
import com.premiumcars.pricing.EcbExchangeRateProvider;
import com.premiumcars.pricing.PriceProfile;
import com.premiumcars.pricing.PricingProfile;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * The price formulas of the source sites (saving one changes new imports only) and the recalculations of existing
 * prices, started by hand.
 */
@RestController
@RequestMapping("/api/admin/pricing-profiles")
public class AdminPricingController {

    private final CarService carService;
    private final EcbExchangeRateProvider rates;
    private final PriceRecalcService recalc;

    public AdminPricingController(CarService carService, EcbExchangeRateProvider rates, PriceRecalcService recalc) {
        this.carService = carService;
        this.rates = rates;
        this.recalc = recalc;
    }

    /**
     * Starts a recalculation.
     *
     * @param source the cars of this site; null = all cars
     * @param mode   STORED: the FOB kept at the import; SOURCE: read again from each listing (slow)
     */
    public record RecalcRequest(CarSource source, @NotNull PriceRecalcService.Mode mode) {
    }

    @PostMapping("/recalculations")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Run startRecalculation(@Valid @RequestBody RecalcRequest request, Principal principal) {
        return recalc.start(request.source(), request.mode(), principal == null ? null : principal.getName());
    }

    @GetMapping("/recalculations")
    public List<Run> recalculations() {
        return recalc.recent();
    }

    /** A recalculation's report: kind CHANGED / FAILED (none = both), only the cars posted somewhere. */
    @GetMapping("/recalculations/{id}/items")
    public PageResponse<Item> recalculationItems(@PathVariable long id, @RequestParam(required = false) String kind,
                                                 @RequestParam(defaultValue = "false") boolean posted,
                                                 @RequestParam(defaultValue = "0") int page,
                                                 @RequestParam(defaultValue = "50") int size) {
        return recalc.items(id, kind, posted, page, size);
    }

    @DeleteMapping("/recalculations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteRecalculation(@PathVariable long id) {
        recalc.delete(id);
    }

    /** The exchange rates in use (source ECB with its date, or CONFIG until the ECB answers). */
    @GetMapping("/exchange-rates")
    public EcbExchangeRateProvider.Rates exchangeRates() {
        return rates.current();
    }

    @GetMapping
    public List<PricingProfile> list() {
        return carService.pricingProfiles();
    }

    @PutMapping("/{id}")
    public PricingProfile update(@PathVariable PriceProfile id, @Valid @RequestBody PricingProfile values) {
        return carService.updatePricingProfile(id, values);
    }
}
