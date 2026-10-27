package com.premiumcars.catalog.web;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.premiumcars.catalog.domain.CarSort;
import com.premiumcars.catalog.domain.CarSource;
import com.premiumcars.catalog.domain.CarStatus;
import com.premiumcars.catalog.service.CarService;
import com.premiumcars.catalog.web.dto.CarDtos.AdminCar;
import com.premiumcars.catalog.web.dto.CarDtos.AdminCarCounts;
import com.premiumcars.catalog.web.dto.CarDtos.AdminCarSummary;
import com.premiumcars.catalog.web.dto.CarDtos.CarUpdateRequest;
import com.premiumcars.catalog.web.dto.CarDtos.ImportRequest;
import com.premiumcars.catalog.web.dto.CarDtos.ImportResult;
import com.premiumcars.catalog.web.dto.CarDtos.StatusChangeRequest;
import com.premiumcars.common.PageResponse;
import com.premiumcars.importing.ImportService;

import jakarta.validation.Valid;

/** Admin API, protected with HTTP Basic (see SecurityConfig). */
@RestController
@RequestMapping("/api/admin")
public class AdminCarController {

    private final CarService carService;
    private final ImportService importService;

    public AdminCarController(CarService carService, ImportService importService) {
        this.carService = carService;
        this.importService = importService;
    }

    @PostMapping("/imports")
    public ImportResult importCar(@Valid @RequestBody ImportRequest request) {
        return importService.importFromUrl(request.url(), request.price(), request.currency(), request.html());
    }

    @GetMapping("/cars")
    public PageResponse<AdminCarSummary> list(@RequestParam(required = false) CarStatus status,
                                         @RequestParam(required = false) String q,
                                         @RequestParam(required = false) CarSource source,
                                         @RequestParam(required = false) String sort,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "24") int size) {
        return carService.searchAdmin(status, q, source, CarSort.fromAdminParam(sort), page, size);
    }

    /** How many cars match the search and source, in all and per status. */
    @GetMapping("/cars/counts")
    public AdminCarCounts counts(@RequestParam(required = false) String q,
                                 @RequestParam(required = false) CarSource source) {
        return carService.adminCounts(q, source);
    }

    @GetMapping("/cars/{id}")
    public AdminCar get(@PathVariable long id) {
        return carService.getAdmin(id);
    }

    @PutMapping("/cars/{id}")
    public AdminCar update(@PathVariable long id, @Valid @RequestBody CarUpdateRequest request) {
        return carService.update(id, request);
    }

    @PostMapping("/cars/{id}/status")
    public AdminCar changeStatus(@PathVariable long id, @Valid @RequestBody StatusChangeRequest request) {
        return carService.changeStatus(id, request.status());
    }

    @DeleteMapping("/cars/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        carService.delete(id);
    }

    @DeleteMapping("/cars/{id}/images/{imageId}")
    public AdminCar deleteImage(@PathVariable long id, @PathVariable long imageId) {
        return carService.deleteImage(id, imageId);
    }

    @PostMapping("/cars/{id}/images/{imageId}/cover")
    public AdminCar makeCover(@PathVariable long id, @PathVariable long imageId) {
        return carService.makeCover(id, imageId);
    }

    /** One car's price with today's exchange rate and its formula as it is now (prices are kept otherwise). */
    @PostMapping("/cars/{id}/recalculate-price")
    public AdminCar recalculatePrice(@PathVariable long id) {
        return carService.recalculatePrice(id);
    }

    /** Every car's price with today's rates and formulas. Changes prices that may be posted already. */
    @PostMapping("/cars/recalculate-prices")
    public Map<String, Integer> recalculatePrices() {
        return Map.of("updated", carService.recalculateAllPrices());
    }
}
