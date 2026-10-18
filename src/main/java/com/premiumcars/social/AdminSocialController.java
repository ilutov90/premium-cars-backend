package com.premiumcars.social;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Publishing cars to Facebook / Instagram / Viber from the admin panel. */
@RestController
@RequestMapping("/api/admin")
public class AdminSocialController {

    /**
     * @param text       the post text, as shown (and possibly edited) in the admin panel
     * @param photoCount Viber only: how many photos to post (null = the configured default)
     */
    public record PublishRequest(@NotNull SocialNetwork network, @NotBlank @Size(max = 7000) String text,
                                 @Min(1) @Max(SocialPublisher.VIBER_PHOTOS_LIMIT) Integer photoCount) {
    }

    /** @param siteUrl the site's public https:// address, e.g. a temporary tunnel while testing locally */
    public record ViberConnectRequest(@NotBlank @Size(max = 300) String siteUrl) {
    }

    public record ViberConnectResponse(String callbackUrl) {
    }

    private final SocialPublisher publisher;

    public AdminSocialController(SocialPublisher publisher) {
        this.publisher = publisher;
    }

    /** Which networks are configured (the admin panel hides the others' publish buttons). */
    @GetMapping("/social/status")
    public SocialPublisher.Status status() {
        return publisher.status();
    }

    /** Registers the Viber callback once; Viber accepts channel posts only after that. */
    @PostMapping("/social/viber/connect")
    public ViberConnectResponse connectViber(@Valid @RequestBody ViberConnectRequest request) {
        return new ViberConnectResponse(publisher.connectViber(request.siteUrl()));
    }

    @GetMapping("/cars/{id}/social-posts")
    public List<SocialPost> posts(@PathVariable long id) {
        return publisher.posts(id);
    }

    @PostMapping("/cars/{id}/social-posts")
    @ResponseStatus(HttpStatus.CREATED)
    public SocialPost publish(@PathVariable long id, @Valid @RequestBody PublishRequest request) {
        return publisher.publish(id, request.network(), request.text(), request.photoCount());
    }
}
