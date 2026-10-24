package com.premiumcars.social;

import java.security.Principal;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Following and cancelling the posts of a car and of all cars; they are created by publishing (now or later) in
 * {@link AdminSocialController}.
 */
@RestController
@RequestMapping("/api/admin")
public class ScheduledPostController {

    private final ScheduledPostService service;

    public ScheduledPostController(ScheduledPostService service) {
        this.service = service;
    }

    @GetMapping("/cars/{id}/scheduled-posts")
    public List<ScheduledPost> forCar(@PathVariable long id) {
        return service.forCar(id);
    }

    /** Waiting posts of all cars and those of the last 7 days. */
    @GetMapping("/scheduled-posts")
    public List<ScheduledPost> overview() {
        return service.overview();
    }

    @DeleteMapping("/scheduled-posts/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@PathVariable long id, Principal principal) {
        service.cancel(id, principal.getName());
    }
}
