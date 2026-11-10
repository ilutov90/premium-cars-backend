package com.premiumcars.catalog.web;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.premiumcars.catalog.service.CarService;
import com.premiumcars.catalog.web.dto.CarDtos.CarDetail;
import com.premiumcars.catalog.web.dto.CarDtos.CarSummary;
import com.premiumcars.catalog.web.dto.CarDtos.FilterOptions;
import com.premiumcars.catalog.web.dto.CarFilter;
import com.premiumcars.common.PageResponse;

@RestController
@RequestMapping("/api/public")
public class PublicCarController {

    private final CarService carService;

    public PublicCarController(CarService carService) {
        this.carService = carService;
    }

    /** Example: /api/public/cars?make=BMW&fuelType=ELECTRIC&priceTo=40000&sort=price_asc&page=0&size=12 */
    @GetMapping("/cars")
    public PageResponse<CarSummary> list(@ModelAttribute CarFilter filter,
                                         @RequestParam(required = false) String sort,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "12") int size) {
        return carService.searchPublic(filter, sort, page, size);
    }

    @GetMapping("/cars/{slug}")
    public CarDetail get(@PathVariable String slug) {
        return carService.getPublic(slug);
    }

    /**
     * Whether a car with this slug is on the site. The frontend asks before rendering a car's page, so a deleted
     * car's address answers a real 404 (search engines drop it). Always 200: a 404 here would be a server that does
     * not know this endpoint yet, which the frontend must not take for a missing car.
     */
    @GetMapping("/cars/{slug}/exists")
    public Map<String, Boolean> exists(@PathVariable String slug) {
        return Map.of("exists", carService.isPublic(slug));
    }

    @GetMapping("/filters")
    public FilterOptions filters() {
        return carService.filterOptions();
    }
}
