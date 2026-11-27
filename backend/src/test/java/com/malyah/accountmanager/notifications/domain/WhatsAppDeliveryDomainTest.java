package com.malyah.accountmanager.notifications.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** H08.4 domain rules, as changed by T36: forward-only confirmations, the webhook secret and the message text. */
class WhatsAppDeliveryDomainTest {
    static final LocalDate TODAY = LocalDate.of(2026, 10, 5);

    @Test
    void confirmationsOnlyMoveForward() {
        var s = WhatsAppDeliveryStatus.class;
        assertThat(WhatsAppDeliveryStatus.ACCEPTED.canAdvanceTo(WhatsAppDeliveryStatus.SENT)).isTrue();
        assertThat(WhatsAppDeliveryStatus.ACCEPTED.canAdvanceTo(WhatsAppDeliveryStatus.READ)).isTrue();
        assertThat(WhatsAppDeliveryStatus.SENT.canAdvanceTo(WhatsAppDeliveryStatus.DELIVERED)).isTrue();
        assertThat(WhatsAppDeliveryStatus.SENT.canAdvanceTo(WhatsAppDeliveryStatus.SENT)).isFalse();
        assertThat(WhatsAppDeliveryStatus.DELIVERED.canAdvanceTo(WhatsAppDeliveryStatus.SENT)).isFalse();
        assertThat(WhatsAppDeliveryStatus.DELIVERED.canAdvanceTo(WhatsAppDeliveryStatus.READ)).isTrue();
        assertThat(WhatsAppDeliveryStatus.UNCERTAIN.canAdvanceTo(WhatsAppDeliveryStatus.SENT)).isTrue();
        assertThat(WhatsAppDeliveryStatus.UNCERTAIN.canAdvanceTo(WhatsAppDeliveryStatus.FAILED)).isTrue();
        assertThat(WhatsAppDeliveryStatus.SENT.canAdvanceTo(WhatsAppDeliveryStatus.FAILED)).isTrue();
        assertThat(WhatsAppDeliveryStatus.DELIVERED.canAdvanceTo(WhatsAppDeliveryStatus.FAILED)).isFalse();
        assertThat(WhatsAppDeliveryStatus.ACCEPTED.canAdvanceTo(WhatsAppDeliveryStatus.ACCEPTED)).isFalse();
        assertThat(WhatsAppDeliveryStatus.ACCEPTED.canAdvanceTo(WhatsAppDeliveryStatus.REJECTED)).isFalse();
        assertThat(WhatsAppDeliveryStatus.SENT.canAdvanceTo(WhatsAppDeliveryStatus.UNCERTAIN)).isFalse();
        for (var terminal : List.of(WhatsAppDeliveryStatus.READ, WhatsAppDeliveryStatus.FAILED,
                WhatsAppDeliveryStatus.REJECTED, WhatsAppDeliveryStatus.SKIPPED, WhatsAppDeliveryStatus.ATTEMPTING))
            for (var next : s.getEnumConstants()) assertThat(terminal.canAdvanceTo(next)).isFalse();
    }

    @Test
    void onlyTheFourNeutralStatusesAreRead() {
        assertThat(WhatsAppDeliveryStatus.fromWebhook("sent")).contains(WhatsAppDeliveryStatus.SENT);
        assertThat(WhatsAppDeliveryStatus.fromWebhook("delivered")).contains(WhatsAppDeliveryStatus.DELIVERED);
        assertThat(WhatsAppDeliveryStatus.fromWebhook("read")).contains(WhatsAppDeliveryStatus.READ);
        assertThat(WhatsAppDeliveryStatus.fromWebhook("failed")).contains(WhatsAppDeliveryStatus.FAILED);
        assertThat(WhatsAppDeliveryStatus.fromWebhook("deleted")).isEmpty();
        assertThat(WhatsAppDeliveryStatus.fromWebhook("SENT")).isEmpty();
        assertThat(WhatsAppDeliveryStatus.fromWebhook(null)).isEqualTo(Optional.empty());
        assertThat(List.of(WhatsAppDeliveryStatus.values()).stream().filter(WhatsAppDeliveryStatus::webhook))
                .containsExactly(WhatsAppDeliveryStatus.SENT, WhatsAppDeliveryStatus.DELIVERED,
                        WhatsAppDeliveryStatus.READ, WhatsAppDeliveryStatus.FAILED);
    }

