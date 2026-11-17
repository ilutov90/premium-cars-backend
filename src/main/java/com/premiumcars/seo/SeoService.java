package com.premiumcars.seo;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.premiumcars.catalog.domain.CarSource;
import com.premiumcars.catalog.domain.FuelType;
import com.premiumcars.catalog.domain.Origin;
import com.premiumcars.catalog.service.CarService;
import com.premiumcars.common.BadRequestException;
import com.premiumcars.common.NotFoundException;
import com.premiumcars.seo.SeoDtos.AdminSeoPage;
import com.premiumcars.seo.SeoDtos.MakeEntry;
import com.premiumcars.seo.SeoDtos.ModelEntry;
import com.premiumcars.seo.SeoDtos.SavePageRequest;
import com.premiumcars.seo.SeoDtos.SeoPage;

/**
 * Landing pages per make and per make + model of the public cars. Names that give the same {@link SeoSlugs slug}
 * are one page: counts are summed and the shown name is the spelling with the most cars.
 */
@Service
public class SeoService {

    static final int MAX_TITLE = 160;
    static final int MAX_DESCRIPTION = 320;
    static final int MAX_TEXT = 5000;
    private static final Pattern SLUG = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");

    private final SeoRepository repository;

    /** The totals are one scan of the public cars: kept this long, since every page view and the proxy's 404 check ask. */
    private final java.time.Duration cacheFor;
    private volatile Map<String, MakeAcc> cached;
    private volatile long cachedAtNanos;

    public SeoService(SeoRepository repository,
                      @org.springframework.beans.factory.annotation.Value("${app.seo.cache:PT60S}") java.time.Duration cacheFor) {
        this.repository = repository;
        this.cacheFor = cacheFor;
    }

    /** The cars of one slug: totals and the stored spellings with their car counts. */
    private static class Acc {
        int count;
        BigDecimal minPrice;
        BigDecimal maxPrice;
        Integer yearMin;
        Integer yearMax;
        final Set<Origin> origins = EnumSet.noneOf(Origin.class);
        final Map<FuelType, Integer> fuels = new HashMap<>();
        final Map<String, Integer> names = new HashMap<>();

        void add(String name, SeoRepository.Group g) {
            count += g.cars();
            minPrice = g.minPrice() == null ? minPrice : minPrice == null ? g.minPrice() : minPrice.min(g.minPrice());
            maxPrice = g.maxPrice() == null ? maxPrice : maxPrice == null ? g.maxPrice() : maxPrice.max(g.maxPrice());
            yearMin = g.yearMin() == null ? yearMin : yearMin == null ? g.yearMin() : Math.min(yearMin, g.yearMin());
            yearMax = g.yearMax() == null ? yearMax : yearMax == null ? g.yearMax() : Math.max(yearMax, g.yearMax());
            Origin origin = origin(g.source());
            if (origin != null) {
                origins.add(origin);
            }
            FuelType fuel = fuel(g.fuel());
            if (fuel != null) {
                fuels.merge(fuel, g.cars(), Integer::sum);
            }
            names.merge(name, g.cars(), Integer::sum);
        }

        List<String> allNames() {
            return names.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                    .map(Map.Entry::getKey).toList();
        }

        String displayName() {
            return allNames().getFirst();
        }

        List<Origin> originList() {
            return List.copyOf(origins);
        }

        List<FuelType> fuelList() {
            return fuels.entrySet().stream()
                    .sorted(Map.Entry.<FuelType, Integer>comparingByValue().reversed()
                            .thenComparing(Map.Entry.comparingByKey()))
                    .map(Map.Entry::getKey).toList();
        }
    }

    private static final class MakeAcc extends Acc {
        final Map<String, Acc> models = new LinkedHashMap<>();
        final Map<String, String> modelDisplay = new HashMap<>();
    }

    private static Origin origin(String source) {
        try {
            return Origin.of(CarSource.valueOf(source));
        } catch (IllegalArgumentException | NullPointerException e) {
            return null;
        }
    }

