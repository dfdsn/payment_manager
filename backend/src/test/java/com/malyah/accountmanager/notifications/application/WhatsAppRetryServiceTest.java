package com.malyah.accountmanager.notifications.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.malyah.accountmanager.identity.application.AuthenticatedUserContextQuery;
import com.malyah.accountmanager.notifications.application.StoredSummary.Channel;
import com.malyah.accountmanager.notifications.application.StoredSummary.ChannelStatus;
import com.malyah.accountmanager.notifications.application.StoredSummary.ChannelType;
import com.malyah.accountmanager.notifications.application.WhatsAppDeliveryService.NotDue;
import com.malyah.accountmanager.notifications.application.WhatsAppDeliveryService.NotPlanned;
import com.malyah.accountmanager.notifications.application.WhatsAppDeliveryService.Ready;
import com.malyah.accountmanager.notifications.application.WhatsAppDeliveryService.Skipped;
import com.malyah.accountmanager.notifications.application.WhatsAppDeliveryService.StatusUpdate;
import com.malyah.accountmanager.notifications.application.port.MemberNotificationRepository;
import com.malyah.accountmanager.notifications.application.port.ReminderSettingsRepository;
import com.malyah.accountmanager.notifications.application.port.ReminderSummaryRepository;
import com.malyah.accountmanager.notifications.application.port.WhatsAppDeliveryRepository;
import com.malyah.accountmanager.notifications.application.port.WhatsAppDeliveryRepository.DeliveryRef;
import com.malyah.accountmanager.notifications.application.port.WhatsAppDeliveryRepository.PlannedSummary;
import com.malyah.accountmanager.notifications.application.port.WhatsAppDeliveryRepository.RetryingDelivery;
import com.malyah.accountmanager.notifications.application.port.WhatsAppProviderStatus;
import com.malyah.accountmanager.notifications.application.port.WhatsAppSender;
import com.malyah.accountmanager.notifications.application.port.WhatsAppSender.Outcome;
import com.malyah.accountmanager.notifications.application.port.WhatsAppSender.SendResult;
import com.malyah.accountmanager.notifications.domain.ReminderItem;
import com.malyah.accountmanager.notifications.domain.ReminderSchedule;
import com.malyah.accountmanager.notifications.domain.ReminderSlot;
import com.malyah.accountmanager.notifications.domain.ReminderSummary;
import com.malyah.accountmanager.notifications.domain.WhatsAppDeliveryStatus;
import com.malyah.accountmanager.notifications.domain.WhatsAppFailureReason;
import com.malyah.accountmanager.notifications.domain.WhatsAppRecipient;
import com.malyah.accountmanager.notifications.domain.WhatsAppSkipReason;
import com.malyah.accountmanager.notifications.domain.WhatsAppSuspensionReason;

/** H08.5 orchestration (retry, expiry, suspension, reconciliation) with mocked ports; PostgreSQL in the IT. */
class WhatsAppRetryServiceTest {
    static final UUID SPACE = UUID.fromString("b0000000-0000-0000-0000-000000000001");
    static final UUID ADMIN = UUID.fromString("b0000000-0000-0000-0000-000000000002");
    static final UUID GUEST = UUID.fromString("b0000000-0000-0000-0000-000000000003");
    static final UUID SUMMARY = UUID.fromString("b0000000-0000-0000-0000-0000000000aa");
    static final UUID DELIVERY = UUID.fromString("b0000000-0000-0000-0000-0000000000dd");
    static final UUID CONSENT = UUID.fromString("b0000000-0000-0000-0000-0000000000cc");
    static final UUID ATTEMPT = UUID.fromString("b0000000-0000-0000-0000-0000000000a1");
    static final LocalDate TODAY = LocalDate.of(2026, 10, 5);
    static final Instant FIRST = Instant.parse("2026-10-05T12:00:00Z");
    static final Instant NEXT = FIRST.plus(Duration.ofMinutes(6));
    static final WhatsAppRecipient NUMBER = WhatsAppRecipient.parse("(11) 98765-4321");
    static final PlannedSummary PLANNED = new PlannedSummary(SUMMARY, SPACE, ADMIN, TODAY, ReminderSlot.FIRST,
            LocalTime.of(9, 0), "America/Sao_Paulo", FIRST);
    static final ReminderItem LUZ = ReminderItem.expense(UUID.fromString("b0000000-0000-0000-0000-0000000000e1"),
            "ONE_OFF", "Luz", new BigDecimal("120.00"), TODAY, false, null, null);
    static final ReminderItem AGUA = ReminderItem.expense(UUID.fromString("b0000000-0000-0000-0000-0000000000e2"),
            "ONE_OFF", "Água", new BigDecimal("80.00"), TODAY, false, null, null);

