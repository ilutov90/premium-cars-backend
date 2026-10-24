package com.premiumcars.social;

import java.security.Principal;
import java.time.Instant;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.premiumcars.common.BadRequestException;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Publishing cars to Facebook / Instagram / Viber from the admin panel. */
@RestController
@RequestMapping("/api/admin")
public class AdminSocialController {

    /** @param text the post text of the network, as confirmed (and possibly edited) in the admin panel */
    public record PostText(@NotNull SocialNetwork network, @NotBlank @Size(max = 7000) String text) {
    }

    /**
     * One publication to one or more networks with the same photos.
     *
     * @param posts     one text per network (Facebook and Instagram usually get the same one)
     * @param imageIds  the car's photos to post, in this order (1 to 10); null = the first ones of the gallery
     * @param publishAt when to publish (ISO-8601 instant, e.g. 2026-10-25T07:30:00Z); null = now
     */
    public record PublishRequest(@NotEmpty @Size(max = 3) List<@Valid @NotNull PostText> posts,
                                 @Size(min = 1, max = SocialPublisher.MAX_SELECTED_PHOTOS) List<@NotNull Long> imageIds,
                                 Instant publishAt) {
    }

    public record TemplateRequest(@NotBlank @Size(max = 7000) String body) {
    }

    /** @param siteUrl the site's public https:// address, e.g. a temporary tunnel while testing locally */
    public record ViberConnectRequest(@NotBlank @Size(max = 300) String siteUrl) {
    }

    public record ViberConnectResponse(String callbackUrl) {
    }

    private final SocialPublisher publisher;
    private final ScheduledPostService background;
    private final PostTemplateRepository templates;
    private final PostSettingsRepository settings;

    public AdminSocialController(SocialPublisher publisher, ScheduledPostService background,
                                 PostTemplateRepository templates, PostSettingsRepository settings) {
        this.publisher = publisher;
        this.background = background;
        this.templates = templates;
        this.settings = settings;
    }

    /** Phone, Viber channel link and office for the templates; null fields = the admin panel's defaults. */
    @GetMapping("/post-settings")
    public PostSettings settings() {
        return settings.find();
    }

    @PutMapping("/post-settings")
    public PostSettings saveSettings(@Valid @RequestBody PostSettings request) {
        return settings.save(request);
    }

    /**
     * The saved templates; one without a row uses the admin panel's default. Facebook and Instagram share one
     * template, stored as FACEBOOK; Viber has its own.
     */
    @GetMapping("/post-templates")
    public List<PostTemplate> templates() {
        return templates.findAll();
    }

    @PutMapping("/post-templates/{network}")
    public PostTemplate saveTemplate(@PathVariable SocialNetwork network, @Valid @RequestBody TemplateRequest request) {
        return templates.save(templateOf(network), request.body());
    }

    @DeleteMapping("/post-templates/{network}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetTemplate(@PathVariable SocialNetwork network) {
        templates.delete(templateOf(network));
    }

    private static SocialNetwork templateOf(SocialNetwork network) {
        if (network == SocialNetwork.INSTAGRAM) {
            throw new BadRequestException("Facebook and Instagram share one template: use FACEBOOK");
        }
        return network;
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

    /**
     * Publishes the car to the chosen networks at the same time, in the background (Facebook and Instagram take up
     * to a minute), or schedules it for {@code publishAt}. Checks every post first, answers 202 with one recorded
     * post per network, and the admin panel follows them (GET /api/admin/cars/{id}/scheduled-posts) until each is
     * PUBLISHED (then it is in social-posts too) or FAILED with the reason.
     */
    @PostMapping("/cars/{id}/publications")
    public ResponseEntity<List<ScheduledPost>> publish(@PathVariable long id, @Valid @RequestBody PublishRequest request,
                                                       Principal principal) {
        List<ScheduledPostService.Draft> drafts = request.posts().stream()
                .map(p -> new ScheduledPostService.Draft(p.network(), p.text()))
                .toList();
        List<ScheduledPost> posts = background.publish(id, drafts, request.imageIds(), request.publishAt(),
                principal.getName());
        return ResponseEntity.status(request.publishAt() == null ? HttpStatus.ACCEPTED : HttpStatus.CREATED)
                .body(posts);
    }
}
