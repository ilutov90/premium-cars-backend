package com.premiumcars.catalog.web;

import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.premiumcars.catalog.service.CarService;
import com.premiumcars.catalog.service.SitemapService;
import com.premiumcars.catalog.web.dto.SitemapDtos.SitemapPage;
import com.premiumcars.catalog.web.dto.CarDtos.CarDetail;
import com.premiumcars.catalog.web.dto.CarDtos.CarSummary;
import com.premiumcars.catalog.web.dto.CarDtos.FilterOptions;
import com.premiumcars.catalog.web.dto.CarFilter;
import com.premiumcars.common.PageResponse;

@RestController
@RequestMapping("/api/public")
public class PublicCarController {

    private final CarService carService;
    private final SitemapService sitemap;

    public PublicCarController(CarService carService, SitemapService sitemap) {
        this.carService = carService;
        this.sitemap = sitemap;
    }

    /** Example: /api/public/cars?make=BMW&fuelType=ELECTRIC&priceTo=40000&sort=price_asc&page=0&size=12 */
    @GetMapping("/cars")
    public PageResponse<CarSummary> list(@ModelAttribute CarFilter filter, HttpServletRequest request,
                                         @RequestParam(required = false) String sort,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "12") int size) {
        return carService.searchPublic(withRepeated(filter, request), sort, page, size);
    }

    /**
     * A repeated {@code make} / {@code model} parameter ("?make=Kgm&make=KGM (Ssangyong)") selects the cars of any of
     * these names (exact, case-insensitive); one value behaves as before.
     */
    private static CarFilter withRepeated(CarFilter filter, HttpServletRequest request) {
        List<String> makes = repeated(request, "make");
        List<String> models = repeated(request, "model");
        return makes == null && models == null ? filter : filter.withNames(makes, models);
    }

    private static List<String> repeated(HttpServletRequest request, String name) {
        String[] values = request.getParameterValues(name);
        return values == null || values.length < 2 ? null : List.of(values);
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

    /** The public cars for the site's sitemap, by id: {@code /api/public/sitemap/cars?page=0&size=5000}. */
    @GetMapping("/sitemap/cars")
    public SitemapPage sitemapCars(@RequestParam(defaultValue = "0") int page,
                                   @RequestParam(defaultValue = "5000") int size) {
        return sitemap.cars(page, size);
    }

    @GetMapping("/filters")
    public FilterOptions filters() {
        return carService.filterOptions();
    }
}
