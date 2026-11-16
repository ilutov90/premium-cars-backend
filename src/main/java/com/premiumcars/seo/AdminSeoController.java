package com.premiumcars.seo;

import java.security.Principal;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.premiumcars.seo.SeoDtos.AdminSeoPage;
import com.premiumcars.seo.SeoDtos.SavePageRequest;

/** The admins' texts for the SEO landing pages. */
@RestController
@RequestMapping("/api/admin/seo/pages")
public class AdminSeoController {

    private final SeoService service;

    public AdminSeoController(SeoService service) {
        this.service = service;
    }

    @GetMapping
    public List<AdminSeoPage> pages() {
        return service.adminPages();
    }

    @PutMapping
    public AdminSeoPage save(@RequestBody SavePageRequest request, Principal principal) {
        return service.save(request, principal.getName());
    }
}
