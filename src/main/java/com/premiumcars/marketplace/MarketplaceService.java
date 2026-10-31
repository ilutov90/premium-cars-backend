package com.premiumcars.marketplace;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.premiumcars.carsbg.CarsBgRepository;
import com.premiumcars.catalog.repo.CarRepository;
import com.premiumcars.catalog.repo.CarRepository.MakeModelCount;
import com.premiumcars.common.BadRequestException;
import com.premiumcars.marketplace.ModelMatch.Status;
import com.premiumcars.marketplace.SiteCatalog.SiteBrand;
import com.premiumcars.marketplace.SiteCatalog.SiteModel;
import com.premiumcars.mobilebg.MobileBgRepository;

/**
 * Our makes + models against the listing sites' lists (the page "Съпоставяне на модели"): how each one is matched,
 * the admins' choices, and the match the mobile.bg and cars.bg forms use.
 */
@Service
public class MarketplaceService {

    private static final Logger log = LoggerFactory.getLogger(MarketplaceService.class);

    /** How one of our models lands on one site. {@code setBy}/{@code setAt}: the admin's choice, if any. */
    public record SiteMatch(Status status, String brand, String model, List<String> suggestions, String setBy,
                            Instant setAt) {
    }

    /** @param cars all our cars of it but those taken off the source; {@code publicCars}: on the site */
    public record Row(String make, String model, int cars, int publicCars, Map<Site, SiteMatch> sites) {
    }

    /** @param brands the site's models of the makes the rows matched, to choose from */
    public record SiteInfo(Instant capturedAt, int brandCount, Map<String, List<String>> brands) {
    }

    public record Overview(List<Row> rows, Map<Site, SiteInfo> sites) {
    }

    private final CarRepository cars;
    private final ModelMappingRepository mappings;
    private final MobileBgRepository mobileBg;
    private final CarsBgRepository carsBg;

    public MarketplaceService(CarRepository cars, ModelMappingRepository mappings, MobileBgRepository mobileBg,
                              CarsBgRepository carsBg) {
        this.cars = cars;
        this.mappings = mappings;
        this.mobileBg = mobileBg;
        this.carsBg = carsBg;
    }

    public SiteCatalog catalog(Site site) {
        return switch (site) {
            case MOBILE_BG -> mobileBg.catalog().toSiteCatalog();
            case CARS_BG -> carsBg.catalog().toSiteCatalog();
        };
    }

    /** The match for one car (its trim breaks ties between suggestions). */
    public ModelMatch match(Site site, SiteCatalog catalog, String make, String model, String trim) {
        return ModelMatcher.match(catalog, make, model, trim, chosen(site, make, model));
    }

    Optional<ModelMapping> chosen(Site site, String make, String model) {
        String mk = ModelNames.key(make);
        String md = ModelNames.modelKey(model);
        return mappings.findAll().stream()
                .filter(m -> m.site() == site && ModelNames.key(m.make()).equals(mk)
                        && ModelNames.modelKey(m.model()).equals(md))
                .findFirst();
    }

    /** Every make + model of our cars, the most cars first, with its match on each site. */
    public Overview overview() {
        Map<String, Agg> byKey = new LinkedHashMap<>();
        for (MakeModelCount c : cars.countMakeModels()) {
            String k = ModelNames.key(c.make()) + "|" + ModelNames.modelKey(c.model());
            byKey.computeIfAbsent(k, x -> new Agg()).add(c);
        }
        Map<String, ModelMapping> chosen = new HashMap<>();
        mappings.findAll().forEach(m -> chosen.put(mapKey(m.site(), m.make(), m.model()), m));

        Map<Site, SiteCatalog> catalogs = new EnumMap<>(Site.class);
        Map<Site, Map<String, List<String>>> brands = new EnumMap<>(Site.class);
        for (Site s : Site.values()) {
            catalogs.put(s, catalog(s));
            brands.put(s, new TreeMap<>(String.CASE_INSENSITIVE_ORDER));
        }

        List<Row> rows = new ArrayList<>();
        for (Agg a : byKey.values()) {
            String make = a.name(a.makes);
            String model = a.name(a.models);
            Map<Site, SiteMatch> sites = new EnumMap<>(Site.class);
            for (Site s : Site.values()) {
                ModelMapping m = chosen.get(mapKey(s, make, model));
                ModelMatch r = ModelMatcher.match(catalogs.get(s), make, model, Optional.ofNullable(m));
                if (r.brand() != null) {
                    brands.get(s).putIfAbsent(r.brand().name(),
                            r.brand().models().stream().map(SiteModel::name).toList());
                }
                sites.put(s, new SiteMatch(r.status(), r.brand() == null ? null : r.brand().name(),
                        r.model() == null ? null : r.model().name(),
                        r.suggestions().stream().map(SiteModel::name).toList(),
                        m == null ? null : m.setBy(), m == null ? null : m.setAt()));
            }
            rows.add(new Row(make, model, a.cars, a.publicCars, sites));
        }
        rows.sort(Comparator.comparingInt(Row::cars).reversed()
                .thenComparing(Row::make, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Row::model, String.CASE_INSENSITIVE_ORDER));

        Map<Site, SiteInfo> info = new EnumMap<>(Site.class);
        for (Site s : Site.values()) {
            info.put(s, new SiteInfo(catalogs.get(s).capturedAt(), catalogs.get(s).brands().size(), brands.get(s)));
        }
        return new Overview(rows, info);
    }

