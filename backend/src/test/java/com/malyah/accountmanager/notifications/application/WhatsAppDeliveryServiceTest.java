package com.malyah.accountmanager.notifications.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
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

import com.malyah.accountmanager.identity.application.AuthenticatedUserContext;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContextQuery;
import com.malyah.accountmanager.identity.domain.SpaceRole;
import com.malyah.accountmanager.notifications.application.StoredSummary.Channel;
import com.malyah.accountmanager.notifications.application.StoredSummary.ChannelStatus;
import com.malyah.accountmanager.notifications.application.StoredSummary.ChannelType;
import com.malyah.accountmanager.notifications.application.WhatsAppDeliveryService.ExistingTest;
import com.malyah.accountmanager.notifications.application.WhatsAppDeliveryService.NotPlanned;
import com.malyah.accountmanager.notifications.application.WhatsAppDeliveryService.Ready;
import com.malyah.accountmanager.notifications.application.WhatsAppDeliveryService.Skipped;
import com.malyah.accountmanager.notifications.application.WhatsAppDeliveryService.StatusUpdate;
import com.malyah.accountmanager.notifications.application.port.MemberNotificationRepository;
import com.malyah.accountmanager.notifications.application.port.ReminderSettingsRepository;
import com.malyah.accountmanager.notifications.application.port.ReminderSummaryRepository;
import com.malyah.accountmanager.notifications.application.port.WhatsAppDeliveryRepository;
import com.malyah.accountmanager.notifications.application.port.WhatsAppDeliveryRepository.DeliveryRef;
import com.malyah.accountmanager.notifications.application.port.WhatsAppDeliveryRepository.NewDelivery;
import com.malyah.accountmanager.notifications.application.port.WhatsAppDeliveryRepository.PlannedSummary;
import com.malyah.accountmanager.notifications.application.port.WhatsAppDeliveryRepository.StaleDelivery;
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

/** H08.4 orchestration with mocked ports; the PostgreSQL and HTTP behavior is in WhatsAppDeliveryPostgresIT. */
class WhatsAppDeliveryServiceTest {
    static final UUID SPACE = UUID.fromString("a0000000-0000-0000-0000-000000000001");
    static final UUID ADMIN = UUID.fromString("a0000000-0000-0000-0000-000000000002");
    static final UUID GUEST = UUID.fromString("a0000000-0000-0000-0000-000000000003");
    static final UUID SUMMARY = UUID.fromString("a0000000-0000-0000-0000-0000000000aa");
    static final UUID DELIVERY = UUID.fromString("a0000000-0000-0000-0000-0000000000dd");
    static final UUID CONSENT = UUID.fromString("a0000000-0000-0000-0000-0000000000cc");
    static final LocalDate TODAY = LocalDate.of(2026, 10, 5);
    static final Instant FIRST = Instant.parse("2026-10-05T12:00:00Z");
    static final Instant NOW = FIRST.plusSeconds(30);
    static final WhatsAppRecipient NUMBER = WhatsAppRecipient.parse("(11) 98765-4321");
    static final ReminderItem LUZ = ReminderItem.expense(UUID.fromString("a0000000-0000-0000-0000-0000000000e1"),
            "ONE_OFF", "Luz", new BigDecimal("120.00"), TODAY, false, null, null);
    static final ReminderItem AGUA = ReminderItem.expense(UUID.fromString("a0000000-0000-0000-0000-0000000000e2"),
            "ONE_OFF", "Água", new BigDecimal("80.00"), TODAY, false, null, null);

