package com.premiumcars.catalog.web;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.premiumcars.catalog.service.CarService;
import com.premiumcars.pricing.EcbExchangeRateProvider;
import com.premiumcars.pricing.PriceProfile;
import com.premiumcars.pricing.PricingProfile;

import jakarta.validation.Valid;

/** The price formulas of the source sites. Saving one reprices every car. */
@RestController
@RequestMapping("/api/admin/pricing-profiles")
public class AdminPricingController {

    private final CarService carService;
    private final EcbExchangeRateProvider rates;

    public AdminPricingController(CarService carService, EcbExchangeRateProvider rates) {
        this.carService = carService;
        this.rates = rates;
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