    WhatsAppDeliveryRepository deliveries;
    ReminderSummaryRepository summaries;
    ReminderSettingsRepository settings;
    MemberNotificationRepository notifications;
    ReminderSummaryService composer;
    WhatsAppProviderStatus provider;
    WhatsAppDeliveryService service;
    StoredReminderSettings current;

    @BeforeEach
    void setUp() {
        deliveries = mock(WhatsAppDeliveryRepository.class);
        summaries = mock(ReminderSummaryRepository.class);
        settings = mock(ReminderSettingsRepository.class);
        notifications = mock(MemberNotificationRepository.class);
        composer = mock(ReminderSummaryService.class);
        provider = mock(WhatsAppProviderStatus.class);
        service = new WhatsAppDeliveryService(deliveries, summaries, settings, notifications, composer, provider,
                mock(AuthenticatedUserContextQuery.class), Clock.fixed(NEXT, ZoneOffset.UTC), () -> ATTEMPT);
        current = new StoredReminderSettings(SPACE, ReminderSchedule.DEFAULT, NUMBER, true, 3, FIRST);
        var retrying = Optional.of(new RetryingDelivery(DELIVERY, PLANNED, 1, NEXT));
        when(deliveries.retrying(DELIVERY, false)).thenReturn(retrying);
        when(deliveries.retrying(DELIVERY, true)).thenReturn(retrying);
        when(settings.lock(eq(SPACE), any())).thenAnswer(invocation -> current);
        when(settings.find(SPACE)).thenAnswer(invocation -> Optional.of(current));
        when(settings.update(any(), any())).thenReturn(true);
        when(summaries.activeAdministrator(SPACE)).thenReturn(Optional.of(ADMIN));
        when(settings.activeConsent(SPACE)).thenReturn(Optional.of(consent(ADMIN, NUMBER)));
        when(provider.availability()).thenReturn(new WhatsAppProviderStatus.Availability(true, "PROVIDER_READY", "ok"));
        when(summaries.find(SPACE, SUMMARY)).thenReturn(Optional.of(stored(LUZ, AGUA)));
        when(composer.compose(SPACE, TODAY, ReminderSlot.FIRST)).thenReturn(ReminderSummary.compose(TODAY,
                ReminderSlot.FIRST, List.of(LUZ, AGUA)));
        when(composer.link(SUMMARY)).thenReturn("https://contas.example/lembretes/resumos/" + SUMMARY);
        when(deliveries.startRetry(any(), any(), any(), anyInt(), any())).thenReturn(true);
        when(deliveries.closeRetry(any(), any(), any(), any(), any())).thenReturn(true);
        when(deliveries.finish(any(), any(), any(), any(), any(), any(), any())).thenReturn(true);
        when(deliveries.retryLater(any(), any(), any(), any(), any(), any())).thenReturn(true);
    }

    @Test
    void aDueRetryIsTheNextAttemptOfTheSameDeliveryWithTheContentRecomposedNow() {
        // Água was paid and Luz changed value while the retry waited.
        var luzNow = ReminderItem.expense(LUZ.expenseId(), "ONE_OFF", "Luz", new BigDecimal("150.00"), TODAY, false,
                null, null);
        when(composer.compose(SPACE, TODAY, ReminderSlot.FIRST)).thenReturn(ReminderSummary.compose(TODAY,
                ReminderSlot.FIRST, List.of(luzNow)));
        var ready = (Ready) service.prepareRetry(DELIVERY, NEXT);
        assertThat(ready.attemptNumber()).isEqualTo(2);
        assertThat(ready.deliveryId()).isEqualTo(DELIVERY);
        assertThat(ready.summaryId()).isEqualTo(SUMMARY);
        assertThat(ready.consentId()).isEqualTo(CONSENT);
        assertThat(ready.message().reference()).isEqualTo(ATTEMPT.toString());
        assertThat(ready.message().parameters()).containsExactly("05/10/2026, 09:00", "1 conta, total R$ 150,00",
                "05/10 Luz R$ 150,00", "https://contas.example/lembretes/resumos/" + SUMMARY);
        verify(deliveries).startRetry(DELIVERY, CONSENT, "4321", 1, NEXT);
        verify(deliveries).insertAttempt(ATTEMPT, DELIVERY, 2, NEXT);
        verify(notifications, never()).recordWhatsAppFailure(any(), any(), any(), any());
    }