    WhatsAppDeliveryRepository deliveries;
    ReminderSummaryRepository summaries;
    ReminderSettingsRepository settings;
    MemberNotificationRepository notifications;
    ReminderSummaryService composer;
    WhatsAppProviderStatus provider;
    AuthenticatedUserContextQuery contexts;
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
        contexts = mock(AuthenticatedUserContextQuery.class);
        var first = new java.util.concurrent.atomic.AtomicBoolean(true);
        service = new WhatsAppDeliveryService(deliveries, summaries, settings, notifications, composer, provider,
                contexts, Clock.fixed(NOW, ZoneOffset.UTC), () -> first.getAndSet(false) ? DELIVERY : UUID.randomUUID());
        current = new StoredReminderSettings(SPACE, ReminderSchedule.DEFAULT, NUMBER, true, 3, FIRST);
        when(deliveries.planned(SUMMARY)).thenReturn(Optional.of(new PlannedSummary(SUMMARY, SPACE, ADMIN, TODAY,
                ReminderSlot.FIRST, LocalTime.of(9, 0), "America/Sao_Paulo", FIRST)));
        when(settings.lock(SPACE, NOW)).thenAnswer(invocation -> current);
        when(summaries.activeAdministrator(SPACE)).thenReturn(Optional.of(ADMIN));
        when(settings.activeConsent(SPACE)).thenReturn(Optional.of(consent(ADMIN, NUMBER)));
        when(provider.availability()).thenReturn(new WhatsAppProviderStatus.Availability(true, "PROVIDER_READY", "ok"));
        when(provider.testTemplateConfigured()).thenReturn(true);
        when(summaries.find(SPACE, SUMMARY)).thenReturn(Optional.of(stored(LUZ, AGUA)));
        when(composer.compose(SPACE, TODAY, ReminderSlot.FIRST)).thenReturn(ReminderSummary.compose(TODAY,
                ReminderSlot.FIRST, List.of(LUZ, AGUA)));
        when(composer.link(SUMMARY)).thenReturn("https://contas.example/lembretes/resumos/" + SUMMARY);
        when(deliveries.insert(any())).thenReturn(true);
        when(contexts.findByEmail("admin@example.com")).thenReturn(actor(ADMIN, SpaceRole.ADMINISTRATOR));
        when(contexts.findByEmail("guest@example.com")).thenReturn(actor(GUEST, SpaceRole.GUEST));
    }

    @Test
    void readySummaryClaimsOneDeliveryWithTheRevalidatedContent() {
        var ready = (Ready) service.prepare(SUMMARY, NOW);
        assertThat(ready.message().recipientE164()).isEqualTo("+5511987654321");
        assertThat(ready.message().kind()).isEqualTo(WhatsAppSender.Kind.SUMMARY);
        assertThat(ready.message().parameters()).containsExactly("05/10/2026, 09:00", "2 contas, total R$ 200,00",
                "05/10 Luz R$ 120,00; 05/10 Água R$ 80,00", "https://contas.example/lembretes/resumos/" + SUMMARY);
        var inserted = ArgumentCaptor.forClass(NewDelivery.class);
        verify(deliveries).insert(inserted.capture());
        assertThat(inserted.getValue().status()).isEqualTo(WhatsAppDeliveryStatus.ATTEMPTING);
        assertThat(inserted.getValue().recipientLastDigits()).isEqualTo("4321");
        assertThat(inserted.getValue().consentId()).isEqualTo(CONSENT);
        assertThat(inserted.getValue().itemCount()).isEqualTo(2);
        verify(deliveries).insertAttempt(ready.attemptId(), DELIVERY, 1, NOW);
        verify(notifications, never()).recordWhatsAppFailure(any(), any(), any(), any());
    }

    @Test
    void onlyBillsOfTheGeneratedSummaryThatAreStillEligibleAreSent() {
        var nova = ReminderItem.expense(UUID.randomUUID(), "ONE_OFF", "Nova", new BigDecimal("5.00"), TODAY, false,
                null, null);
        when(composer.compose(SPACE, TODAY, ReminderSlot.FIRST)).thenReturn(ReminderSummary.compose(TODAY,
                ReminderSlot.FIRST, List.of(AGUA, nova)));
        var ready = (Ready) service.prepare(SUMMARY, NOW);
        assertThat(ready.message().parameters().get(1)).isEqualTo("1 conta, total R$ 80,00");
    }

    @Test
    void eachRevalidationFailureSkipsWithItsReason() {
        assertSkip(() -> when(summaries.activeAdministrator(SPACE)).thenReturn(Optional.of(GUEST)),
                WhatsAppSkipReason.ADMINISTRATOR_CHANGED);
        assertSkip(() -> when(summaries.activeAdministrator(SPACE)).thenReturn(Optional.empty()),
                WhatsAppSkipReason.ADMINISTRATOR_CHANGED);
        assertSkip(() -> when(settings.activeConsent(SPACE)).thenReturn(Optional.empty()),
                WhatsAppSkipReason.CONSENT_REVOKED);
        assertSkip(() -> when(settings.activeConsent(SPACE)).thenReturn(Optional.of(consent(GUEST, NUMBER))),
                WhatsAppSkipReason.CONSENT_REVOKED);
        assertSkip(() -> current = new StoredReminderSettings(SPACE, ReminderSchedule.DEFAULT,
                WhatsAppRecipient.parse("(21) 99876-5432"), true, 4, FIRST), WhatsAppSkipReason.RECIPIENT_CHANGED);
        assertSkip(() -> current = new StoredReminderSettings(SPACE, ReminderSchedule.DEFAULT, null, false, 4, FIRST),
                WhatsAppSkipReason.RECIPIENT_CHANGED);
        assertSkip(() -> current = new StoredReminderSettings(SPACE, ReminderSchedule.DEFAULT, NUMBER, false, 4,
                FIRST), WhatsAppSkipReason.DISABLED);
        assertSkip(() -> when(composer.compose(SPACE, TODAY, ReminderSlot.FIRST)).thenReturn(Optional.empty()),
                WhatsAppSkipReason.EMPTY);
        assertSkip(() -> when(composer.compose(SPACE, TODAY, ReminderSlot.FIRST)).thenReturn(ReminderSummary.compose(
                TODAY, ReminderSlot.FIRST, List.of(ReminderItem.expense(UUID.randomUUID(), "ONE_OFF", "Outra",
                        BigDecimal.TEN, TODAY, false, null, null)))), WhatsAppSkipReason.EMPTY);
        assertSkip(() -> when(summaries.find(SPACE, SUMMARY)).thenReturn(Optional.empty()), WhatsAppSkipReason.EMPTY);
        verify(notifications, never()).recordWhatsAppFailure(any(), any(), any(), any());
    }

    @Test
    void providerDownAndClosedWindowAreFailuresTheAdministratorSees() {
        when(provider.availability()).thenReturn(new WhatsAppProviderStatus.Availability(false, "X", "x"));
        assertThat(service.prepare(SUMMARY, NOW)).isEqualTo(new Skipped(WhatsAppSkipReason.PROVIDER_UNAVAILABLE));
        verify(notifications).recordWhatsAppFailure(SPACE, SUMMARY, WhatsAppFailureReason.PROVIDER_UNAVAILABLE, NOW);

        setUp();
        var late = FIRST.plusSeconds(3600);
        when(settings.lock(SPACE, late)).thenAnswer(invocation -> current);
        assertThat(service.prepare(SUMMARY, late)).isEqualTo(new Skipped(WhatsAppSkipReason.WINDOW_CLOSED));
        verify(notifications).recordWhatsAppFailure(SPACE, SUMMARY, WhatsAppFailureReason.NOT_SENT_IN_WINDOW, late);

        setUp();
        var early = FIRST.minusSeconds(1);
        when(settings.lock(SPACE, early)).thenAnswer(invocation -> current);
        assertThat(service.prepare(SUMMARY, early)).isEqualTo(new Skipped(WhatsAppSkipReason.WINDOW_CLOSED));
        var justInside = FIRST.plusSeconds(3599);
        when(settings.lock(SPACE, justInside)).thenAnswer(invocation -> current);
        when(deliveries.insert(any())).thenReturn(true);
        assertThat(service.prepare(SUMMARY, justInside)).isInstanceOf(Ready.class);
    }

    @Test
    void windowEndsAtTheNextSlotOfTheCurrentSchedule() {
        current = new StoredReminderSettings(SPACE, ReminderSchedule.parse("09:00", "09:30"), NUMBER, true, 4, FIRST);
        var afterNext = FIRST.plusSeconds(1800);
        when(settings.lock(SPACE, afterNext)).thenAnswer(invocation -> current);
        assertThat(service.prepare(SUMMARY, afterNext)).isEqualTo(new Skipped(WhatsAppSkipReason.WINDOW_CLOSED));
        var beforeNext = FIRST.plusSeconds(1799);
        when(settings.lock(SPACE, beforeNext)).thenAnswer(invocation -> current);
        assertThat(service.prepare(SUMMARY, beforeNext)).isInstanceOf(Ready.class);
    }

    @Test
    void alreadyHandledSummariesAreNotPlanned() {
        when(deliveries.insert(any())).thenReturn(false);
        assertThat(service.prepare(SUMMARY, NOW)).isEqualTo(new NotPlanned());
        verify(deliveries, never()).insertAttempt(any(), any(), anyInt(), any());
        when(provider.availability()).thenReturn(new WhatsAppProviderStatus.Availability(false, "X", "x"));
        assertThat(service.prepare(SUMMARY, NOW)).isEqualTo(new NotPlanned());
        verify(notifications, never()).recordWhatsAppFailure(any(), any(), any(), any());
        when(deliveries.planned(SUMMARY)).thenReturn(Optional.empty());
        assertThat(service.prepare(SUMMARY, NOW)).isEqualTo(new NotPlanned());
        assertThat(service.pending(5)).isEmpty();
        verify(deliveries).plannedSummaries(5);
    }

    @Test
    void resultsMapToStatesAndFailures() {
        record Case(SendResult result, WhatsAppDeliveryStatus status, WhatsAppFailureReason failure, String id) { }
        for (var c : List.of(
                new Case(SendResult.accepted("wamid.1"), WhatsAppDeliveryStatus.ACCEPTED, null, "wamid.1"),
                new Case(new SendResult(Outcome.ACCEPTED, " ", null), WhatsAppDeliveryStatus.UNCERTAIN,
                        WhatsAppFailureReason.RESULT_UNCERTAIN, null),
                new Case(new SendResult(Outcome.ACCEPTED, null, null), WhatsAppDeliveryStatus.UNCERTAIN,
                        WhatsAppFailureReason.RESULT_UNCERTAIN, null),
                new Case(SendResult.of(Outcome.REJECTED, "132001"), WhatsAppDeliveryStatus.REJECTED,
                        WhatsAppFailureReason.PROVIDER_REJECTED, null),
                new Case(SendResult.of(Outcome.RECIPIENT_INVALID, "131026"), WhatsAppDeliveryStatus.REJECTED,
                        WhatsAppFailureReason.RECIPIENT_INVALID, null),
                new Case(SendResult.of(Outcome.UNAVAILABLE, "429"), WhatsAppDeliveryStatus.FAILED,
                        WhatsAppFailureReason.PROVIDER_UNAVAILABLE, null),
                new Case(SendResult.of(Outcome.UNCERTAIN, null), WhatsAppDeliveryStatus.UNCERTAIN,
                        WhatsAppFailureReason.RESULT_UNCERTAIN, null))) {
            setUp();
            var ready = new Ready(DELIVERY, UUID.randomUUID(), SPACE, SUMMARY,
                    new WhatsAppSender.Message(WhatsAppSender.Kind.SUMMARY, NUMBER.e164(), List.of()));
            when(deliveries.finish(any(), any(), any(), any(), any(), any(), any())).thenReturn(true);
            assertThat(service.record(ready, c.result(), NOW)).isEqualTo(c.status());
            verify(deliveries).finish(DELIVERY, ready.attemptId(), c.status(), c.id(), c.result().errorCode(),
                    c.failure() == null ? null : c.failure().name(), NOW);
            if (c.failure() == null) verify(notifications, never()).recordWhatsAppFailure(any(), any(), any(), any());
            else verify(notifications).recordWhatsAppFailure(SPACE, SUMMARY, c.failure(), NOW);
        }
    }

    @Test
    void aResultAlreadyRecordedOrOfATestNotifiesNobody() {
        var ready = new Ready(DELIVERY, UUID.randomUUID(), SPACE, SUMMARY,
                new WhatsAppSender.Message(WhatsAppSender.Kind.SUMMARY, NUMBER.e164(), List.of()));
        when(deliveries.finish(any(), any(), any(), any(), any(), any(), any())).thenReturn(false);
        service.record(ready, SendResult.of(Outcome.REJECTED, "1"), NOW);
        when(deliveries.finish(any(), any(), any(), any(), any(), any(), any())).thenReturn(true);
        service.record(new Ready(DELIVERY, UUID.randomUUID(), SPACE, null, ready.message()),
                SendResult.of(Outcome.REJECTED, "1"), NOW);
        verify(notifications, never()).recordWhatsAppFailure(any(), any(), any(), any());
    }

    @Test
    void interruptedAttemptsBecomeUncertainOnce() {
        var attempt = UUID.randomUUID();
        var test = UUID.randomUUID();
        when(deliveries.staleAttempts(NOW.minus(WhatsAppDeliveryService.STALE_ATTEMPT))).thenReturn(List.of(
                new StaleDelivery(DELIVERY, attempt, SPACE, SUMMARY), new StaleDelivery(test, attempt, SPACE, null),
                new StaleDelivery(UUID.randomUUID(), attempt, SPACE, UUID.randomUUID())));
        when(deliveries.finish(eq(DELIVERY), any(), any(), any(), any(), any(), any())).thenReturn(true);
        when(deliveries.finish(eq(test), any(), any(), any(), any(), any(), any())).thenReturn(true);
        assertThat(service.expireStale(NOW)).isEqualTo(3);
        verify(deliveries).finish(DELIVERY, attempt, WhatsAppDeliveryStatus.UNCERTAIN, null, null,
                "RESULT_UNCERTAIN", NOW);
        verify(notifications).recordWhatsAppFailure(SPACE, SUMMARY, WhatsAppFailureReason.RESULT_UNCERTAIN, NOW);
        verify(notifications, org.mockito.Mockito.times(1)).recordWhatsAppFailure(any(), any(), any(), any());
    }

    @Test
    void webhookStatusesApplyOnceForwardOnlyAndOnlyToKnownMessages() {
        var at = Instant.parse("2026-10-05T12:01:00Z");
        when(deliveries.lockByProviderMessageId("wamid.A")).thenReturn(Optional.of(
                new DeliveryRef(DELIVERY, SPACE, SUMMARY, WhatsAppDeliveryStatus.READ)));
        when(deliveries.lockByProviderMessageId("wamid.B")).thenReturn(Optional.of(
                new DeliveryRef(UUID.randomUUID(), SPACE, SUMMARY, WhatsAppDeliveryStatus.ACCEPTED)));
        when(deliveries.lockByProviderMessageId("wamid.C")).thenReturn(Optional.of(
                new DeliveryRef(UUID.randomUUID(), SPACE, null, WhatsAppDeliveryStatus.SENT)));
        when(deliveries.lockByProviderMessageId("wamid.D")).thenReturn(Optional.of(
                new DeliveryRef(UUID.randomUUID(), SPACE, SUMMARY, WhatsAppDeliveryStatus.REJECTED)));
        when(deliveries.recordEvent(any(), any(), any(), any(), any(), any())).thenReturn(true);
        when(deliveries.recordEvent(eq("wamid.A"), any(), eq(WhatsAppDeliveryStatus.SENT), any(), any(), any()))
                .thenReturn(false);
        var outcome = service.applyStatuses(List.of(
                new StatusUpdate("wamid.A", "delivered", at, null),
                new StatusUpdate("wamid.A", "sent", at, null),
                new StatusUpdate("wamid.A", "failed", at, "131026"),
                new StatusUpdate("wamid.B", "failed", null, "131026"),
                new StatusUpdate("wamid.C", "failed", at, "1"),
                new StatusUpdate("wamid.D", "delivered", at, null),
                new StatusUpdate("wamid.X", "delivered", at, null),
                new StatusUpdate("wamid.A", "deleted", at, null),
                new StatusUpdate(" ", "sent", at, null),
                new StatusUpdate(null, "sent", at, null)), NOW);
        assertThat(outcome).isEqualTo(new WhatsAppDeliveryService.WebhookOutcome(2, 1, 3, 4, false));
        verify(deliveries).confirm(DELIVERY, WhatsAppDeliveryStatus.DELIVERED, at, null, false, NOW);
        verify(deliveries, never()).confirm(eq(DELIVERY), eq(WhatsAppDeliveryStatus.FAILED), any(), any(),
                anyBoolean(), any());
        verify(deliveries).confirm(any(), eq(WhatsAppDeliveryStatus.FAILED), eq(NOW), eq("131026"), eq(true),
                eq(NOW));
        verify(notifications).recordWhatsAppFailure(SPACE, SUMMARY, WhatsAppFailureReason.DELIVERY_FAILED, NOW);
        verify(notifications, org.mockito.Mockito.times(1)).recordWhatsAppFailure(any(), any(), any(), any());

        when(deliveries.anyAttempting()).thenReturn(true);
        assertThat(service.applyStatuses(List.of(new StatusUpdate("wamid.X", "sent", at, null)), NOW).retryLater())
                .isTrue();
    }

    @Test
    void trackingIsForTheAdministratorAndExplainsEachState() {
        assertThatThrownBy(() -> service.summaryDelivery("guest@example.com", SUMMARY))
                .isInstanceOf(WhatsAppAdministratorRequiredException.class);
        when(summaries.find(SPACE, SUMMARY)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.summaryDelivery("admin@example.com", SUMMARY))
                .isInstanceOf(ReminderSummaryNotFoundException.class);

        when(summaries.find(SPACE, SUMMARY)).thenReturn(Optional.of(stored(LUZ)));
        var waiting = service.summaryDelivery("admin@example.com", SUMMARY);
        assertThat(waiting.state()).isEqualTo("WAITING");
        assertThat(waiting.stateMessage()).isEqualTo("Aguardando o envio pelo WhatsApp.");

        when(summaries.find(SPACE, SUMMARY)).thenReturn(Optional.of(storedWith(new Channel(ChannelType.WHATSAPP,
                ChannelStatus.SKIPPED, "DISABLED", null), LUZ)));
        var notPlanned = service.summaryDelivery("admin@example.com", SUMMARY);
        assertThat(notPlanned.state()).isEqualTo("NOT_PLANNED");
        assertThat(notPlanned.reasonMessage()).isEqualTo("Canal desativado quando o resumo foi gerado.");

        when(summaries.find(SPACE, SUMMARY)).thenReturn(Optional.of(storedWith(null, LUZ)));
        assertThat(service.summaryDelivery("admin@example.com", SUMMARY).reason()).isNull();

        when(deliveries.forSummary(SPACE, SUMMARY)).thenReturn(Optional.of(delivery(WhatsAppDeliveryStatus.SKIPPED,
                "EMPTY", null)));
        var skipped = service.summaryDelivery("admin@example.com", SUMMARY);
        assertThat(skipped.reason()).isEqualTo("EMPTY");
        assertThat(skipped.reasonMessage()).startsWith("As contas do resumo já estavam pagas");
        when(deliveries.forSummary(SPACE, SUMMARY)).thenReturn(Optional.of(delivery(WhatsAppDeliveryStatus.FAILED,
                null, "DELIVERY_FAILED")));
        var failed = service.summaryDelivery("admin@example.com", SUMMARY);
        assertThat(failed.stateMessage()).isEqualTo("Não entregue.");
        assertThat(failed.reasonMessage()).isEqualTo(WhatsAppFailureReason.DELIVERY_FAILED.message());
        assertThat(failed.recipientMasked()).isEqualTo("+55 ** *****-4321");
        assertThat(failed.attempts()).extracting(WhatsAppDeliveryView.Attempt::outcome).containsExactly("ACCEPTED");
        when(deliveries.forSummary(SPACE, SUMMARY)).thenReturn(Optional.of(new StoredDelivery(DELIVERY, SPACE,
                WhatsAppSender.Kind.SUMMARY, SUMMARY, WhatsAppDeliveryStatus.DELIVERED, null, null, null, null, 2, NOW,
                NOW, NOW, NOW, NOW, null, null, List.of())));
        var delivered = service.summaryDelivery("admin@example.com", SUMMARY);
        assertThat(delivered.recipientMasked()).isNull();
        assertThat(delivered.reasonMessage()).isNull();
        assertThat(delivered.stateMessage()).isEqualTo("Entregue no WhatsApp do administrador.");
    }

    @Test
    void theTestIsIdempotentSpacedAndOnlyForAConsentedAdministrator() {
        var key = UUID.randomUUID();
        assertThatThrownBy(() -> service.prepareTest("guest@example.com", key, NOW))
                .isInstanceOf(WhatsAppAdministratorRequiredException.class);
        var ready = (Ready) service.prepareTest("admin@example.com", key, NOW);
        assertThat(ready.summaryId()).isNull();
        assertThat(ready.message().kind()).isEqualTo(WhatsAppSender.Kind.TEST);
        assertThat(ready.message().parameters()).isEmpty();
        var inserted = ArgumentCaptor.forClass(NewDelivery.class);
        verify(deliveries).insert(inserted.capture());
        assertThat(inserted.getValue().testKey()).isEqualTo(key);
        assertThat(inserted.getValue().summaryId()).isNull();

        when(deliveries.test(SPACE, key)).thenReturn(Optional.of(delivery(WhatsAppDeliveryStatus.ACCEPTED, null,
                null)));
        assertThat(service.prepareTest("admin@example.com", key, NOW)).isInstanceOf(ExistingTest.class);
        assertThat(service.testView(SPACE, key).state()).isEqualTo("ACCEPTED");
        assertThatThrownBy(() -> service.testView(SPACE, UUID.randomUUID())).isInstanceOf(IllegalStateException.class);

        var other = UUID.randomUUID();
        when(deliveries.test(SPACE, other)).thenReturn(Optional.empty(),
                Optional.of(delivery(WhatsAppDeliveryStatus.ATTEMPTING, null, null)));
        assertThat(service.prepareTest("admin@example.com", other, NOW)).isInstanceOf(ExistingTest.class);

        when(deliveries.lastTestAt(SPACE)).thenReturn(Optional.of(NOW.minusSeconds(59)));
        assertTestRefused("WHATSAPP_TEST_TOO_SOON");
        when(deliveries.lastTestAt(SPACE)).thenReturn(Optional.of(NOW.minusSeconds(60)));
        assertThat(service.prepareTest("admin@example.com", UUID.randomUUID(), NOW)).isInstanceOf(Ready.class);
        when(provider.testTemplateConfigured()).thenReturn(false);
        assertTestRefused("WHATSAPP_TEST_TEMPLATE_MISSING");
        when(provider.availability()).thenReturn(new WhatsAppProviderStatus.Availability(false, "PROVIDER_DISABLED",
                "desligado"));
        assertTestRefused("PROVIDER_DISABLED");
        when(settings.activeConsent(SPACE)).thenReturn(Optional.of(consent(GUEST, NUMBER)));
        assertTestRefused("WHATSAPP_CONSENT_REQUIRED");
        when(settings.activeConsent(SPACE)).thenReturn(Optional.of(consent(ADMIN,
                WhatsAppRecipient.parse("(21) 99876-5432"))));
        assertTestRefused("WHATSAPP_CONSENT_REQUIRED");
        assertThat(service.now()).isEqualTo(NOW);
    }

    private void assertTestRefused(String code) {
        assertThatThrownBy(() -> service.prepareTest("admin@example.com", UUID.randomUUID(), NOW))
                .isInstanceOf(WhatsAppTestUnavailableException.class)
                .extracting(e -> ((WhatsAppTestUnavailableException) e).code()).isEqualTo(code);
    }

    private void assertSkip(Runnable arrange, WhatsAppSkipReason reason) {
        setUp();
        arrange.run();
        assertThat(service.prepare(SUMMARY, NOW)).isEqualTo(new Skipped(reason));
        var inserted = ArgumentCaptor.forClass(NewDelivery.class);
        verify(deliveries).insert(inserted.capture());
        assertThat(inserted.getValue().status()).isEqualTo(WhatsAppDeliveryStatus.SKIPPED);
        assertThat(inserted.getValue().skipReason()).isEqualTo(reason.name());
        assertThat(inserted.getValue().consentId()).isNull();
        verify(deliveries, never()).insertAttempt(any(), any(), anyInt(), any());
    }

    private static int anyInt() {
        return org.mockito.ArgumentMatchers.anyInt();
    }

    private static StoredConsent consent(UUID user, WhatsAppRecipient recipient) {
        return new StoredConsent(CONSENT, user, "Admin", recipient, "V1", FIRST.minusSeconds(3600));
    }

    private static StoredDelivery delivery(WhatsAppDeliveryStatus status, String skip, String failure) {
        return new StoredDelivery(DELIVERY, SPACE, WhatsAppSender.Kind.SUMMARY, SUMMARY, status, skip, failure, null,
                "4321", 1, NOW, NOW, NOW, null, null, null, null,
                List.of(new StoredDelivery.Attempt(1, NOW, NOW, "ACCEPTED")));
    }

    private static StoredSummary stored(ReminderItem... items) {
        return storedWith(new Channel(ChannelType.WHATSAPP, ChannelStatus.PLANNED, null, ADMIN), items);
    }

    private static StoredSummary storedWith(Channel whatsapp, ReminderItem... items) {
        var channels = whatsapp == null ? List.of(new Channel(ChannelType.IN_APP, ChannelStatus.PLANNED, null, null))
                : List.of(new Channel(ChannelType.IN_APP, ChannelStatus.PLANNED, null, null), whatsapp);
        return new StoredSummary(SUMMARY, SPACE, LocalTime.of(9, 0), "America/Sao_Paulo", FIRST, FIRST,
                ReminderSummary.compose(TODAY, ReminderSlot.FIRST, List.of(items)).orElseThrow(), channels);
    }

    private static AuthenticatedUserContext actor(UUID id, SpaceRole role) {
        return new AuthenticatedUserContext(id, "Pessoa", "x@example.com", SPACE, "Casa", role, "BRL", "pt-BR",
                "America/Sao_Paulo");
    }
}
