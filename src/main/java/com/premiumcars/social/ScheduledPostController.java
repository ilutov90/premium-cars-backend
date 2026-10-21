package com.premiumcars.social;

import java.security.Principal;
import java.time.Instant;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Scheduling posts for later; publishing right away stays in {@link AdminSocialController}. */
@RestController
@RequestMapping("/api/admin")
public class ScheduledPostController {

    /** @param publishAt when to publish (ISO-8601 instant, e.g. 2026-10-25T07:30:00Z) */
    public record ScheduleRequest(@NotNull SocialNetwork network, @NotBlank @Size(max = 7000) String text,
                                  @Size(min = 1, max = SocialPublisher.MAX_SELECTED_PHOTOS) List<@NotNull Long> imageIds,
                                  @NotNull Instant publishAt) {
    }

    private final ScheduledPostService service;

    public ScheduledPostController(ScheduledPostService service) {
        this.service = service;
    }

    @GetMapping("/cars/{id}/scheduled-posts")
    public List<ScheduledPost> forCar(@PathVariable long id) {
        return service.forCar(id);
    }

    @PostMapping("/cars/{id}/scheduled-posts")
    @ResponseStatus(HttpStatus.CREATED)
    public ScheduledPost schedule(@PathVariable long id, @Valid @RequestBody ScheduleRequest request,
                                  Principal principal) {
        return service.schedule(id, request.network(), request.text(), request.imageIds(), request.publishAt(),
                principal.getName());
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