    @Test
    void aValidRetryBeforeItsInstantOnlyKeepsWaiting() {
        assertThat(service.prepareRetry(DELIVERY, NEXT.minusSeconds(1))).isEqualTo(new NotDue(NEXT));
        verify(deliveries, never()).startRetry(any(), any(), any(), anyInt(), any());
        verify(deliveries, never()).closeRetry(any(), any(), any(), any(), any());
        verify(composer, never()).compose(any(), any(), any());
    }

    @Test
    void aRetryNoLongerWaitingOrAlreadyTakenIsNotPlanned() {
        when(deliveries.retrying(DELIVERY, true)).thenReturn(Optional.empty());
        assertThat(service.prepareRetry(DELIVERY, NEXT)).isEqualTo(new NotPlanned());
        when(deliveries.retrying(DELIVERY, false)).thenReturn(Optional.empty());
        assertThat(service.prepareRetry(DELIVERY, NEXT)).isEqualTo(new NotPlanned());
        setUp();
        when(deliveries.startRetry(any(), any(), any(), anyInt(), any())).thenReturn(false);
        assertThat(service.prepareRetry(DELIVERY, NEXT)).isEqualTo(new NotPlanned());
        verify(deliveries, never()).insertAttempt(any(), any(), anyInt(), any());
        assertThat(service.retrying(3)).isEmpty();
        verify(deliveries).retryingDeliveries(3);
    }

    @Test
    void anExpiredWindowClosesTheRetryAsNotSentAndTellsTheAdministrator() {
        var late = FIRST.plus(Duration.ofHours(1));
        assertThat(service.prepareRetry(DELIVERY, late)).isEqualTo(new Skipped(WhatsAppSkipReason.WINDOW_CLOSED));
        verify(deliveries).closeRetry(DELIVERY, WhatsAppDeliveryStatus.FAILED, null, "NOT_SENT_IN_WINDOW", late);
        verify(notifications).recordWhatsAppFailure(SPACE, SUMMARY, WhatsAppFailureReason.NOT_SENT_IN_WINDOW, late);
        verify(deliveries, never()).insertAttempt(any(), any(), anyInt(), any());

        // The next slot moved to 09:30: closed at 09:30 even though its instant has not come.
        setUp();
        current = new StoredReminderSettings(SPACE, ReminderSchedule.parse("09:00", "09:30"), NUMBER, true, 4, FIRST);
        when(deliveries.retrying(DELIVERY, true)).thenReturn(Optional.of(new RetryingDelivery(DELIVERY, PLANNED, 3,
                FIRST.plus(Duration.ofMinutes(40)))));
        var nextSlot = FIRST.plus(Duration.ofMinutes(30));
        assertThat(service.prepareRetry(DELIVERY, nextSlot)).isEqualTo(new Skipped(WhatsAppSkipReason.WINDOW_CLOSED));
        verify(deliveries).closeRetry(DELIVERY, WhatsAppDeliveryStatus.FAILED, null, "NOT_SENT_IN_WINDOW", nextSlot);

        // Already closed by another worker: nobody is told twice.
        setUp();
        when(deliveries.closeRetry(any(), any(), any(), any(), any())).thenReturn(false);
        service.prepareRetry(DELIVERY, late);
        verify(notifications, never()).recordWhatsAppFailure(any(), any(), any(), any());
    }

