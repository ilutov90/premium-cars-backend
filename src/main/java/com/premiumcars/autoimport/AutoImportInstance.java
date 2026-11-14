package com.premiumcars.autoimport;

import java.time.Duration;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * This server's part in the automatic import, from its own configuration (not the database, which both servers
 * share): whether this server runs it (the nightly run, "run now", the queue, the preview), and how gently it reads
 * the sites. Only one server may run it at a time; the other one leaves it alone (app.auto-import.enabled=false), e.g.
 * production while an admin's laptop does the import against the production database (premium-cars-infra
 * "local-worker").
 *
 * @param enabled          this server runs the automatic import
 * @param searchPause      at least this long between two requests to a site's search
 * @param guaziSearchPause the same for guazi's search, which blocks the server's address sooner: longer
 * @param queuePause       at least this long between two imported listings of a site's queue
 * @param refusedWaits     how long a run waits, one wait after another (one budget per run), before it reads again
 *                         a make the site refused (a bot check); empty = never waits
 */
@Component
public record AutoImportInstance(boolean enabled, Duration searchPause, Duration guaziSearchPause,
                                 Duration queuePause, List<Duration> refusedWaits) {

    public AutoImportInstance(@Value("${app.auto-import.enabled:true}") boolean enabled,
                              @Value("${app.auto-import.search-pause:PT2S}") Duration searchPause,
                              @Value("${app.auto-import.guazi-search-pause:PT3S}") Duration guaziSearchPause,
                              @Value("${app.auto-import.queue-pause:PT2S}") Duration queuePause,
                              @Value("${app.auto-import.refused-waits:PT30M,PT1H,PT2H}") List<Duration> refusedWaits) {
        this.enabled = enabled;
        this.searchPause = searchPause;
        this.guaziSearchPause = guaziSearchPause;
        this.queuePause = queuePause;
        this.refusedWaits = List.copyOf(refusedWaits);
    }
}
