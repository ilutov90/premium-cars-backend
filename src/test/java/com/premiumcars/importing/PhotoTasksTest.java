package com.premiumcars.importing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

class PhotoTasksTest {

    @Test
    void testRun_ParallelTasksFinishingOutOfOrder_KeepsIndexOrder() {
        List<String> out = PhotoTasks.run(12, 4, Duration.ZERO, i -> {
            sleep(ThreadLocalRandom.current().nextInt(1, 20));
            return i % 3 == 0 ? null : "photo-" + i;
        });

        List<String> expected = IntStream.range(0, 12).mapToObj(i -> i % 3 == 0 ? null : "photo-" + i).toList();
        assertThat(out).containsExactlyElementsOf(expected);
    }

    @Test
    void testRun_ParallelThree_RunsAtMostThreeAtOnce() {
        AtomicInteger running = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();

        PhotoTasks.run(10, 3, Duration.ZERO, i -> {
            peak.accumulateAndGet(running.incrementAndGet(), Math::max);
            sleep(15);
            running.decrementAndGet();
            return i;
        });

        assertThat(peak.get()).isBetween(2, 3);
    }

    @Test
    void testRun_ParallelOne_RunsOnCallingThreadInOrder() {
        Thread caller = Thread.currentThread();

        List<Boolean> sameThread = PhotoTasks.run(3, 1, Duration.ZERO, i -> Thread.currentThread() == caller);

        assertThat(sameThread).containsExactly(true, true, true);
    }

    @Test
    void testRun_StartGap_SpacesTheStarts() {
        long start = System.nanoTime();

        PhotoTasks.run(4, 4, Duration.ofMillis(30), i -> i);

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(90));
    }

    @Test
    void testRun_OneTaskThrows_PropagatesItsOwnException() {
        AtomicInteger started = new AtomicInteger();

        assertThatThrownBy(() -> PhotoTasks.run(40, 4, Duration.ZERO, i -> {
            started.incrementAndGet();
            if (i == 2) {
                throw new SourceBlockedException(403, "blocked");
            }
            sleep(20);
            return i;
        })).isExactlyInstanceOf(SourceBlockedException.class).hasMessage("blocked");
        assertThat(started.get()).isLessThan(40);
    }

    @Test
    void testRun_ParallelOneTaskThrows_PropagatesItsOwnException() {
        assertThatThrownBy(() -> PhotoTasks.run(3, 1, Duration.ZERO, i -> {
            throw new IllegalArgumentException("bad " + i);
        })).isExactlyInstanceOf(IllegalArgumentException.class).hasMessage("bad 0");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