    @Test
    void eachRevalidationFailureClosesTheRetryWithItsReasonAndNoCall() {
        assertClosed(() -> current = new StoredReminderSettings(SPACE, ReminderSchedule.DEFAULT, NUMBER, false, 4,
                FIRST), WhatsAppSkipReason.DISABLED);
        assertClosed(() -> current = new StoredReminderSettings(SPACE, ReminderSchedule.DEFAULT, NUMBER, false, 4,
                FIRST, WhatsAppSuspensionReason.RECIPIENT_INVALID, FIRST), WhatsAppSkipReason.SUSPENDED);
        assertClosed(() -> when(settings.activeConsent(SPACE)).thenReturn(Optional.empty()),
                WhatsAppSkipReason.CONSENT_REVOKED);
        assertClosed(() -> when(summaries.activeAdministrator(SPACE)).thenReturn(Optional.of(GUEST)),
                WhatsAppSkipReason.ADMINISTRATOR_CHANGED);
        assertClosed(() -> current = new StoredReminderSettings(SPACE, ReminderSchedule.DEFAULT,
                WhatsAppRecipient.parse("(21) 99876-5432"), true, 4, FIRST), WhatsAppSkipReason.RECIPIENT_CHANGED);
        assertClosed(() -> when(composer.compose(SPACE, TODAY, ReminderSlot.FIRST)).thenReturn(Optional.empty()),
                WhatsAppSkipReason.EMPTY);
        verify(notifications, never()).recordWhatsAppFailure(any(), any(), any(), any());

        assertClosed(() -> when(provider.availability()).thenReturn(new WhatsAppProviderStatus.Availability(false,
                "PROVIDER_DISABLED", "x")), WhatsAppSkipReason.PROVIDER_UNAVAILABLE);
        verify(notifications).recordWhatsAppFailure(SPACE, SUMMARY, WhatsAppFailureReason.PROVIDER_UNAVAILABLE, NEXT);
        // A disabled channel closes the retry even before its instant.
        setUp();
        current = new StoredReminderSettings(SPACE, ReminderSchedule.DEFAULT, NUMBER, false, 4, FIRST);
        assertThat(service.prepareRetry(DELIVERY, NEXT.minusSeconds(60)))
                .isEqualTo(new Skipped(WhatsAppSkipReason.DISABLED));
    }

    @Test
    void aTransientFailureWaitsForTheNextAttemptInsideTheWindowWithoutAnyNotice() {
        var failedAt = FIRST.plusSeconds(10);
        var status = service.record(ready(1), new SendResult(Outcome.UNAVAILABLE, null, "130429", null), failedAt);
        assertThat(status).isEqualTo(WhatsAppDeliveryStatus.RETRY_WAITING);
        verify(deliveries).retryLater(DELIVERY, ATTEMPT, failedAt.plus(Duration.ofMinutes(1)), "130429",
                "PROVIDER_UNAVAILABLE", failedAt);
        verify(deliveries, never()).finish(any(), any(), any(), any(), any(), any(), any());
        verify(notifications, never()).recordWhatsAppFailure(any(), any(), any(), any());
        verify(settings, never()).update(any(), any());

        // The provider asked to wait ten minutes.
        setUp();
        service.record(ready(2), new SendResult(Outcome.UNAVAILABLE, null, "429", Duration.ofMinutes(10)), failedAt);
        verify(deliveries).retryLater(DELIVERY, ATTEMPT, failedAt.plus(Duration.ofMinutes(10)), "429",
                "PROVIDER_UNAVAILABLE", failedAt);
    }

    @Test
    void whenNoRetryFitsTheTransientFailureExpiresAsNotSentInTheWindow() {
        var failedAt = FIRST.plus(Duration.ofMinutes(50));
        assertThat(service.record(ready(4), SendResult.of(Outcome.UNAVAILABLE, "429"), failedAt))
                .isEqualTo(WhatsAppDeliveryStatus.FAILED);
        verify(deliveries).finish(DELIVERY, ATTEMPT, WhatsAppDeliveryStatus.FAILED, null, "429",
                "NOT_SENT_IN_WINDOW", failedAt);
        verify(notifications).recordWhatsAppFailure(SPACE, SUMMARY, WhatsAppFailureReason.NOT_SENT_IN_WINDOW,
                failedAt);
        verify(deliveries, never()).retryLater(any(), any(), any(), any(), any(), any());

        setUp();
        assertThat(service.record(ready(5), SendResult.of(Outcome.UNAVAILABLE, "429"), FIRST.plusSeconds(60)))
                .isEqualTo(WhatsAppDeliveryStatus.FAILED);
        verify(deliveries, never()).retryLater(any(), any(), any(), any(), any(), any());
    }

