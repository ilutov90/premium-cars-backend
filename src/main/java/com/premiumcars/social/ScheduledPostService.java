package com.premiumcars.social;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.premiumcars.catalog.domain.Car;
import com.premiumcars.catalog.domain.CarStatus;
import com.premiumcars.catalog.repo.CarRepository;
import com.premiumcars.common.BadRequestException;
import com.premiumcars.common.NotFoundException;

/**
 * Posts planned for later, for all three networks (Instagram's API cannot schedule, so the backend does it for
 * every network the same way): every 30 seconds the due posts are published through {@link SocialPublisher}.
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

    public ScheduledPostService(ScheduledPostRepository scheduled, SocialPublisher publisher, CarRepository cars,
                                Clock clock) {
        this.scheduled = scheduled;
        this.publisher = publisher;
        this.cars = cars;
        this.clock = clock;
    }

    public ScheduledPost schedule(long carId, SocialNetwork network, String text, List<Long> imageIds,
                                  Instant publishAt, String createdBy) {
        Instant now = clock.instant();
        if (publishAt == null || publishAt.isBefore(now.plus(MIN_LEAD).minusSeconds(5))) {
            throw new BadRequestException("Pick a time at least a minute from now (or publish now)");
        }
        if (publishAt.isAfter(now.plus(MAX_LEAD))) {
            throw new BadRequestException("Posts can be scheduled at most " + MAX_LEAD.toDays() + " days ahead");
        }
        publisher.validate(carId, network, text, imageIds);
        ScheduledPost post = scheduled.insert(carId, network, text, imageIds, publishAt, createdBy);
        log.info("Admin '{}' scheduled car {} for {} at {}", createdBy, carId, network, publishAt);
        return post;
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
            due.forEach(this::publish);
        } while (due.size() == BATCH);
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
