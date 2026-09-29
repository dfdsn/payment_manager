package com.malyah.accountmanager.notifications.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * RF-ALT-08 to RF-ALT-11: the logical summary of one slot. Count and totals cover every eligible bill; at most
 * {@link #DETAIL_LIMIT} are detailed, overdue first, then the nearest due date, then description and identity, so
 * the order never depends on how the bills were read. No bill means no summary.
 */
public record ReminderSummary(LocalDate date, ReminderSlot slot, List<ReminderItem> items, BigDecimal total,
        int estimatedCount, BigDecimal estimatedTotal, int overdueCount) {
    public static final int DETAIL_LIMIT = 5;
    public static final Comparator<ReminderItem> ORDER = Comparator
            .comparing(ReminderItem::dueDate)
            .thenComparing(item -> item.label().toLowerCase(Locale.ROOT))
            .thenComparing(ReminderItem::key);

    public ReminderSummary {
        items = List.copyOf(items);
    }

    /** Keeps the eligible bills of the slot, each once by its key, and composes the summary if any is left. */
    public static Optional<ReminderSummary> compose(LocalDate today, ReminderSlot slot,
            Collection<ReminderItem> candidates) {
        var unique = new LinkedHashMap<String, ReminderItem>();
        for (var item : candidates)
            if (ReminderCalendar.eligible(slot, item.dueDate(), today)) unique.putIfAbsent(item.key(), item);
        if (unique.isEmpty()) return Optional.empty();
        // Overdue bills have the earliest due dates, so the due-date order already puts them first.
        var items = unique.values().stream().sorted(ORDER).toList();
        var total = BigDecimal.ZERO;
        var estimatedTotal = BigDecimal.ZERO;
        var estimated = 0;
        var overdue = 0;
        for (var item : items) {
            total = total.add(item.amount());
            if (item.estimated()) {
                estimated++;
                estimatedTotal = estimatedTotal.add(item.amount());
            }
            if (item.overdue(today)) overdue++;
        }
        return Optional.of(new ReminderSummary(today, slot, items, total, estimated, estimatedTotal, overdue));
    }

    public int count() {
        return items.size();
    }

    public List<ReminderItem> details() {
        return items.subList(0, Math.min(DETAIL_LIMIT, items.size()));
    }

    /** The “e mais X contas” of RF-ALT-11. */
    public int remaining() {
        return items.size() - details().size();
    }
}