    @Test
    void uncertainResultsAreNeverRetriedAndNeverSuspend() {
        assertThat(service.record(ready(2), SendResult.of(Outcome.UNCERTAIN, null), NEXT))
                .isEqualTo(WhatsAppDeliveryStatus.UNCERTAIN);
        verify(deliveries, never()).retryLater(any(), any(), any(), any(), any(), any());
        verify(notifications).recordWhatsAppFailure(SPACE, SUMMARY, WhatsAppFailureReason.RESULT_UNCERTAIN, NEXT);
        verify(settings, never()).update(any(), any());
    }

    @Test
    void permanentFailuresSuspendTheChannelThatFailedKeepingNumberAndConsent() {
        service.record(ready(1), SendResult.of(Outcome.RECIPIENT_INVALID, "131026"), NEXT);
        var saved = ArgumentCaptor.forClass(StoredReminderSettings.class);
        verify(settings).update(saved.capture(), isNull());
        assertThat(saved.getValue().enabled()).isFalse();
        assertThat(saved.getValue().suspension()).isEqualTo(WhatsAppSuspensionReason.RECIPIENT_INVALID);
        assertThat(saved.getValue().suspendedAt()).isEqualTo(NEXT);
        assertThat(saved.getValue().recipient()).isEqualTo(NUMBER);
        assertThat(saved.getValue().version()).isEqualTo(4);
        var event = ArgumentCaptor.forClass(SettingsEvent.class);
        verify(settings).appendEvent(event.capture());
        assertThat(event.getValue().type()).isEqualTo(SettingsEvent.Type.CHANNEL_SUSPENDED);
        assertThat(event.getValue().actorId()).isNull();
        assertThat(event.getValue().detail()).isEqualTo("RECIPIENT_INVALID");
        verify(settings, never()).revokeConsent(any(), any(), any(), any());
        verify(notifications).recordWhatsAppFailure(SPACE, SUMMARY, WhatsAppFailureReason.RECIPIENT_INVALID, NEXT);

        setUp();
        service.record(ready(1), SendResult.of(Outcome.REJECTED, "132001"), NEXT);
        verify(settings).update(saved.capture(), isNull());
        assertThat(saved.getValue().suspension()).isEqualTo(WhatsAppSuspensionReason.PROVIDER_REJECTED);
    }

