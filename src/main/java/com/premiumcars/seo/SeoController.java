package com.premiumcars.seo;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.premiumcars.seo.SeoDtos.MakeEntry;
import com.premiumcars.seo.SeoDtos.SeoPage;

/** Data of the SEO landing pages per make and per make + model of the public cars. */
@RestController
@RequestMapping("/api/public/seo")
public class SeoController {

    private final SeoService service;

    public SeoController(SeoService service) {
        this.service = service;
    }

    @GetMapping("/makes")
    public List<MakeEntry> makes() {
        return service.makes();
    }

    @GetMapping("/pages/{makeSlug}")
    public SeoPage makePage(@PathVariable String makeSlug) {
        return service.makePage(makeSlug);
    }

    @GetMapping("/pages/{makeSlug}/{modelSlug}")
    public SeoPage modelPage(@PathVariable String makeSlug, @PathVariable String modelSlug) {
        return service.modelPage(makeSlug, modelSlug);
    }
}
