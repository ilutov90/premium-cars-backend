package com.premiumcars.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.premiumcars.catalog.domain.Car;
import com.premiumcars.catalog.domain.CarSource;
import com.premiumcars.catalog.domain.CarSpecs;
import com.premiumcars.catalog.domain.CarStatus;
import com.premiumcars.catalog.repo.CarRepository;
import com.premiumcars.common.BadRequestException;

/** Scheduling rules and what happens to a due post; no database, no network. */
class ScheduledPostServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-21T15:00:00Z");

    private final ScheduledPostRepository repo = mock(ScheduledPostRepository.class);
    private final SocialPublisher publisher = mock(SocialPublisher.class);
    private final CarRepository cars = mock(CarRepository.class);
    /** Runs the background work right away, on the test's thread. */
    private final ScheduledPostService service =
            new ScheduledPostService(repo, publisher, cars, Clock.fixed(NOW, ZoneOffset.UTC), Runnable::run);

    private static Car car(CarStatus status) {
        return new Car(7, "kia-7", status, CarSource.AUTOWINI, null, null, null,
                CarSpecs.builder().title("Kia").build(), null, null, NOW, NOW, null);
    }

    private static ScheduledPost due(List<Long> imageIds) {
        return new ScheduledPost(1, 7, "Kia", SocialNetwork.FACEBOOK, "text", imageIds, NOW,
                ScheduledPost.Status.PUBLISHING, 1, null, null, "admin", NOW, NOW);
    }

    @Test
    void theTimeMustBeInTheFutureAndWithin90Days() {
        assertThatThrownBy(() -> service.schedule(7, SocialNetwork.VIBER, "t", null, NOW, "admin"))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("minute");
        assertThatThrownBy(() -> service.schedule(7, SocialNetwork.VIBER, "t", null,
                NOW.plusSeconds(91L * 24 * 3600), "admin"))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("90 days");
        verify(repo, never()).insert(anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    void schedulingValidatesLikePublishingNow() {
        doThrow(new BadRequestException("Instagram captions are limited to 2200 characters"))
                .when(publisher).validate(7, SocialNetwork.INSTAGRAM, "long", List.of(1L));
        assertThatThrownBy(() -> service.schedule(7, SocialNetwork.INSTAGRAM, "long", List.of(1L),
                NOW.plusSeconds(3600), "admin")).hasMessageContaining("2200");
        verify(repo, never()).insert(anyLong(), any(), any(), any(), any(), any());

        service.schedule(7, SocialNetwork.VIBER, "ok", List.of(1L), NOW.plusSeconds(3600), "admin");
        verify(repo).insert(7, SocialNetwork.VIBER, "ok", List.of(1L), NOW.plusSeconds(3600), "admin");
    }

    @Test
    void publishNowRecordsThePostAndPublishesItInTheBackground() {
        ScheduledPost recorded = new ScheduledPost(9, 7, "Kia", SocialNetwork.INSTAGRAM, "text", List.of(3L), NOW,
                ScheduledPost.Status.SCHEDULED, 0, null, null, "admin", NOW, NOW);
        when(repo.insert(7, SocialNetwork.INSTAGRAM, "text", List.of(3L), NOW, "admin")).thenReturn(recorded);
        ScheduledPost claimed = new ScheduledPost(9, 7, "Kia", SocialNetwork.INSTAGRAM, "text", List.of(3L), NOW,
                ScheduledPost.Status.PUBLISHING, 1, null, null, "admin", NOW, NOW);
        when(repo.claim(9)).thenReturn(Optional.of(claimed));
        when(cars.findById(7)).thenReturn(Optional.of(car(CarStatus.PUBLISHED)));
        when(publisher.publish(7, SocialNetwork.INSTAGRAM, "text", List.of(3L)))
                .thenReturn(new SocialPost(70, 7, SocialNetwork.INSTAGRAM, "media", null, 1, NOW));

        assertThat(service.publishNow(7, SocialNetwork.INSTAGRAM, "text", List.of(3L), "admin")).isSameAs(recorded);

        verify(publisher).validate(7, SocialNetwork.INSTAGRAM, "text", List.of(3L));
        verify(repo).markPublished(9, 70);
    }

    @Test
    void publishNowChecksFirst() {
        doThrow(new BadRequestException("Instagram is not configured")).when(publisher)
                .validate(7, SocialNetwork.INSTAGRAM, "text", null);
        assertThatThrownBy(() -> service.publishNow(7, SocialNetwork.INSTAGRAM, "text", null, "admin"))
                .hasMessageContaining("not configured");
        verify(repo, never()).insert(anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    void aDuePostIsPublishedWithItsPhotos() {
        when(cars.findById(7)).thenReturn(Optional.of(car(CarStatus.PUBLISHED)));
        when(publisher.publish(7, SocialNetwork.FACEBOOK, "text", List.of(2L, 1L)))
                .thenReturn(new SocialPost(55, 7, SocialNetwork.FACEBOOK, "PAGE_1", null, 2, NOW));

        service.publish(due(List.of(2L, 1L)));

        verify(repo).markPublished(1, 55);
    }

    @Test
    void noPhotoChoiceMeansTheGalleryDefault() {
        when(cars.findById(7)).thenReturn(Optional.of(car(CarStatus.DRAFT)));
        when(publisher.publish(eq(7L), eq(SocialNetwork.FACEBOOK), eq("text"), isNull()))
                .thenReturn(new SocialPost(56, 7, SocialNetwork.FACEBOOK, "PAGE_2", null, 6, NOW));

        service.publish(due(List.of()));

        verify(repo).markPublished(1, 56);
    }

    @Test
    void soldCarsAreNotPosted() {
        when(cars.findById(7)).thenReturn(Optional.of(car(CarStatus.SOLD)));

        service.publish(due(List.of(1L)));

        verify(repo).markFailed(eq(1L), contains("SOLD"));
        verify(publisher, never()).publish(anyLong(), any(), any(), anyList());
    }

    @Test
    void aFailureIsRecordedAndNotRetried() {
        when(cars.findById(7)).thenReturn(Optional.of(car(CarStatus.PUBLISHED)));
        when(publisher.publish(anyLong(), any(), any(), anyList()))
                .thenThrow(new BadRequestException("Facebook post failed: token expired"));

        service.publish(due(List.of(1L)));

        verify(repo).markFailed(eq(1L), contains("token expired"));
        verify(repo, never()).markPublished(anyLong(), anyLong());
    }
}