    @Test
    void aConfigurationAlreadyChangedOrSuspendedIsNotSuspendedAgain() {
        when(settings.activeConsent(SPACE)).thenReturn(Optional.of(new StoredConsent(UUID.randomUUID(), ADMIN,
                "Admin", NUMBER, "V1", FIRST)));
        service.record(ready(1), SendResult.of(Outcome.RECIPIENT_INVALID, "131026"), NEXT);
        verify(settings, never()).update(any(), any());

        setUp();
        current = new StoredReminderSettings(SPACE, ReminderSchedule.DEFAULT, NUMBER, false, 4, FIRST,
                WhatsAppSuspensionReason.PROVIDER_REJECTED, FIRST);
        service.record(ready(1), SendResult.of(Outcome.RECIPIENT_INVALID, "131026"), NEXT);
        verify(settings, never()).update(any(), any());

        setUp();
        when(settings.activeConsent(SPACE)).thenReturn(Optional.empty());
        service.record(ready(1), SendResult.of(Outcome.REJECTED, "1"), NEXT);
        setUp();
        current = new StoredReminderSettings(SPACE, ReminderSchedule.DEFAULT, null, false, 4, FIRST);
        service.record(ready(1), SendResult.of(Outcome.REJECTED, "1"), NEXT);
        setUp();
        when(settings.activeConsent(SPACE)).thenReturn(Optional.of(new StoredConsent(CONSENT, ADMIN, "Admin",
                WhatsAppRecipient.parse("(21) 99876-5432"), "V1", FIRST)));
        service.record(ready(1), SendResult.of(Outcome.REJECTED, "1"), NEXT);
        verify(settings, never()).update(any(), any());
        setUp();
        service.record(new Ready(DELIVERY, ATTEMPT, SPACE, SUMMARY, ready(1).message()),
                SendResult.of(Outcome.REJECTED, "1"), NEXT);
        verify(settings, never()).update(any(), any());

        setUp();
        when(settings.update(any(), any())).thenReturn(false);
        assertThatThrownBy(() -> service.record(ready(1), SendResult.of(Outcome.REJECTED, "1"), NEXT))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theWebhookReconcilesAnUncertainAttemptByItsReferenceAndNeverAWaitingOne() {
        var at = NEXT.plusSeconds(5);
        when(deliveries.lockByAttempt(ATTEMPT)).thenReturn(Optional.of(new DeliveryRef(DELIVERY, SPACE, SUMMARY,
                WhatsAppDeliveryStatus.UNCERTAIN, CONSENT, false)));
        when(deliveries.reconcile(DELIVERY, ATTEMPT, "wamid.LOST", NEXT)).thenReturn(true);
        when(deliveries.recordEvent(any(), any(), any(), any(), any(), any())).thenReturn(true);
        var outcome = service.applyStatuses(List.of(new StatusUpdate("wamid.LOST", "delivered", at, null,
                ATTEMPT.toString(), false)), NEXT);
        assertThat(outcome.reconciled()).isOne();
        assertThat(outcome.applied()).isOne();
        verify(deliveries).confirm(DELIVERY, WhatsAppDeliveryStatus.DELIVERED, at, null, true, NEXT);

        setUp();
        when(deliveries.lockByAttempt(ATTEMPT)).thenReturn(Optional.of(new DeliveryRef(DELIVERY, SPACE, SUMMARY,
                WhatsAppDeliveryStatus.ATTEMPTING, CONSENT, false)));
        var pending = service.applyStatuses(List.of(new StatusUpdate("wamid.NEW", "sent", at, null,
                ATTEMPT.toString(), false)), NEXT);
        assertThat(pending.retryLater()).isTrue();
        verify(deliveries, never()).reconcile(any(), any(), any(), any());

        setUp();
        // A waiting retry was certainly not received: a status naming it is not trusted to reconcile anything.
        when(deliveries.lockByAttempt(ATTEMPT)).thenReturn(Optional.of(new DeliveryRef(DELIVERY, SPACE, SUMMARY,
                WhatsAppDeliveryStatus.RETRY_WAITING, CONSENT, false)));
        var ignored = service.applyStatuses(List.of(new StatusUpdate("wamid.Y", "sent", at, null, ATTEMPT.toString(),
                false), new StatusUpdate("wamid.Z", "sent", at, null, "not-a-reference", false),
                new StatusUpdate("wamid.W", "sent", at, null, "b0000000-0000-0000-0000-00000000zzzz", false)), NEXT);
        assertThat(ignored.ignored()).isEqualTo(3);
        assertThat(ignored.reconciled()).isZero();
        verify(deliveries, never()).reconcile(any(), any(), any(), any());
    }

    @Test
    void aReportedRecipientFailureSuspendsAndOtherFailuresDoNot() {
        var at = NEXT.plusSeconds(5);
        when(deliveries.lockByProviderMessageId("wamid.A")).thenReturn(Optional.of(new DeliveryRef(DELIVERY, SPACE,
                SUMMARY, WhatsAppDeliveryStatus.ACCEPTED, CONSENT, true)));
        when(deliveries.recordEvent(any(), any(), any(), any(), any(), any())).thenReturn(true);
        service.applyStatuses(List.of(new StatusUpdate("wamid.A", "failed", at, "131026", null, true)), NEXT);
        verify(notifications).recordWhatsAppFailure(SPACE, SUMMARY, WhatsAppFailureReason.RECIPIENT_UNREACHABLE, NEXT);
        verify(settings).update(any(), isNull());

        setUp();
        when(deliveries.lockByProviderMessageId("wamid.A")).thenReturn(Optional.of(new DeliveryRef(DELIVERY, SPACE,
                SUMMARY, WhatsAppDeliveryStatus.ACCEPTED, CONSENT, true)));
        when(deliveries.recordEvent(any(), any(), any(), any(), any(), any())).thenReturn(true);
        service.applyStatuses(List.of(new StatusUpdate("wamid.A", "failed", at, "131049", null, false)), NEXT);
        verify(notifications).recordWhatsAppFailure(SPACE, SUMMARY, WhatsAppFailureReason.DELIVERY_FAILED, NEXT);
        verify(settings, never()).update(any(), any());
    }

    @Test
    void theAdministratorSeesTheWaitingRetrySuspensionAndReconciliation() {
        var waiting = WhatsAppDeliveryService.view(new StoredDelivery(DELIVERY, SPACE, WhatsAppSender.Kind.SUMMARY,
                SUMMARY, WhatsAppDeliveryStatus.RETRY_WAITING, null, "PROVIDER_UNAVAILABLE", "429", "4321", 2, FIRST,
                FIRST, null, null, null, null, null, List.of(new StoredDelivery.Attempt(1, FIRST, FIRST, "FAILED")),
                NEXT, null));
        assertThat(waiting.state()).isEqualTo("RETRY_WAITING");
        assertThat(waiting.stateMessage()).contains("nova tentativa");
        assertThat(waiting.nextAttemptAt()).isEqualTo(NEXT);
        assertThat(waiting.reasonMessage()).isEqualTo(WhatsAppFailureReason.PROVIDER_UNAVAILABLE.message());
        var suspended = WhatsAppDeliveryService.view(new StoredDelivery(DELIVERY, SPACE, WhatsAppSender.Kind.SUMMARY,
                SUMMARY, WhatsAppDeliveryStatus.SKIPPED, "SUSPENDED", null, null, null, null, FIRST, null, null, null,
                null, null, null, List.of()));
        assertThat(suspended.reasonMessage()).contains("suspenso");
        var reconciled = WhatsAppDeliveryService.view(new StoredDelivery(DELIVERY, SPACE, WhatsAppSender.Kind.SUMMARY,
                SUMMARY, WhatsAppDeliveryStatus.DELIVERED, null, "RESULT_UNCERTAIN", null, "4321", 2, FIRST, FIRST,
                null, null, NEXT, null, null, List.of(), null, NEXT));
        assertThat(reconciled.reconciledAt()).isEqualTo(NEXT);
        assertThat(WhatsAppDeliveryService.CHANNEL_MESSAGES.get("SUSPENDED")).contains("suspenso");
    }

    private void assertClosed(Runnable arrange, WhatsAppSkipReason reason) {
        setUp();
        arrange.run();
        assertThat(service.prepareRetry(DELIVERY, NEXT)).isEqualTo(new Skipped(reason));
        verify(deliveries).closeRetry(DELIVERY, WhatsAppDeliveryStatus.SKIPPED, reason.name(), null, NEXT);
        verify(deliveries, never()).startRetry(any(), any(), any(), anyInt(), any());
        verify(deliveries, never()).insertAttempt(any(), any(), anyInt(), any());
    }

    private Ready ready(int attempt) {
        return new Ready(DELIVERY, ATTEMPT, SPACE, SUMMARY, new WhatsAppSender.Message(WhatsAppSender.Kind.SUMMARY,
                NUMBER.e164(), List.of(), ATTEMPT.toString()), attempt, CONSENT, PLANNED);
    }

    private static StoredConsent consent(UUID user, WhatsAppRecipient recipient) {
        return new StoredConsent(CONSENT, user, "Admin", recipient, "V1", FIRST.minusSeconds(3600));
    }

    private static StoredSummary stored(ReminderItem... items) {
        return new StoredSummary(SUMMARY, SPACE, LocalTime.of(9, 0), "America/Sao_Paulo", FIRST, FIRST,
                ReminderSummary.compose(TODAY, ReminderSlot.FIRST, List.of(items)).orElseThrow(), List.of(
                        new Channel(ChannelType.IN_APP, ChannelStatus.PLANNED, null, null),
                        new Channel(ChannelType.WHATSAPP, ChannelStatus.PLANNED, null, ADMIN)));
    }
}
