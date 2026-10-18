package com.premiumcars.social;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Publishing cars to Facebook / Instagram / Viber from the admin panel. */
@RestController
@RequestMapping("/api/admin")
public class AdminSocialController {

    /**
     * @param text     the post text, as confirmed (and possibly edited) in the admin panel
     * @param imageIds the car's photos to post, in this order (1 to 10); null = the first ones of the gallery
     */
    public record PublishRequest(@NotNull SocialNetwork network, @NotBlank @Size(max = 7000) String text,
                                 @Size(min = 1, max = SocialPublisher.MAX_SELECTED_PHOTOS) List<@NotNull Long> imageIds) {
    }

    public record TemplateRequest(@NotBlank @Size(max = 7000) String body) {
    }

    /** @param siteUrl the site's public https:// address, e.g. a temporary tunnel while testing locally */
    public record ViberConnectRequest(@NotBlank @Size(max = 300) String siteUrl) {
    }

    public record ViberConnectResponse(String callbackUrl) {
    }

    private final SocialPublisher publisher;
    private final PostTemplateRepository templates;

    public AdminSocialController(SocialPublisher publisher, PostTemplateRepository templates) {
        this.publisher = publisher;
        this.templates = templates;
    }

    /** The saved templates; a network without one uses the admin panel's default. */
    @GetMapping("/post-templates")
    public List<PostTemplate> templates() {
        return templates.findAll();
    }

    @PutMapping("/post-templates/{network}")
    public PostTemplate saveTemplate(@PathVariable SocialNetwork network, @Valid @RequestBody TemplateRequest request) {
        return templates.save(network, request.body());
    }

    @DeleteMapping("/post-templates/{network}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetTemplate(@PathVariable SocialNetwork network) {
        templates.delete(network);
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
        return publisher.publish(id, request.network(), request.text(), request.imageIds());
    }
}
