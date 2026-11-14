package com.premiumcars.importing;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.IntFunction;

/**
 * Runs one task per photo of a listing, up to {@code parallel} at a time on virtual threads of their own (a new
 * executor per call), and returns the results in the photos' order. Tasks start in index order, at least
 * {@code startGap} apart. The first task that throws cancels the others and its exception reaches the caller as it
 * was thrown. With {@code parallel} 1 the tasks run one after another on the calling thread, {@code startGap} apart.
 */
final class PhotoTasks {

    private PhotoTasks() {
    }

    /** @return the results by index; null where the task returned null */
    static <T> List<T> run(int count, int parallel, Duration startGap, IntFunction<T> task) {
        if (parallel <= 1 || count <= 1) {
            List<T> out = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                if (i > 0) {
                    pause(startGap);
                }
                out.add(task.apply(i));
            }
            return out;
        }
        Run<T> run = new Run<>(count, startGap, task);
        int workers = Math.min(parallel, count);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var done = new ExecutorCompletionService<Void>(pool);
            List<Future<Void>> futures = new ArrayList<>(workers);
            for (int w = 0; w < workers; w++) {
                futures.add(done.submit(run::work));
            }
            try {
                for (int w = 0; w < workers; w++) {
                    done.take().get();
                }
            } catch (ExecutionException e) {
                run.failed = true;
                futures.forEach(f -> f.cancel(true));
                throw unwrap(e.getCause());
            } catch (InterruptedException e) {
                run.failed = true;
                futures.forEach(f -> f.cancel(true));
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while handling the photos", e);
            }
        }
        return run.results();
    }

    /** One call's shared state: the next index to start and when it may start. */
    private static final class Run<T> {
        private final int count;
        private final long gapNanos;
        private final IntFunction<T> task;
        private final AtomicReferenceArray<T> results;
        private int nextIndex;
        private long nextStart = System.nanoTime();
        private volatile boolean failed;

        Run(int count, Duration gap, IntFunction<T> task) {
            this.count = count;
            this.gapNanos = Math.max(0, gap.toNanos());
            this.task = task;
            this.results = new AtomicReferenceArray<>(count);
        }

        Void work() throws InterruptedException {
            while (!failed && !Thread.currentThread().isInterrupted()) {
                int i;
                long startAt;
                synchronized (this) {
                    if (nextIndex >= count) {
                        return null;
                    }
                    i = nextIndex++;
                    startAt = Math.max(System.nanoTime(), nextStart);
                    nextStart = startAt + gapNanos;
                }
                long wait = startAt - System.nanoTime();
                if (wait > 0) {
                    Thread.sleep(Duration.ofNanos(wait));
                }
                try {
                    results.set(i, task.apply(i));
                } catch (RuntimeException | Error e) {
                    failed = true;
                    throw e;
                }
            }
            return null;
        }

        List<T> results() {
            List<T> out = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                out.add(results.get(i));
            }
            return out;
        }
    }

    private static RuntimeException unwrap(Throwable cause) {
        if (cause instanceof RuntimeException r) {
            return r;
        }
        if (cause instanceof Error e) {
            throw e;
        }
        return new IllegalStateException(cause);
    }

    private static void pause(Duration d) {
        if (d.isZero() || d.isNegative()) {
            return;
        }
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
