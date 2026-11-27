package com.malyah.accountmanager.notifications.domain;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * The WhatsApp text of one summary, one message per summary (RF-ALT-10/11). Since the move to the Evolution API
 * (T36) the message is free text instead of a Meta template, so it keeps the same content the template proposal
 * had, one line per bill:
 *
 * <pre>
 * Contas a pagar em 05/10/2026, 09:00.
 * 2 contas, total R$ 170,00, 1 atrasada
 * • ATRASADA 03/10 Aluguel R$ 50,00
 * • 05/10 Luz R$ 120,00
 * Lista completa: https://…/lembretes/resumos/…
 * </pre>
 *
 * Each description is flattened to one line and cut at {@link #LABEL_LIMIT} characters; the summary itself limits
 * how many bills are listed, so the whole text stays far below the WhatsApp text limit. The test message
 * ({@link #TEST}) has no financial data.
 */
public final class WhatsAppSummaryMessage {
    static final int LABEL_LIMIT = 40;
    /** The administrator's test message: no bill, amount or link. */
    public static final String TEST = "Teste do gerenciador de contas: este número está pronto para receber os "
            + "resumos de contas a pagar pelo WhatsApp. Nenhum dado financeiro foi enviado nesta mensagem.";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM");
    private static final DateTimeFormatter FULL_DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private WhatsAppSummaryMessage() { }

    public static String text(ReminderSummary summary, LocalTime time, String link) {
        return String.join("\n", lines(summary, time, link));
    }

    static List<String> lines(ReminderSummary summary, LocalTime time, String link) {
        var lines = new ArrayList<String>();
        lines.add("Contas a pagar em " + FULL_DAY.format(summary.date()) + ", " + ReminderSchedule.format(time) + ".");
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
        lines.add(headline.toString());
        for (var item : summary.details()) {
            var line = new StringBuilder("• ");
            if (item.overdue(summary.date())) line.append("ATRASADA ");
            line.append(DAY.format(item.dueDate())).append(' ').append(oneLine(item.label())).append(' ')
                    .append(ReminderSummaryText.money(item.amount()));
            if (item.estimated()) line.append(" (estimada)");
            lines.add(line.toString());
        }
        if (summary.remaining() > 0)
            lines.add("• e mais " + summary.remaining() + (summary.remaining() == 1 ? " conta" : " contas"));
        lines.add("Lista completa: " + link);
        return lines;
    }

    /** No line breaks, tabs or long runs of spaces inside a line; long descriptions end with an ellipsis. */
    static String oneLine(String text) {
        var flat = text.replaceAll("\\s+", " ").strip();
        return flat.length() <= LABEL_LIMIT ? flat : flat.substring(0, LABEL_LIMIT - 1).stripTrailing() + "…";
    }
}
