package com.malyah.accountmanager.notifications.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

/**
 * H08.5 rules without infrastructure: retry spacing inside the window, the window end of a generated summary under
 * the current schedule, the suspended channel state and the waiting state that no webhook can move.
 * "Today" is 05/10/2026 in São Paulo: 09:00 is 12:00 UTC.
 */
class WhatsAppRetryDomainTest {
    static final ZoneId SP = ZoneId.of("America/Sao_Paulo");
    static final LocalDate TODAY = LocalDate.of(2026, 10, 5);
    static final Instant FIRST = Instant.parse("2026-10-05T12:00:00Z");
    static final Instant DEADLINE = FIRST.plus(Duration.ofHours(1));

    @Test
    void retriesAreSpacedProgressivelyAndNeverPassTheDeadline() {
        assertThat(WhatsAppRetryPolicy.MAX_ATTEMPTS).isEqualTo(5);
        var failed = FIRST.plusSeconds(10);
        assertThat(WhatsAppRetryPolicy.next(1, failed, null, DEADLINE)).contains(failed.plus(Duration.ofMinutes(1)));
        assertThat(WhatsAppRetryPolicy.next(2, failed, null, DEADLINE)).contains(failed.plus(Duration.ofMinutes(5)));
        assertThat(WhatsAppRetryPolicy.next(3, failed, null, DEADLINE)).contains(failed.plus(Duration.ofMinutes(15)));
        assertThat(WhatsAppRetryPolicy.next(4, failed, null, DEADLINE)).contains(failed.plus(Duration.ofMinutes(30)));
        assertThat(WhatsAppRetryPolicy.next(5, failed, null, DEADLINE)).isEmpty();
        assertThat(WhatsAppRetryPolicy.next(0, failed, null, DEADLINE)).isEmpty();
        // The fourth wait (30 min) after a failure at 12:30 would end at 13:00: past the window, so no retry.
        assertThat(WhatsAppRetryPolicy.next(4, FIRST.plus(Duration.ofMinutes(30)), null, DEADLINE)).isEmpty();
        // Exactly at the deadline is already too late.
        assertThat(WhatsAppRetryPolicy.next(1, DEADLINE.minus(Duration.ofMinutes(1)), null, DEADLINE)).isEmpty();
    }

    @Test
    void aLongerWaitAskedByTheProviderIsHonoredOnlyInsideTheWindow() {
        var failed = FIRST.plusSeconds(10);
        assertThat(WhatsAppRetryPolicy.next(1, failed, Duration.ofMinutes(10), DEADLINE))
                .contains(failed.plus(Duration.ofMinutes(10)));
        assertThat(WhatsAppRetryPolicy.next(2, failed, Duration.ofSeconds(30), DEADLINE))
                .contains(failed.plus(Duration.ofMinutes(5)));
        assertThat(WhatsAppRetryPolicy.next(1, failed, Duration.ofMinutes(70), DEADLINE)).isEmpty();
    }

    @Test
    void theWindowOfAGeneratedSummaryEndsAtOneHourOrAtTheNextSlotOfTheCurrentSchedule() {
        assertThat(ReminderWindow.deadlineOf(ReminderSchedule.DEFAULT, SP, TODAY, ReminderSlot.FIRST, FIRST))
                .isEqualTo(DEADLINE);
        // The second slot moved to 09:30 after the summary was generated: the window closes before it.
        assertThat(ReminderWindow.deadlineOf(ReminderSchedule.parse("09:00", "09:30"), SP, TODAY,
                ReminderSlot.FIRST, FIRST)).isEqualTo(FIRST.plus(Duration.ofMinutes(30)));
        // The first slot moved later: never past one hour after the original instant.
        assertThat(ReminderWindow.deadlineOf(ReminderSchedule.parse("11:00", "18:00"), SP, TODAY,
                ReminderSlot.FIRST, FIRST)).isEqualTo(DEADLINE);
        // A schedule whose next slot is not after the original instant keeps the one-hour limit.
        assertThat(ReminderWindow.deadlineOf(ReminderSchedule.parse("07:00", "08:00"), SP, TODAY,
                ReminderSlot.FIRST, FIRST)).isEqualTo(DEADLINE);
        // Second slot: the first slot of the next day comes after one hour.
        var second = Instant.parse("2026-10-05T21:00:00Z");
        assertThat(ReminderWindow.deadlineOf(ReminderSchedule.DEFAULT, SP, TODAY, ReminderSlot.SECOND, second))
                .isEqualTo(second.plus(Duration.ofHours(1)));
    }

    @Test
    void aSuspendedChannelIsNotReadyNorMerelyDisabled() {
        assertThat(WhatsAppChannelState.of(true, true, false, true, true)).isEqualTo(WhatsAppChannelState.SUSPENDED);
        assertThat(WhatsAppChannelState.of(true, true, false, false, true)).isEqualTo(WhatsAppChannelState.DISABLED);
        assertThat(WhatsAppChannelState.of(true, false, false, true, true))
                .isEqualTo(WhatsAppChannelState.CONSENT_REQUIRED);
        assertThat(WhatsAppChannelState.of(false, false, false, true, true))
                .isEqualTo(WhatsAppChannelState.RECIPIENT_REQUIRED);
        assertThat(WhatsAppChannelState.of(true, true, true, false, true)).isEqualTo(WhatsAppChannelState.READY);
        assertThat(WhatsAppChannelState.of(true, true, true, true, false))
                .isEqualTo(WhatsAppChannelState.PROVIDER_UNAVAILABLE);
    }

    @Test
    void aWaitingRetryIsNeverMovedByTheWebhookNorFinal() {
        for (var next : WhatsAppDeliveryStatus.values())
            assertThat(WhatsAppDeliveryStatus.RETRY_WAITING.canAdvanceTo(next)).isFalse();
        assertThat(WhatsAppDeliveryStatus.RETRY_WAITING.webhook()).isFalse();
        assertThat(WhatsAppDeliveryStatus.UNCERTAIN.canAdvanceTo(WhatsAppDeliveryStatus.SENT)).isTrue();
        assertThat(WhatsAppDeliveryStatus.UNCERTAIN.canAdvanceTo(WhatsAppDeliveryStatus.FAILED)).isTrue();
    }

    @Test
    void suspensionReasonsCarryTheCorrectionAndUnknownCodesAreNotShown() {
        assertThat(WhatsAppSuspensionReason.fromCode("RECIPIENT_INVALID"))
                .contains(WhatsAppSuspensionReason.RECIPIENT_INVALID);
        assertThat(WhatsAppSuspensionReason.fromCode("X")).isEmpty();
        assertThat(WhatsAppSuspensionReason.fromCode(null)).isEmpty();
        assertThat(WhatsAppSuspensionReason.RECIPIENT_INVALID.guidance()).contains("reative o canal");
        assertThat(WhatsAppSuspensionReason.PROVIDER_REJECTED.guidance()).contains("configuração do servidor");
        assertThat(WhatsAppFailureReason.RECIPIENT_UNREACHABLE.message()).contains("suspenso");
    }
}
