package com.premiumcars.autoimport;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.premiumcars.autoimport.AutoImportDtos.BrandUpdate;
import com.premiumcars.autoimport.AutoImportDtos.BrandView;
import com.premiumcars.autoimport.AutoImportDtos.CatalogEntry;
import com.premiumcars.autoimport.AutoImportDtos.Failure;
import com.premiumcars.autoimport.AutoImportDtos.FailureDelete;
import com.premiumcars.autoimport.AutoImportDtos.Failures;
import com.premiumcars.autoimport.AutoImportDtos.Overview;
import com.premiumcars.autoimport.AutoImportDtos.Preview;
import com.premiumcars.autoimport.AutoImportDtos.SourceUpdate;
import com.premiumcars.autoimport.AutoImportDtos.SourceView;
import com.premiumcars.autoimport.AutoImportSettingsRepository.SourceSettings;
import com.premiumcars.autoimport.AutoImportTypes.Brand;
import com.premiumcars.autoimport.AutoImportTypes.BrandCount;
import com.premiumcars.autoimport.AutoImportTypes.CatalogBrand;
import com.premiumcars.autoimport.AutoImportTypes.CatalogModel;
import com.premiumcars.autoimport.AutoImportTypes.FuelOption;
import com.premiumcars.autoimport.AutoImportTypes.SourceFilters;
import com.premiumcars.autoimport.SourceSearch.SearchException;
import com.premiumcars.catalog.domain.CarSource;
import com.premiumcars.common.BadRequestException;
import com.premiumcars.common.PageResponse;
import com.premiumcars.common.NotFoundException;

/** The admin panel's "Автоматичен импорт": settings, the sources' makes and models, preview, runs. */
@Service
public class AutoImportService {

    private static final Set<String> GRADES = Set.of("S", "A", "B", "C", "D");

    private final AutoImportSettingsRepository settings;
    private final AutoImportListingRepository listings;
    private final AutoImportRunRepository runs;
    private final AutoImportRunner runner;
    private final Map<CarSource, SourceSearch> searches = new EnumMap<>(CarSource.class);

    public AutoImportService(AutoImportSettingsRepository settings, AutoImportListingRepository listings,
                             AutoImportRunRepository runs, AutoImportRunner runner, List<SourceSearch> searches) {
        this.settings = settings;
        this.listings = listings;
        this.runs = runs;
        this.runner = runner;
        searches.forEach(s -> this.searches.put(s.source(), s));
    }

    public Overview overview() {
        Map<String, Map<String, Integer>> queues = listings.stateCounts();
        List<SourceView> sources = new ArrayList<>();
        for (SourceSettings s : settings.sources()) {
            List<BrandView> brands = settings.brands(s.source()).stream()
                    .map(b -> new BrandView(b.code(), b.name(), b.enabled(), b.yearFrom(), b.models(),
                            b.excludedFuels())).toList();
            sources.add(new SourceView(s.source(), s.enabled(), s.filters(), brands,
                    queues.getOrDefault(s.source().name(), Map.of())));
        }
        return new Overview(settings.enabled(), runner.running(), settings.maxMileageKm(), sources,
                runs.recent(30));
    }

    /** The failures tab: a page of failed listings (filtered) and the reasons of all of them. */
    public Failures failures(CarSource source, String text, int page, int size) {
        int p = Math.max(page, 0);
        int s = Math.min(Math.max(size, 1), 200);
        var rows = listings.failures(source, text, p, s);
        return new Failures(PageResponse.of(rows, p, s, listings.countFailures(source, text),
                l -> new Failure(l.source(), l.listingId(), l.url(), l.title(), l.price(), l.state(), l.attempts(),
                        l.lastError(), l.updatedAt())),
                listings.failureReasons(source));
    }

    public List<AutoImportRunRepository.Removed> removed(long runId) {
        return runs.removed(runId);
    }

    /** @return how many were deleted */
    public int deleteFailures(FailureDelete d) {
        if (d.all()) {
            return listings.deleteFailures(d.source(), d.text());
        }
        if (d.items() == null || d.items().isEmpty()) {
            return 0;
        }
        return listings.deleteFailures(d.items().stream()
                .map(i -> new AutoImportListingRepository.FailureKey(i.source(), i.listingId())).toList());
    }

    /** Applies to the queue at once (the worker checks it) and to the next run's new listings. */
    public Overview setMaxMileage(Integer km) {
        settings.setMaxMileageKm(km);
        return overview();
    }

    public Overview setEnabled(boolean enabled) {
        settings.setEnabled(enabled);
        return overview();
    }

    @Transactional
    public Overview save(CarSource source, SourceUpdate u) {
        settingsOf(source);
        SourceFilters filters = check(u.filters());
        settings.updateSource(source, u.enabled(), filters);
        settings.replaceBrands(source, brands(u));
        return overview();
    }