    private static FuelType fuel(String value) {
        try {
            return value == null ? null : FuelType.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static final Comparator<Acc> BY_COUNT = Comparator.comparingInt((Acc a) -> -a.count);

    /** make slug -> its totals, in the order of the lists (most cars first, then name). */
    private Map<String, MakeAcc> aggregate() {
        Map<String, MakeAcc> kept = cached;
        if (kept != null && !cacheFor.isZero() && System.nanoTime() - cachedAtNanos < cacheFor.toNanos()) {
            return kept;
        }
        Map<String, MakeAcc> fresh = read();
        cached = fresh;
        cachedAtNanos = System.nanoTime();
        return fresh;
    }

    private Map<String, MakeAcc> read() {
        Map<String, MakeAcc> makes = new HashMap<>();
        for (SeoRepository.Group g : repository.groups(CarService.PUBLIC_STATUSES)) {
            String makeSlug = SeoSlugs.of(g.make());
            if (makeSlug.isEmpty()) {
                continue;
            }
            MakeAcc make = makes.computeIfAbsent(makeSlug, k -> new MakeAcc());
            make.add(g.make(), g);
            String modelSlug = SeoSlugs.of(g.model());
            if (!modelSlug.isEmpty()) {
                make.models.computeIfAbsent(modelSlug, k -> new Acc()).add(g.model(), g);
            }
        }
        Map<String, MakeAcc> sorted = new LinkedHashMap<>();
        makes.entrySet().stream()
                .sorted(Map.Entry.<String, MakeAcc>comparingByValue(BY_COUNT)
                        .thenComparing(e -> e.getValue().displayName()))
                .forEach(e -> sorted.put(e.getKey(), e.getValue()));
        return sorted;
    }

    private static List<Map.Entry<String, Acc>> sortedModels(MakeAcc make) {
        return make.models.entrySet().stream()
                .sorted(Map.Entry.<String, Acc>comparingByValue(BY_COUNT)
                        .thenComparing(e -> e.getValue().displayName()))
                .toList();
    }

    private static ModelEntry modelEntry(String slug, Acc a) {
        return new ModelEntry(a.displayName(), slug, a.count, a.minPrice, a.maxPrice, a.yearMin, a.yearMax);
    }

    private static List<ModelEntry> modelEntries(MakeAcc make) {
        return sortedModels(make).stream().map(e -> modelEntry(e.getKey(), e.getValue())).toList();
    }

    @Transactional(readOnly = true)
    public List<MakeEntry> makes() {
        List<MakeEntry> out = new ArrayList<>();
        aggregate().forEach((slug, m) -> out.add(new MakeEntry(m.displayName(), slug, m.count, m.minPrice, m.maxPrice,
                m.yearMin, m.yearMax, m.originList(), modelEntries(m))));
        return out;
    }

    @Transactional(readOnly = true)
    public SeoPage makePage(String makeSlug) {
        return page(makeSlug, null);
    }

    @Transactional(readOnly = true)
    public SeoPage modelPage(String makeSlug, String modelSlug) {
        return page(makeSlug, modelSlug);
    }

    private SeoPage page(String makeSlug, String modelSlug) {
        MakeAcc make = aggregate().get(makeSlug);
        if (make == null) {
            throw new NotFoundException("No cars of this make: " + makeSlug);
        }
        var override = repository.find(makeSlug, modelSlug == null ? "" : modelSlug);
        String title = override.map(SeoRepository.Override::title).orElse(null);
        String description = override.map(SeoRepository.Override::description).orElse(null);
        String text = override.map(SeoRepository.Override::body).orElse(null);
        if (modelSlug == null) {
            return new SeoPage(make.displayName(), makeSlug, null, null, make.allNames(), List.of(), make.count,
                    make.minPrice, make.maxPrice, make.yearMin, make.yearMax, make.originList(), make.fuelList(),
                    modelEntries(make), title, description, text);
        }
        Acc model = make.models.get(modelSlug);
        if (model == null) {
            throw new NotFoundException("No cars of this model: " + makeSlug + "/" + modelSlug);
        }
        return new SeoPage(make.displayName(), makeSlug, model.displayName(), modelSlug, make.allNames(),
                model.allNames(), model.count, model.minPrice, model.maxPrice, model.yearMin, model.yearMax,
                model.originList(), model.fuelList(), List.of(), title, description, text);
    }

    // ---------------------------------------------------------------- admin

    @Transactional(readOnly = true)
    public List<AdminSeoPage> adminPages() {
        Map<String, SeoRepository.Override> overrides = new LinkedHashMap<>();
        repository.findAll().forEach(o -> overrides.put(o.makeSlug() + "/" + o.modelSlug(), o));
        Map<String, MakeAcc> makes = aggregate();
        List<AdminSeoPage> out = new ArrayList<>();
        List<AdminSeoPage> orphans = new ArrayList<>();
        makes.forEach((makeSlug, m) -> {
            out.add(row(m.displayName(), makeSlug, null, "", m.count, overrides.remove(makeSlug + "/")));
            for (var e : sortedModels(m)) {
                out.add(row(m.displayName(), makeSlug, e.getValue().displayName(), e.getKey(), e.getValue().count,
                        overrides.remove(makeSlug + "/" + e.getKey())));
            }
        });
        for (SeoRepository.Override o : overrides.values()) {
            MakeAcc m = makes.get(o.makeSlug());
            String make = m == null ? o.makeSlug() : m.displayName();
            orphans.add(row(make, o.makeSlug(), o.modelSlug().isEmpty() ? null : o.modelSlug(), o.modelSlug(), 0, o));
        }
        out.addAll(orphans);
        return out;
    }

    private static AdminSeoPage row(String make, String makeSlug, String model, String modelSlug, int count,
                                    SeoRepository.Override o) {
        return new AdminSeoPage(make, makeSlug, model, modelSlug.isEmpty() ? null : modelSlug, count,
                o == null ? null : o.title(), o == null ? null : o.description(), o == null ? null : o.body(),
                o == null ? null : o.updatedAt());
    }

    /** Saves the override (all three texts blank deletes it) and returns the page's row as the list shows it. */
    @Transactional
    public AdminSeoPage save(SavePageRequest r, String by) {
        String makeSlug = slug(r.makeSlug(), 120, "makeSlug");
        String modelSlug = r.modelSlug() == null || r.modelSlug().isBlank() ? "" : slug(r.modelSlug(), 160, "modelSlug");
        String title = text(r.title(), MAX_TITLE, "title");
        String description = text(r.description(), MAX_DESCRIPTION, "description");
        String body = text(r.text(), MAX_TEXT, "text");
        if (title == null && description == null && body == null) {
            repository.delete(makeSlug, modelSlug);
        } else {
            repository.save(makeSlug, modelSlug, title, description, body, by);
        }
        String key = modelSlug.isEmpty() ? null : modelSlug;
        return adminPages().stream()
                .filter(p -> p.makeSlug().equals(makeSlug) && java.util.Objects.equals(p.modelSlug(), key))
                .findFirst()
                .orElseGet(() -> row(makeSlug, makeSlug, key, modelSlug, 0, null));
    }

    private static String slug(String value, int max, String field) {
        String v = value == null ? "" : value.trim();
        if (v.isEmpty() || v.length() > max || !SLUG.matcher(v).matches()) {
            throw new BadRequestException(field + " must be a slug (a-z, 0-9 and single dashes), at most " + max
                    + " characters");
        }
        return v;
    }

    private static String text(String value, int max, String field) {
        String v = value == null ? "" : value.trim();
        if (v.length() > max) {
            throw new BadRequestException(field + " is longer than " + max + " characters");
        }
        return v.isEmpty() ? null : v;
    }
}