    @Test
    void webhookTokenIsComparedExactly() {
        assertThat(WebhookToken.matches("token-secreto", "token-secreto")).isTrue();
        assertThat(WebhookToken.matches("token-secreto", "token-secretO")).isFalse();
        assertThat(WebhookToken.matches("token-secreto", "token-secreto ")).isFalse();
        assertThat(WebhookToken.matches("token-secreto", "")).isFalse();
        assertThat(WebhookToken.matches("token-secreto", null)).isFalse();
        assertThat(WebhookToken.matches("", "")).isFalse();
        assertThat(WebhookToken.matches(null, "x")).isFalse();
    }

    @Test
    void summaryTextHasOneLinePerBill() {
        var items = new ArrayList<ReminderItem>();
        items.add(ReminderItem.expense(UUID.randomUUID(), "ONE_OFF", "Aluguel\tdo\napartamento   grande",
                new BigDecimal("1500.00"), TODAY.minusDays(2), false, null, null));
        items.add(ReminderItem.expense(UUID.randomUUID(), "INSTALLMENT", "Geladeira", new BigDecimal("300.00"),
                TODAY, true, 2, 10));
        items.add(ReminderItem.expense(UUID.randomUUID(), "ONE_OFF", "x".repeat(60), new BigDecimal("1.00"),
                TODAY.plusDays(1), false, null, null));
        for (var i = 0; i < 4; i++)
            items.add(ReminderItem.expense(UUID.randomUUID(), "ONE_OFF", "Conta " + i, new BigDecimal("10.00"),
                    TODAY.plusDays(3), false, null, null));
        var summary = ReminderSummary.compose(TODAY, ReminderSlot.FIRST, items).orElseThrow();
        var text = WhatsAppSummaryMessage.text(summary, LocalTime.of(9, 0), "https://x/l/1");
        assertThat(text.split("\n", -1)).containsExactly(
                "Contas a pagar em 05/10/2026, 09:00.",
                "7 contas, total R$ 1.841,00, 1 atrasada (1 estimada: R$ 300,00)",
                "• ATRASADA 03/10 Aluguel do apartamento grande R$ 1.500,00",
                "• 05/10 Geladeira (2/10) R$ 300,00 (estimada)",
                "• 06/10 " + "x".repeat(39) + "… R$ 1,00",
                "• 08/10 Conta 0 R$ 10,00",
                "• 08/10 Conta 1 R$ 10,00",
                "• e mais 2 contas",
                "Lista completa: https://x/l/1");
        assertThat(text).doesNotContain("\t", "    ");

        var one = ReminderSummary.compose(TODAY, ReminderSlot.FIRST, List.of(ReminderItem.expense(UUID.randomUUID(),
                "ONE_OFF", "Luz", new BigDecimal("120.00"), TODAY.minusDays(1), true, null, null))).orElseThrow();
        assertThat(WhatsAppSummaryMessage.text(one, LocalTime.of(18, 0), "l").split("\n", -1)).containsExactly(
                "Contas a pagar em 05/10/2026, 18:00.",
                "1 conta, total R$ 120,00, 1 atrasada (1 estimada: R$ 120,00)",
                "• ATRASADA 04/10 Luz R$ 120,00 (estimada)",
                "Lista completa: l");
        var six = new ArrayList<ReminderItem>();
        for (var i = 0; i < 6; i++)
            six.add(ReminderItem.expense(UUID.randomUUID(), "ONE_OFF", "C" + i, BigDecimal.ONE, TODAY, false, null,
                    null));
        var plural = WhatsAppSummaryMessage.text(ReminderSummary.compose(TODAY, ReminderSlot.FIRST, six)
                .orElseThrow(), LocalTime.of(9, 0), "l").split("\n", -1);
        assertThat(plural[1]).isEqualTo("6 contas, total R$ 6,00");
        assertThat(plural[plural.length - 2]).isEqualTo("• e mais 1 conta");
        assertThat(WhatsAppSummaryMessage.oneLine("x".repeat(40))).isEqualTo("x".repeat(40));
        assertThat(WhatsAppSummaryMessage.oneLine("x".repeat(41))).isEqualTo("x".repeat(39) + "…");
        assertThat(WhatsAppSummaryMessage.TEST).doesNotContain("R$", "http").contains("Nenhum dado financeiro");
    }

    @Test
    void theWindowFailureHasItsOwnMessage() {
        assertThat(WhatsAppFailureReason.fromCode("NOT_SENT_IN_WINDOW")).contains(
                WhatsAppFailureReason.NOT_SENT_IN_WINDOW);
        assertThat(WhatsAppFailureReason.NOT_SENT_IN_WINDOW.message()).contains("não será enviado depois");
    }
}
