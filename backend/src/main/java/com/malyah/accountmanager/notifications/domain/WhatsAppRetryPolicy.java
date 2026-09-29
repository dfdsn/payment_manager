package com.malyah.accountmanager.notifications.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * H08.5 (RF-ALT-17): when the next attempt of a summary may start after an attempt that certainly did not reach the
 * provider. The spacing grows (1, 5, 15 and 30 minutes, at most five attempts in all) and a wait asked by the
 * provider is honored when it is longer. A retry that would not start before the deadline of the window does not
 * exist: the window is never extended by a failure, a restart or a retry.
 */
public final class WhatsAppRetryPolicy {
    public static final List<Duration> DELAYS = List.of(Duration.ofMinutes(1), Duration.ofMinutes(5),
            Duration.ofMinutes(15), Duration.ofMinutes(30));
    public static final int MAX_ATTEMPTS = DELAYS.size() + 1;

    private WhatsAppRetryPolicy() {
    }

    /**
     * @param failedAttempt number of the attempt that just failed (1 for the first)
     * @param providerWait the wait the provider asked for, or {@code null}
     */
    public static Optional<Instant> next(int failedAttempt, Instant failedAt, Duration providerWait,
            Instant deadline) {
        if (failedAttempt < 1 || failedAttempt >= MAX_ATTEMPTS) return Optional.empty();
        var delay = DELAYS.get(failedAttempt - 1);
        if (providerWait != null && providerWait.compareTo(delay) > 0) delay = providerWait;
        var next = failedAt.plus(delay);
        return next.isBefore(deadline) ? Optional.of(next) : Optional.empty();
    }
}
