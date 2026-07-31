package com.example.ykdsummer.common.concurrent;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;

/** Stops accepting work, drains all executors within one shared deadline, then forces stragglers. */
public final class GracefulExecutorShutdown {
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private GracefulExecutorShutdown() { }

    public static Report shutdown(
            String groupName,
            Duration timeout,
            Logger log,
            ExecutorService... executors
    ) {
        return shutdown(groupName, timeout, log,
                executors == null ? List.of() : Arrays.asList(executors));
    }

    public static Report shutdown(
            String groupName,
            Duration timeout,
            Logger log,
            Collection<? extends ExecutorService> executors
    ) {
        List<ExecutorService> targets = executors == null ? List.of() : executors.stream()
                .filter(Objects::nonNull)
                .map(executor -> (ExecutorService) executor)
                .distinct()
                .toList();
        if (targets.isEmpty()) return new Report(0, 0, 0, 0);

        targets.forEach(ExecutorService::shutdown);
        long deadline = System.nanoTime() + normalize(timeout).toNanos();
        boolean interrupted = false;

        for (ExecutorService executor : targets) {
            if (executor.isTerminated()) continue;
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0L) break;
            try {
                executor.awaitTermination(remaining, TimeUnit.NANOSECONDS);
            } catch (InterruptedException exception) {
                interrupted = true;
                break;
            }
        }

        int graceful = (int) targets.stream().filter(ExecutorService::isTerminated).count();
        int forced = 0;
        int dropped = 0;
        for (ExecutorService executor : targets) {
            if (executor.isTerminated()) continue;
            forced++;
            dropped += executor.shutdownNow().size();
        }
        if (interrupted) Thread.currentThread().interrupt();

        if (log != null) {
            if (forced == 0) {
                log.info("Executor group {} stopped gracefully, executors={}", safe(groupName), targets.size());
            } else {
                log.warn("Executor group {} exceeded shutdown deadline, forced={}, droppedQueuedTasks={}",
                        safe(groupName), forced, dropped);
            }
        }
        return new Report(targets.size(), graceful, forced, dropped);
    }

    private static Duration normalize(Duration timeout) {
        return timeout == null || timeout.isZero() || timeout.isNegative() ? DEFAULT_TIMEOUT : timeout;
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "unnamed" : value.strip();
    }

    public record Report(int total, int graceful, int forced, int droppedQueuedTasks) { }
}
