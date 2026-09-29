package com.malyah.accountmanager.notifications.domain;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * H08.4: the four body parameters of the summary template, one message per summary (RF-ALT-10/11). The proposed
 * template body (P03, subject to Meta approval) is:
 *
 * <pre>
 * Contas a pagar em {{1}}.
 * {{2}}
 * {{3}}
 * Lista completa: {{4}}
 * </pre>
 *
 * A template parameter must fit on one line, so the details are joined with {@code "; "} and each description is
 * cut at {@link #LABEL_LIMIT} characters; the whole body stays far below the template size limit.
 */
public final class WhatsAppSummaryTemplate {
    public static final int PARAMETERS = 4;
    static final int LABEL_LIMIT = 40;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM");
    private static final DateTimeFormatter FULL_DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private WhatsAppSummaryTemplate() { }

    public static List<String> parameters(ReminderSummary summary, LocalTime time, String link) {
        var headline = new StringBuilder();
        headline.append(summary.count()).append(summary.count() == 1 ? " conta" : " contas").append(", total ")
                .append(ReminderSummaryText.money(summary.total()));
        if (summary.overdueCount() > 0)
            headline.append(", ").append(summary.overdueCount())
                    .append(summary.overdueCount() == 1 ? " atrasada" : " atrasadas");
        if (summary.estimatedCount() > 0)
            headline.append(" (").append(summary.estimatedCount())
                    .append(summary.estimatedCount() == 1 ? " estimada: " : " estimadas: ")
                    .append(ReminderSummaryText.money(summary.estimatedTotal())).append(')');
        var details = new StringBuilder();
        for (var item : summary.details()) {
            if (!details.isEmpty()) details.append("; ");
            if (item.overdue(summary.date())) details.append("ATRASADA ");
            details.append(DAY.format(item.dueDate())).append(' ').append(oneLine(item.label())).append(" ")
                    .append(ReminderSummaryText.money(item.amount()));
            if (item.estimated()) details.append(" (estimada)");
        }
        if (summary.remaining() > 0)
            details.append("; e mais ").append(summary.remaining())
                    .append(summary.remaining() == 1 ? " conta" : " contas");
        return List.of(FULL_DAY.format(summary.date()) + ", " + ReminderSchedule.format(time), headline.toString(),
                details.toString(), link);
    }

    /** No line breaks, tabs or long runs of spaces inside a parameter; long descriptions end with an ellipsis. */
    static String oneLine(String text) {
        var flat = text.replaceAll("\\s+", " ").strip();
        return flat.length() <= LABEL_LIMIT ? flat : flat.substring(0, LABEL_LIMIT - 1).stripTrailing() + "…";
    }
}