    /** The source's makes; loaded from the site the first time. */
    public List<CatalogEntry> catalog(CarSource source) {
        settingsOf(source);
        List<CatalogBrand> brands = settings.catalog(source);
        if (brands.isEmpty()) {
            brands = refreshCatalog(source);
        }
        return brands.stream().map(b -> new CatalogEntry(b.code(), b.name())).toList();
    }

    public List<CatalogBrand> refreshCatalog(CarSource source) {
        try {
            settings.saveCatalog(source, search(source).brands());
        } catch (SearchException e) {
            throw new BadRequestException("Could not read the makes from " + source + ": " + e.getMessage(), e);
        }
        return settings.catalog(source);
    }

    /** A make's models; loaded from the site when not known yet (or when asked to). */
    public List<CatalogModel> models(CarSource source, String brandCode, boolean refresh) {
        settingsOf(source);
        CatalogBrand known = settings.catalog(source).stream().filter(b -> b.code().equals(brandCode)).findFirst()
                .orElse(null);
        if (known != null && known.models() != null && !refresh) {
            return known.models();
        }
        try {
            List<CatalogModel> models = search(source).models(brandCode);
            settings.saveModels(source, brandCode, known == null ? brandCode : known.name(), models);
            return models;
        } catch (SearchException e) {
            throw new BadRequestException("Could not read the models from " + source + ": " + e.getMessage(), e);
        }
    }

    /** How many listings the (unsaved) settings match now, per enabled make. Asks the site: takes a few seconds. */
    public Preview preview(CarSource source, SourceUpdate u) {
        settingsOf(source);
        SourceSearch search = search(source);
        SourceFilters filters = check(u.filters());
        List<BrandCount> counts = new ArrayList<>();
        int total = 0;
        String error = null;
        for (Brand b : brands(u)) {
            if (!b.enabled()) {
                continue;
            }
            try {
                int n = search.count(b, filters);
                counts.add(new BrandCount(b.code(), b.name(), n, null));
                total += n;
            } catch (SearchException e) {
                counts.add(new BrandCount(b.code(), b.name(), null, e.getMessage()));
                error = e.getMessage();
            }
        }
        return new Preview(source, counts, total, error);
    }

    /** A run now: a dry run any time, a real one only while the main switch and the source are on. */
    public Overview run(CarSource source, boolean dryRun) {
        SourceSettings s = settingsOf(source);
        if (!dryRun && (!settings.enabled() || !s.enabled())) {
            throw new BadRequestException("Turn on the automatic import and " + source + " first");
        }
        runner.submit(source, dryRun);
        return overview();
    }

    private SourceSettings settingsOf(CarSource source) {
        return settings.source(source).orElseThrow(() -> new NotFoundException("No automatic import for " + source));
    }

    private SourceSearch search(CarSource source) {
        SourceSearch s = searches.get(source);
        if (s == null) {
            throw new NotFoundException("No automatic import for " + source);
        }
        return s;
    }

    private static List<String> fuels(List<String> names) {
        return names == null ? List.of() : names.stream().map(String::trim).filter(n -> !n.isEmpty()).distinct()
                .toList();
    }

    /**
     * The fuel types of a make, or of one of its models, with their numbers of listings under the source's saved
     * filters: what the "Двигател" column offers to leave out. Asks the site.
     */
    public List<FuelOption> fuels(CarSource source, String brandCode, String modelCode, int yearFrom) {
        SourceSettings s = settingsOf(source);
        try {
            return search(source).fuels(brandCode, modelCode, yearFrom, s.filters());
        } catch (SearchException e) {
            throw new BadRequestException("Could not read the fuel types from " + source + ": " + e.getMessage(), e);
        }
    }

    private static SourceFilters check(SourceFilters f) {
        for (String g : f.grades()) {
            if (!GRADES.contains(g)) {
                throw new BadRequestException("Unknown grade: " + g);
            }
        }
        return f;
    }

    private static List<Brand> brands(SourceUpdate u) {
        List<Brand> out = new ArrayList<>();
        Set<String> codes = new HashSet<>();
        for (BrandUpdate b : u.brands()) {
            if (!codes.add(b.code())) {
                throw new BadRequestException("Make " + b.name() + " is listed twice");
            }
            List<CatalogModel> models = b.models() == null ? List.of()
                    : b.models().stream().map(m -> new CatalogModel(m.code(), m.name(), fuels(m.excludedFuels())))
                            .toList();
            // the make's own exclusions count only while no model is chosen
            out.add(new Brand(b.code(), b.name().trim(), b.enabled(), b.yearFrom(), models,
                    models.isEmpty() ? fuels(b.excludedFuels()) : List.of()));
        }
        return out;
    }
}
