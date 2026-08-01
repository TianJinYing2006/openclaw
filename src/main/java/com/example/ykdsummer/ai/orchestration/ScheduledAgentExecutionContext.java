package com.example.ykdsummer.ai.orchestration;

/**
 * Marks a background, scheduled Agent execution on its worker thread.
 * Reminder-management tools are withheld for this scope so a due task cannot schedule itself again.
 */
public final class ScheduledAgentExecutionContext {
    private static final ThreadLocal<Boolean> SCHEDULED = new ThreadLocal<>();

    private ScheduledAgentExecutionContext() { }

    public static Scope enter() {
        Boolean prior = SCHEDULED.get();
        SCHEDULED.set(Boolean.TRUE);
        return () -> {
            if (prior == null) SCHEDULED.remove();
            else SCHEDULED.set(prior);
        };
    }

    public static boolean active() {
        return Boolean.TRUE.equals(SCHEDULED.get());
    }

    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
