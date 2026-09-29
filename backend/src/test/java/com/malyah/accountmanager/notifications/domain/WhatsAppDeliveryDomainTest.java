package com.malyah.accountmanager.notifications.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** H08.4 domain rules: forward-only confirmations, the webhook signature and the template parameters. */
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
    void onlyTheFourMetaStatusesAreRead() {
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
    void signatureIsTheHmacOfTheExactBody() {
        var body = "{\"object\":\"whatsapp_business_account\"}".getBytes(StandardCharsets.UTF_8);
        // Reference value computed independently: printf '%s' '<body>' | openssl dgst -sha256 -hmac segredo
        var header = WebhookSignature.header("segredo", body);
        assertThat(header).isEqualTo("sha256=" + HexOf.hmac("segredo", body));
        assertThat(WebhookSignature.matches("segredo", body, header)).isTrue();
        assertThat(WebhookSignature.matches("segredo", body, header.toUpperCase().replace("SHA256=", "sha256="))).isTrue();
        assertThat(WebhookSignature.matches("outro", body, header)).isFalse();
        assertThat(WebhookSignature.matches("segredo", "{}".getBytes(StandardCharsets.UTF_8), header)).isFalse();
        assertThat(WebhookSignature.matches("segredo", body, header.substring(7))).isFalse();
        assertThat(WebhookSignature.matches("segredo", body, "sha256=xyz")).isFalse();
        assertThat(WebhookSignature.matches("segredo", body, "sha256=")).isFalse();
        assertThat(WebhookSignature.matches("segredo", body, null)).isFalse();
        assertThat(WebhookSignature.matches("", body, header)).isFalse();
        assertThat(WebhookSignature.matches(null, body, header)).isFalse();
        assertThat(WebhookSignature.matches("segredo", null, header)).isFalse();
        assertThat(WebhookSignature.sameSecret("token", "token")).isTrue();
        assertThat(WebhookSignature.sameSecret("token", "tokem")).isFalse();
        assertThat(WebhookSignature.sameSecret("token", null)).isFalse();
        assertThat(WebhookSignature.sameSecret("", "")).isFalse();
        assertThat(WebhookSignature.sameSecret(null, "x")).isFalse();
    }

    @Test
    void templateParametersFitOneLineEach() {
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
        var parameters = WhatsAppSummaryTemplate.parameters(summary, LocalTime.of(9, 0), "https://x/l/1");
        assertThat(parameters).hasSize(WhatsAppSummaryTemplate.PARAMETERS);
        assertThat(parameters.get(0)).isEqualTo("05/10/2026, 09:00");
        assertThat(parameters.get(1)).isEqualTo("7 contas, total R$ 1.841,00, 1 atrasada (1 estimada: R$ 300,00)");
        assertThat(parameters.get(2)).isEqualTo("ATRASADA 03/10 Aluguel do apartamento grande R$ 1.500,00; "
                + "05/10 Geladeira (2/10) R$ 300,00 (estimada); 06/10 " + "x".repeat(39) + "… R$ 1,00; "
                + "08/10 Conta 0 R$ 10,00; 08/10 Conta 1 R$ 10,00; e mais 2 contas");
        assertThat(parameters.get(3)).isEqualTo("https://x/l/1");
        assertThat(parameters).allSatisfy(p -> assertThat(p).doesNotContain("\n", "\t", "    "));

        var one = ReminderSummary.compose(TODAY, ReminderSlot.FIRST, List.of(ReminderItem.expense(UUID.randomUUID(),
                "ONE_OFF", "Luz", new BigDecimal("120.00"), TODAY.minusDays(1), true, null, null))).orElseThrow();
        var single = WhatsAppSummaryTemplate.parameters(one, LocalTime.of(18, 0), "l");
        assertThat(single.get(1)).isEqualTo("1 conta, total R$ 120,00, 1 atrasada (1 estimada: R$ 120,00)");
        assertThat(single.get(2)).isEqualTo("ATRASADA 04/10 Luz R$ 120,00 (estimada)");
        var six = new ArrayList<ReminderItem>();
        for (var i = 0; i < 6; i++)
            six.add(ReminderItem.expense(UUID.randomUUID(), "ONE_OFF", "C" + i, BigDecimal.ONE, TODAY, false, null,
                    null));
        var plural = WhatsAppSummaryTemplate.parameters(ReminderSummary.compose(TODAY, ReminderSlot.FIRST, six)
                .orElseThrow(), LocalTime.of(9, 0), "l");
        assertThat(plural.get(1)).isEqualTo("6 contas, total R$ 6,00");
        assertThat(plural.get(2)).endsWith("; e mais 1 conta");
        assertThat(WhatsAppSummaryTemplate.oneLine("x".repeat(40))).isEqualTo("x".repeat(40));
        assertThat(WhatsAppSummaryTemplate.oneLine("x".repeat(41))).isEqualTo("x".repeat(39) + "…");
    }

    @Test
    void theWindowFailureHasItsOwnMessage() {
        assertThat(WhatsAppFailureReason.fromCode("NOT_SENT_IN_WINDOW")).contains(
                WhatsAppFailureReason.NOT_SENT_IN_WINDOW);
        assertThat(WhatsAppFailureReason.NOT_SENT_IN_WINDOW.message()).contains("não será enviado depois");
    }

    /** Independent HMAC for the reference value (plain JDK, no project code). */
    static final class HexOf {
        static String hmac(String key, byte[] body) {
            try {
                var mac = javax.crypto.Mac.getInstance("HmacSHA256");
                mac.init(new javax.crypto.spec.SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
                return java.util.HexFormat.of().formatHex(mac.doFinal(body));
            } catch (java.security.GeneralSecurityException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
