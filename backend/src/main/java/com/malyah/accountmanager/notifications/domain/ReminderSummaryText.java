package com.malyah.accountmanager.notifications.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * The single message of a summary (RF-ALT-10/11), ready for any channel: count and total of every bill with the
 * estimates flagged, up to five details, the “e mais X contas” line and the authenticated link. H08.4 may adapt it
 * to the approved provider template without splitting it into several messages.
 */
public final class ReminderSummaryText {
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM");
    private static final DateTimeFormatter FULL_DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private ReminderSummaryText() { }

    public static String render(ReminderSummary summary, LocalTime time, String link) {
        var text = new StringBuilder();
        text.append("Contas a pagar — ").append(FULL_DAY.format(summary.date())).append(", ")
                .append(ReminderSchedule.format(time)).append('\n');
        text.append(summary.count()).append(summary.count() == 1 ? " conta" : " contas").append(", total ")
                .append(money(summary.total()));
        if (summary.overdueCount() > 0)
            text.append(", ").append(summary.overdueCount())
                    .append(summary.overdueCount() == 1 ? " atrasada" : " atrasadas");
        if (summary.estimatedCount() > 0)
            text.append(" (").append(summary.estimatedCount())
                    .append(summary.estimatedCount() == 1 ? " estimada: " : " estimadas: ")
                    .append(money(summary.estimatedTotal())).append(')');
        text.append('\n');
        for (var item : summary.details()) {
            text.append("• ");
            if (item.overdue(summary.date())) text.append("ATRASADA ");
            text.append(DAY.format(item.dueDate())).append(' ').append(item.label()).append(" — ")
                    .append(money(item.amount()));
            if (item.estimated()) text.append(" (estimada)");
            text.append('\n');
        }
        if (summary.remaining() > 0)
            text.append("e mais ").append(summary.remaining())
                    .append(summary.remaining() == 1 ? " conta" : " contas").append('\n');
        return text.append("Lista completa: ").append(link).toString();
    }

    /** pt-BR currency without depending on the JVM locale data: R$ 1.234,56. */
    public static String money(BigDecimal value) {
        var symbols = new DecimalFormatSymbols(Locale.ROOT);
        symbols.setGroupingSeparator('.');
        symbols.setDecimalSeparator(',');
        var format = new DecimalFormat("#,##0.00", symbols);
        return "R$ " + format.format(value.setScale(2, RoundingMode.UNNECESSARY));
    }
}
