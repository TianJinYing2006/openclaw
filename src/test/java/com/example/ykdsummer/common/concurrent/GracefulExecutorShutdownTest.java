package com.example.ykdsummer.common.concurrent;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class GracefulExecutorShutdownTest {

    @Test
    void drainsAcceptedWorkBeforeTheSharedDeadline() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean completed = new AtomicBoolean();
        executor.execute(() -> {
            started.countDown();
            completed.set(true);
        });
        assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();

        GracefulExecutorShutdown.Report report = GracefulExecutorShutdown.shutdown(
                "test", Duration.ofSeconds(1), null, executor);

        assertThat(completed).isTrue();
        assertThat(report.total()).isEqualTo(1);
        assertThat(report.graceful()).isEqualTo(1);
        assertThat(report.forced()).isZero();
    }

    @Test
    void forcesWorkThatExceedsTheDeadline() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        executor.execute(() -> {
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();

        GracefulExecutorShutdown.Report report = GracefulExecutorShutdown.shutdown(
                "test", Duration.ofMillis(10), null, executor);
        release.countDown();

        assertThat(report.total()).isEqualTo(1);
        assertThat(report.forced()).isEqualTo(1);
        assertThat(executor.isShutdown()).isTrue();
    }
}
