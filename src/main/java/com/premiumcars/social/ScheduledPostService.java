package com.premiumcars.social;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.premiumcars.catalog.domain.Car;
import com.premiumcars.catalog.domain.CarStatus;
import com.premiumcars.catalog.repo.CarRepository;
import com.premiumcars.common.BadRequestException;
import com.premiumcars.common.NotFoundException;

/**
 * Posts published in the background, now or later. One publication goes to several networks at once (one post
 * per network, the same photos): "now" records the posts and publishes them right away, in parallel, on other
 * threads, so the admin panel does not wait for Facebook / Instagram (which take up to a minute). Posts planned
 * for later (Instagram's API cannot schedule, so the backend does it for every network the same way): every 30
 * seconds the due posts are published through {@link SocialPublisher}, also in parallel.
 * A failed post is not retried automatically: a network can fail half-way (Viber photos sent, the text not; a
 * Facebook post created, its link not read), and a retry would post twice. It is marked FAILED with the reason,
 * and the admin publishes it again by hand. Sold or archived cars are not posted.
 */
@Service
public class ScheduledPostService {

    private static final Logger log = LoggerFactory.getLogger(ScheduledPostService.class);

    /** Earliest time in the future a post can be scheduled for ("now" is the publish button). */
    static final Duration MIN_LEAD = Duration.ofMinutes(1);
    static final Duration MAX_LEAD = Duration.ofDays(90);
    private static final int BATCH = 5;

    private final ScheduledPostRepository scheduled;
    private final SocialPublisher publisher;
    private final CarRepository cars;
    private final Clock clock;
    private final TaskExecutor executor;

    public ScheduledPostService(ScheduledPostRepository scheduled, SocialPublisher publisher, CarRepository cars,
                                Clock clock, @Qualifier("applicationTaskExecutor") TaskExecutor executor) {
        this.scheduled = scheduled;
        this.publisher = publisher;
        this.cars = cars;
        this.clock = clock;
        this.executor = executor;
    }

    /** The text of one network in a publication. */
    public record Draft(SocialNetwork network, String text) {
    }

    /**
     * One publication to several networks at once, with the same photos: checks every post first (nothing is
     * recorded when one is wrong), records one post per network and publishes them in the background right away,
     * at the same time ({@code publishAt} null), or at {@code publishAt}.
     *
     * @param imageIds the car's photos in posting order; null = the first ones of the gallery
     * @return the recorded posts, in the order of {@code drafts}; the admin panel follows them until PUBLISHED or
     *         FAILED
     */
    public List<ScheduledPost> publish(long carId, List<Draft> drafts, List<Long> imageIds, Instant publishAt,
                                       String createdBy) {
        if (drafts == null || drafts.isEmpty()) {
            throw new BadRequestException("Pick at least one network");
        }
        if (drafts.stream().map(Draft::network).distinct().count() != drafts.size()) {
            throw new BadRequestException("Each network can be picked once");
        }
        Instant now = clock.instant();
        if (publishAt != null) {
            if (publishAt.isBefore(now.plus(MIN_LEAD).minusSeconds(5))) {
                throw new BadRequestException("Pick a time at least a minute from now (or publish now)");
            }
            if (publishAt.isAfter(now.plus(MAX_LEAD))) {
                throw new BadRequestException("Posts can be scheduled at most " + MAX_LEAD.toDays() + " days ahead");
            }
        }
        for (Draft d : drafts) {
            publisher.validate(carId, d.network(), d.text(), imageIds);
        }
        List<ScheduledPost> posts = scheduled.insertAll(carId, drafts, imageIds, publishAt == null ? now : publishAt,
                createdBy);
        List<SocialNetwork> networks = drafts.stream().map(Draft::network).toList();
        if (publishAt == null) {
            log.info("Admin '{}' publishes car {} to {} (in the background)", createdBy, carId, networks);
            posts.forEach(this::publishInBackground);
        } else {
            log.info("Admin '{}' scheduled car {} for {} at {}", createdBy, carId, networks, publishAt);
        }
        return posts;
    }

    public List<ScheduledPost> forCar(long carId) {
        if (cars.findById(carId).isEmpty()) {
            throw new NotFoundException("Car not found: " + carId);
        }
        return scheduled.findByCarId(carId);
    }

    /** Waiting posts and those of the last 7 days. */
    public List<ScheduledPost> overview() {
        return scheduled.findUpcomingAndRecent(clock.instant().minus(Duration.ofDays(7)));
    }

    public void cancel(long id, String by) {
        ScheduledPost post = scheduled.findById(id)
                .orElseThrow(() -> new NotFoundException("Scheduled post not found: " + id));
        if (!scheduled.cancel(id)) {
            throw new BadRequestException("Only waiting posts can be cancelled (this one is " + post.status() + ")");
        }
        log.info("Admin '{}' cancelled scheduled post {} (car {}, {})", by, id, post.carId(), post.network());
    }

    /** After a restart: posts that were being published when the backend stopped. */
    @EventListener(ApplicationReadyEvent.class)
    public void failInterrupted() {
        int n = scheduled.failInterrupted(clock.instant());
        if (n > 0) {
            log.warn("{} scheduled post(s) were interrupted by a restart and marked FAILED", n);
        }
    }

    @Scheduled(fixedDelayString = "${app.social.schedule-check:PT30S}",
            initialDelayString = "${app.social.schedule-initial-delay:PT1M}")
    public void publishDue() {
        List<ScheduledPost> due;
        do {
            due = scheduled.claimDue(BATCH);
            // the networks of one publication go out at the same time
            due.forEach(post -> executor.execute(() -> publish(post)));
        } while (due.size() == BATCH);
    }

    private void publishInBackground(ScheduledPost post) {
        executor.execute(() -> scheduled.claim(post.id()).ifPresent(this::publish));
    }

    void publish(ScheduledPost post) {
        try {
            Car car = cars.findById(post.carId()).orElse(null);
            if (car == null) {
                scheduled.markFailed(post.id(), "The car was deleted");
                return;
            }
            if (car.status() == CarStatus.SOLD || car.status() == CarStatus.ARCHIVED) {
                scheduled.markFailed(post.id(), "Not posted: the car is " + car.status());
                return;
            }
            List<Long> imageIds = post.imageIds().isEmpty() ? null : post.imageIds();
            SocialPost published = publisher.publish(post.carId(), post.network(), post.body(), imageIds);
            scheduled.markPublished(post.id(), published.id());
            log.info("Scheduled post {} published (car {}, {})", post.id(), post.carId(), post.network());
        } catch (RuntimeException e) {
            String error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            scheduled.markFailed(post.id(), error);
            log.error("Scheduled post {} (car {}, {}) failed: {}", post.id(), post.carId(), post.network(), error);
        }
    }
}
