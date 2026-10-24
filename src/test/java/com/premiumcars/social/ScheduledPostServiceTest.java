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

    private static final List<ScheduledPostService.Draft> ALL = List.of(
            new ScheduledPostService.Draft(SocialNetwork.FACEBOOK, "shared"),
            new ScheduledPostService.Draft(SocialNetwork.INSTAGRAM, "shared"),
            new ScheduledPostService.Draft(SocialNetwork.VIBER, "viber"));

    private static ScheduledPost recorded(long id, SocialNetwork network, String text, ScheduledPost.Status status) {
        return new ScheduledPost(id, 7, "Kia", network, text, List.of(3L), NOW, status, 0, null, null, "admin", NOW,
                NOW);
    }

    @Test
    void theTimeMustBeInTheFutureAndWithin90Days() {
        assertThatThrownBy(() -> service.publish(7, ALL, null, NOW, "admin"))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("minute");
        assertThatThrownBy(() -> service.publish(7, ALL, null, NOW.plusSeconds(91L * 24 * 3600), "admin"))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("90 days");
        verify(repo, never()).insertAll(anyLong(), any(), any(), any(), any());
    }

    @Test
    void eachNetworkOnceAndAtLeastOne() {
        assertThatThrownBy(() -> service.publish(7, List.of(), null, null, "admin"))
                .hasMessageContaining("at least one");
        assertThatThrownBy(() -> service.publish(7, List.of(ALL.get(0), ALL.get(0)), null, null, "admin"))
                .hasMessageContaining("once");
        verify(repo, never()).insertAll(anyLong(), any(), any(), any(), any());
    }

    @Test
    void oneWrongPostStopsTheWholePublication() {
        doThrow(new BadRequestException("Instagram captions are limited to 2200 characters"))
                .when(publisher).validate(7, SocialNetwork.INSTAGRAM, "shared", List.of(1L));
        assertThatThrownBy(() -> service.publish(7, ALL, List.of(1L), NOW.plusSeconds(3600), "admin"))
                .hasMessageContaining("2200");
        assertThatThrownBy(() -> service.publish(7, ALL, List.of(1L), null, "admin"))
                .hasMessageContaining("2200");
        verify(repo, never()).insertAll(anyLong(), any(), any(), any(), any());
    }

    @Test
    void schedulingRecordsOnePostPerNetworkForLater() {
        Instant later = NOW.plusSeconds(3600);
        service.publish(7, ALL, List.of(1L), later, "admin");

        verify(repo).insertAll(7, ALL, List.of(1L), later, "admin");
        verify(repo, never()).claim(anyLong());
        verify(publisher, never()).publish(anyLong(), any(), any(), anyList());
    }

    @Test
    void publishingNowRecordsEveryNetworkAndPublishesThemInTheBackground() {
        List<ScheduledPost> posts = List.of(
                recorded(9, SocialNetwork.FACEBOOK, "shared", ScheduledPost.Status.SCHEDULED),
                recorded(10, SocialNetwork.INSTAGRAM, "shared", ScheduledPost.Status.SCHEDULED),
                recorded(11, SocialNetwork.VIBER, "viber", ScheduledPost.Status.SCHEDULED));
        when(repo.insertAll(7, ALL, List.of(3L), NOW, "admin")).thenReturn(posts);
        for (ScheduledPost p : posts) {
            when(repo.claim(p.id())).thenReturn(Optional.of(
                    recorded(p.id(), p.network(), p.body(), ScheduledPost.Status.PUBLISHING)));
        }
        when(cars.findById(7)).thenReturn(Optional.of(car(CarStatus.PUBLISHED)));
        when(publisher.publish(eq(7L), any(), any(), eq(List.of(3L)))).thenAnswer(inv -> new SocialPost(
                70 + ((SocialNetwork) inv.getArgument(1)).ordinal(), 7, inv.getArgument(1), "x", null, 1, NOW));

        assertThat(service.publish(7, ALL, List.of(3L), null, "admin")).isSameAs(posts);

        verify(publisher).validate(7, SocialNetwork.FACEBOOK, "shared", List.of(3L));
        verify(publisher).validate(7, SocialNetwork.VIBER, "viber", List.of(3L));
        verify(publisher).publish(7, SocialNetwork.INSTAGRAM, "shared", List.of(3L));
        verify(publisher).publish(7, SocialNetwork.VIBER, "viber", List.of(3L));
        verify(repo).markPublished(9, 70);
        verify(repo).markPublished(10, 71);
        verify(repo).markPublished(11, 72);
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
