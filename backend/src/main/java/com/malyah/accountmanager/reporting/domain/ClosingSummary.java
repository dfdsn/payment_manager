package com.malyah.accountmanager.reporting.domain;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * RF-FEC-01: the content of a month closing, computed from one set of lines. The totals reuse the H06.1 rule
 * ({@link DueIndicators}) over buckets built from the same lines, so totals, categories and pending entries always
 * describe exactly the lines that were read. {@link #digest()} identifies the values and classifications of the
 * content (never its labels), which lets a saved version be compared with the current data.
 */
public final class ClosingSummary {
    private static final Comparator<ClosingLine> LINE_ORDER = Comparator.comparing(ClosingLine::referenceDate)
            .thenComparing(line -> line.description().toLowerCase(java.util.Locale.ROOT))
            .thenComparing(ClosingLine::expenseId);
    private static final Comparator<ClosingCategory> CATEGORY_ORDER = Comparator
            .comparing((ClosingCategory category) -> category.categoryId() == null)
            .thenComparing(category -> category.categoryName() == null ? ""
                    : category.categoryName().toLowerCase(java.util.Locale.ROOT))
            .thenComparing(category -> category.categoryId() == null ? "" : category.categoryId().toString());

    private final List<ClosingLine> lines;
    private final DueIndicators indicators;
    private final List<ClosingCategory> categories;
    private final String digest;

    private ClosingSummary(List<ClosingLine> lines) {
        this.lines = lines;
        this.indicators = DueIndicators.from(buckets(lines));
        this.categories = categories(lines);
        this.digest = digestOf(lines);
    }

    public static ClosingSummary of(List<ClosingLine> lines) {
        Objects.requireNonNull(lines, "lines");
        var sorted = new ArrayList<>(lines);
        sorted.sort(LINE_ORDER);
        for (int i = 1; i < sorted.size(); i++)
            if (sorted.get(i).expenseId().equals(sorted.get(i - 1).expenseId()))
                throw new IllegalArgumentException("Um lançamento aparece duas vezes no fechamento.");
        return new ClosingSummary(List.copyOf(sorted));
    }

    public List<ClosingLine> lines() {
        return lines;
    }

    public List<ClosingLine> pendingLines() {
        return lines.stream().filter(line -> line.situation() == Situation.PENDING).toList();
    }

    public DueIndicators indicators() {
        return indicators;
    }

    public List<ClosingCategory> categories() {
        return categories;
    }

    public String digest() {
        return digest;
    }

    public boolean hasPending() {
        return indicators.pendingCount() > 0;
    }

    /** Groups the lines as the database groups expenses for the H06.1 totals: situation, estimate and overdue. */
    static List<TotalsBucket> buckets(List<ClosingLine> lines) {
        var groups = new LinkedHashMap<String, List<ClosingLine>>();
        for (var line : lines)
            groups.computeIfAbsent(line.situation() + "|" + line.estimated() + "|" + line.overdue(),
                    key -> new ArrayList<>()).add(line);
        return groups.values().stream().map(group -> {
            var first = group.getFirst();
            BigDecimal charge = BigDecimal.ZERO, paid = BigDecimal.ZERO, increase = BigDecimal.ZERO,
                    discount = BigDecimal.ZERO;
            for (var line : group) {
                charge = charge.add(line.chargeAmount());
                if (line.paidAmount() != null) paid = paid.add(line.paidAmount());
                var adjustment = line.adjustment();
                if (adjustment.signum() > 0) increase = increase.add(adjustment);
                else discount = discount.add(adjustment.negate());
            }
            return new TotalsBucket(first.situation(), first.estimated(), first.overdue(), group.size(), charge,
                    paid, increase, discount);
        }).toList();
    }

    private static List<ClosingCategory> categories(List<ClosingLine> lines) {
        var groups = new LinkedHashMap<UUID, List<ClosingLine>>();
        var withoutCategory = new ArrayList<ClosingLine>();
        for (var line : lines) {
            if (line.categoryId() == null) withoutCategory.add(line);
            else groups.computeIfAbsent(line.categoryId(), key -> new ArrayList<>()).add(line);
        }
        var result = new ArrayList<ClosingCategory>();
        groups.values().forEach(group -> result.add(category(group)));
        if (!withoutCategory.isEmpty()) result.add(category(withoutCategory));
        result.sort(CATEGORY_ORDER);
        return List.copyOf(result);
    }

    private static ClosingCategory category(List<ClosingLine> group) {
        var totals = DueIndicators.from(buckets(group));
        var first = group.getFirst();
        return new ClosingCategory(first.categoryId(), first.categoryName(), group.size(), totals.plannedTotal(),
                totals.plannedEstimated(), totals.paidTotal(), totals.pendingCount(), totals.pendingTotal());
    }

    static String digestOf(List<ClosingLine> lines) {
        var keys = lines.stream().sorted(Comparator.comparing(ClosingLine::expenseId)).map(ClosingLine::contentKey)
                .toList();
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(String.join("\n", keys).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