    /**
     * An admin's choice: the site's model of the make our make is matched to (never another make), or none.
     */
    public void choose(Site site, String make, String model, String targetModel, boolean unavailable, String by) {
        if (make == null || make.isBlank() || model == null || model.isBlank()) {
            throw new BadRequestException("The make and the model are needed");
        }
        String brand = null;
        String target = null;
        if (!unavailable) {
            SiteCatalog catalog = catalog(site);
            if (catalog.isEmpty()) {
                throw new BadRequestException("The site's lists were never read");
            }
            SiteBrand b = ModelMatcher.brand(catalog, make)
                    .orElseThrow(() -> new BadRequestException("The site has no make " + make));
            SiteModel m = b.models().stream()
                    .filter(x -> targetModel != null && ModelNames.key(x.name()).equals(ModelNames.key(targetModel)))
                    .findFirst()
                    .orElseThrow(() -> new BadRequestException(b.name() + " has no model " + targetModel));
            brand = b.name();
            target = m.name();
        }
        mappings.save(new ModelMapping(site, make.trim(), model.trim(), brand, target, unavailable, by, null));
        log.info("Admin '{}' mapped {} {} on {} to {}", by, make, model, site,
                unavailable ? "nothing" : brand + " " + target);
    }

    public void reset(Site site, String make, String model, String by) {
        if (mappings.delete(site, make, model)) {
            log.info("Admin '{}' reset the mapping of {} {} on {}", by, make, model, site);
        }
    }

    /**
     * After a site's lists were read again: a model said to be missing that the site has now (the same name) is
     * matched by itself again. Chosen models gone from the lists are matched again anyway ({@link ModelMatcher}).
     *
     * @return how many "not on the site" choices were dropped
     */
    public int catalogChanged(Site site) {
        SiteCatalog catalog = catalog(site);
        int dropped = 0;
        for (ModelMapping m : mappings.findAll()) {
            if (m.site() == site && m.unavailable()
                    && ModelMatcher.match(catalog, m.make(), m.model(), Optional.empty()).status() == Status.AUTO
                    && mappings.delete(site, m.make(), m.model())) {
                dropped++;
            }
        }
        if (dropped > 0) {
            log.info("{}: {} models marked missing are on the site now", site, dropped);
        }
        return dropped;
    }

    private static String mapKey(Site site, String make, String model) {
        return site + "|" + ModelNames.key(make) + "|" + ModelNames.modelKey(model);
    }

    /** The cars of one key; the name shown is the one most cars have. */
    private static final class Agg {
        final Map<String, Integer> makes = new HashMap<>();
        final Map<String, Integer> models = new HashMap<>();
        int cars;
        int publicCars;

        void add(MakeModelCount c) {
            makes.merge(c.make().trim(), c.cars(), Integer::sum);
            models.merge(c.model().trim(), c.cars(), Integer::sum);
            cars += c.cars();
            publicCars += c.publicCars();
        }

        String name(Map<String, Integer> names) {
            return names.entrySet().stream()
                    .max(Map.Entry.<String, Integer>comparingByValue().thenComparing(Map.Entry::getKey,
                            Comparator.reverseOrder()))
                    .map(Map.Entry::getKey).orElseThrow();
        }
    }
}
