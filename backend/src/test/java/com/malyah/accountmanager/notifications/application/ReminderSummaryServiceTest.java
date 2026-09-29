package com.malyah.accountmanager.notifications.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.malyah.accountmanager.expenses.application.ExpenseReminderQueries;
import com.malyah.accountmanager.expenses.application.InstallmentLink;
import com.malyah.accountmanager.expenses.application.ReminderExpense;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContext;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContextQuery;
import com.malyah.accountmanager.identity.domain.SpaceRole;
import com.malyah.accountmanager.notifications.application.port.ReminderSettingsRepository;
import com.malyah.accountmanager.notifications.application.port.ReminderSummaryRepository;
import com.malyah.accountmanager.notifications.application.port.WhatsAppProviderStatus;
import com.malyah.accountmanager.notifications.domain.ReminderSchedule;
import com.malyah.accountmanager.notifications.domain.ReminderSlot;
import com.malyah.accountmanager.notifications.domain.ReminderWindow;
import com.malyah.accountmanager.notifications.domain.WhatsAppRecipient;
import com.malyah.accountmanager.recurrences.application.PlannedForecast;
import com.malyah.accountmanager.recurrences.application.RecurrenceForecastQueries;
import com.malyah.accountmanager.recurrences.application.UpcomingOccurrenceGeneration;

/** H08.2 orchestration with in-memory ports; the PostgreSQL behavior is in ReminderSummaryPostgresIT. */
class ReminderSummaryServiceTest {
    static final UUID SPACE = UUID.fromString("a0000000-0000-0000-0000-000000000001");
    static final UUID ADMIN = UUID.fromString("a0000000-0000-0000-0000-000000000002");
    static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");
    static final LocalDate TODAY = LocalDate.of(2026, 10, 5);
    static final Instant FIRST_SLOT = Instant.parse("2026-10-05T12:00:00Z");
    static final WhatsAppRecipient NUMBER = WhatsAppRecipient.parse("(11) 98765-4321");

    FakeSummaries summaries;
    ReminderSettingsRepository settings;
    ExpenseReminderQueries expenses;
    RecurrenceForecastQueries forecasts;
    UpcomingOccurrenceGeneration generation;
    boolean providerAvailable;
    ReminderSummaryService service;
    UUID nextId;

    @BeforeEach
    void setUp() {
        summaries = new FakeSummaries();
        summaries.spaces.add(new SpaceReminderSchedule(SPACE, ZONE, ReminderSchedule.DEFAULT));
        summaries.administrator = ADMIN;
        settings = mock(ReminderSettingsRepository.class);
        when(settings.find(SPACE)).thenReturn(Optional.empty());
        when(settings.activeConsent(SPACE)).thenReturn(Optional.empty());
        expenses = mock(ExpenseReminderQueries.class);
        forecasts = mock(RecurrenceForecastQueries.class);
        generation = mock(UpcomingOccurrenceGeneration.class);
        nextId = UUID.fromString("b0000000-0000-0000-0000-000000000001");
        WhatsAppProviderStatus provider = () -> new WhatsAppProviderStatus.Availability(providerAvailable, "X", "m");
        AuthenticatedUserContextQuery contexts = email -> new AuthenticatedUserContext(ADMIN, "Admin", email, SPACE,
                "Casa", SpaceRole.ADMINISTRATOR, "BRL", "pt-BR", "America/Sao_Paulo");
        service = new ReminderSummaryService(summaries, settings, expenses, forecasts, generation, provider, contexts,
                Clock.fixed(Instant.parse("2026-10-05T15:00:00Z"), ZoneOffset.UTC), () -> nextId,
                "https://contas.example/");
    }

    @Test
    void dueSlotsFollowTheWindowsOfTheLocalDay() {
        assertThat(service.dueSlots(Instant.parse("2026-10-05T11:59:00Z"))).isEmpty();
        assertThat(service.dueSlots(FIRST_SLOT)).singleElement().satisfies(task -> {
            assertThat(task.action()).isEqualTo(ReminderSlotTask.Action.GENERATE);
            assertThat(task.window().date()).isEqualTo(TODAY);
            assertThat(task.window().slot()).isEqualTo(ReminderSlot.FIRST);
            assertThat(task.space().spaceId()).isEqualTo(SPACE);
        });
        assertThat(service.dueSlots(Instant.parse("2026-10-05T13:00:00Z"))).singleElement()
                .extracting(ReminderSlotTask::action).isEqualTo(ReminderSlotTask.Action.MISS);
        summaries.runs.put(key(TODAY, ReminderSlot.FIRST), "GENERATED");
        assertThat(service.dueSlots(Instant.parse("2026-10-05T12:30:00Z"))).isEmpty();
        var evening = service.dueSlots(Instant.parse("2026-10-05T21:10:00Z"));
        assertThat(evening).singleElement().satisfies(task -> {
            assertThat(task.window().slot()).isEqualTo(ReminderSlot.SECOND);
            assertThat(task.action()).isEqualTo(ReminderSlotTask.Action.GENERATE);
        });
    }

    @Test
    void yesterdaysSlotsAreOnlyHandledInsideTheirWindowAndNeverMarkedMissed() {
        summaries.spaces.clear();
        summaries.spaces.add(new SpaceReminderSchedule(SPACE, ZONE, new ReminderSchedule(LocalTime.of(0, 30), LocalTime.of(23, 50))));
        summaries.runs.put(key(TODAY.minusDays(1), ReminderSlot.FIRST), "GENERATED");
        // 00:20 local on 06/10: yesterday's 23:50 slot is still open; today's 00:30 has not started.
        var tasks = service.dueSlots(Instant.parse("2026-10-06T03:20:00Z"));
        assertThat(tasks).singleElement().satisfies(task -> {
            assertThat(task.window().date()).isEqualTo(TODAY);
            assertThat(task.window().slot()).isEqualTo(ReminderSlot.SECOND);
            assertThat(task.action()).isEqualTo(ReminderSlotTask.Action.GENERATE);
        });
        // 00:40 on 06/10: yesterday's second window is over (nothing recorded for yesterday); today's first is open.
        summaries.runs.put(key(TODAY.plusDays(1).minusDays(1), ReminderSlot.FIRST), "GENERATED");
        var later = service.dueSlots(Instant.parse("2026-10-06T03:40:00Z"));
        assertThat(later).extracting(task -> task.window().date() + " " + task.window().slot() + " " + task.action())
                .containsExactly("2026-10-06 FIRST GENERATE");
    }

    @Test
    void missedSlotIsRecordedOnceWithoutSummary() {
        var task = task(ReminderSlot.FIRST, ReminderSlotTask.Action.MISS);
        assertThat(service.process(task, Instant.parse("2026-10-05T13:30:00Z"))).isEqualTo(ReminderSlotOutcome.MISSED);
        assertThat(summaries.runs).containsEntry(key(TODAY, ReminderSlot.FIRST), "MISSED");
        assertThat(service.process(task, Instant.parse("2026-10-05T13:30:00Z"))).isEqualTo(ReminderSlotOutcome.ALREADY_PROCESSED);
        assertThat(summaries.stored).isEmpty();
        verify(expenses, never()).pendingDueThrough(any(), any());
    }

    @Test
    void aGenerateTaskReachingItsDeadlineIsMissedInstead() {
        var task = task(ReminderSlot.FIRST, ReminderSlotTask.Action.GENERATE);
        assertThat(service.process(task, Instant.parse("2026-10-05T13:00:00Z"))).isEqualTo(ReminderSlotOutcome.MISSED);
        assertThat(summaries.stored).isEmpty();
    }

    @Test
    void emptySlotIsRecordedAndNothingIsStored() {
        assertThat(service.process(task(ReminderSlot.FIRST, ReminderSlotTask.Action.GENERATE), FIRST_SLOT))
                .isEqualTo(ReminderSlotOutcome.EMPTY);
        assertThat(summaries.runs).containsEntry(key(TODAY, ReminderSlot.FIRST), "EMPTY");
        assertThat(summaries.stored).isEmpty();
    }

    @Test
    void generatesWithInAppAlwaysAndWhatsAppSkippedWithTheReason() {
        bills();
        var task = task(ReminderSlot.FIRST, ReminderSlotTask.Action.GENERATE);
        assertThat(service.process(task, FIRST_SLOT.plusSeconds(30))).isEqualTo(ReminderSlotOutcome.GENERATED);
        assertThat(summaries.runs).containsEntry(key(TODAY, ReminderSlot.FIRST), "GENERATED:" + nextId);
        var stored = summaries.stored.getFirst();
        assertThat(stored.id()).isEqualTo(nextId);
        assertThat(stored.spaceId()).isEqualTo(SPACE);
        assertThat(stored.scheduledTime()).isEqualTo(LocalTime.of(9, 0));
        assertThat(stored.timeZone()).isEqualTo("America/Sao_Paulo");
        assertThat(stored.scheduledAt()).isEqualTo(FIRST_SLOT);
        assertThat(stored.generatedAt()).isEqualTo(FIRST_SLOT.plusSeconds(30));
        assertThat(stored.summary().items()).extracting(item -> item.label())
                .containsExactly("Aluguel", "Geladeira (3/10)", "Internet");
        assertThat(stored.channels()).containsExactly(
                new StoredSummary.Channel(StoredSummary.ChannelType.IN_APP, StoredSummary.ChannelStatus.PLANNED, null, null),
                new StoredSummary.Channel(StoredSummary.ChannelType.WHATSAPP, StoredSummary.ChannelStatus.SKIPPED,
                        "RECIPIENT_REQUIRED", null));
        assertThat(service.process(task, FIRST_SLOT.plusSeconds(60))).isEqualTo(ReminderSlotOutcome.ALREADY_PROCESSED);
        assertThat(summaries.stored).hasSize(1);
    }

    @Test
    void whatsappStatesOfTheChannel() {
        bills();
        configure(true, true, ADMIN);
        assertThat(whatsapp()).isEqualTo(new StoredSummary.Channel(StoredSummary.ChannelType.WHATSAPP,
                StoredSummary.ChannelStatus.SKIPPED, "PROVIDER_UNAVAILABLE", null));
        providerAvailable = true;
        assertThat(whatsapp()).isEqualTo(new StoredSummary.Channel(StoredSummary.ChannelType.WHATSAPP,
                StoredSummary.ChannelStatus.PLANNED, null, ADMIN));
        configure(true, false, ADMIN);
        assertThat(whatsapp().skipReason()).isEqualTo("DISABLED");
        configure(false, true, null);
        assertThat(whatsapp().skipReason()).isEqualTo("CONSENT_REQUIRED");
        // A consent left by someone who is no longer the administrator is never used.
        configure(true, true, UUID.randomUUID());
        assertThat(whatsapp().skipReason()).isEqualTo("CONSENT_REQUIRED");
        configure(true, true, ADMIN);
        summaries.administrator = null;
        assertThat(whatsapp().skipReason()).isEqualTo("CONSENT_REQUIRED");
        summaries.administrator = ADMIN;
        // A consent for another number than the configured one is not usable either.
        when(settings.activeConsent(SPACE)).thenReturn(Optional.of(new StoredConsent(UUID.randomUUID(), ADMIN, "Admin",
                WhatsAppRecipient.parse("(21) 99876-5432"), "V1", FIRST_SLOT)));
        assertThat(whatsapp().skipReason()).isEqualTo("CONSENT_REQUIRED");
    }

    @Test
    void composeReadsPendingExpensesAndOnlyForecastsInsideTheReach() {
        var recurrence = UUID.randomUUID();
        when(expenses.pendingDueThrough(SPACE, LocalDate.of(2026, 10, 10))).thenReturn(List.of(
                new ReminderExpense(UUID.randomUUID(), "RECURRENCE", null, "Luz", LocalDate.of(2026, 10, 6),
                        new BigDecimal("90.00"), false)));
        when(forecasts.unmaterialized(SPACE, YearMonth.of(2026, 10), YearMonth.of(2026, 10))).thenReturn(List.of(
                forecast(recurrence, "2026-10-04"), forecast(recurrence, "2026-10-05"), forecast(recurrence, "2026-10-10"),
                forecast(recurrence, "2026-10-11")));
        var summary = service.compose(SPACE, TODAY, ReminderSlot.FIRST).orElseThrow();
        assertThat(summary.items()).extracting(item -> item.dueDate().toString() + (item.forecast() ? " F" : " E")
                + (item.estimated() ? " est" : "")).containsExactly("2026-10-05 F", "2026-10-06 E est", "2026-10-10 F");
        assertThat(summary.estimatedTotal()).isEqualByComparingTo("90.00");
    }

    @Test
    void composeAsksForTheMonthsOfTheReach() {
        service.compose(SPACE, LocalDate.of(2026, 9, 28), ReminderSlot.FIRST);
        verify(expenses).pendingDueThrough(SPACE, LocalDate.of(2026, 10, 3));
        verify(forecasts).unmaterialized(SPACE, YearMonth.of(2026, 9), YearMonth.of(2026, 10));
        service.compose(SPACE, TODAY, ReminderSlot.SECOND);
        verify(expenses).pendingDueThrough(SPACE, LocalDate.of(2026, 10, 6));
    }

    @Test
    void materializationReachesTheLastDueDateOfTheSlot() {
        when(generation.materializeUpcoming(SPACE, LocalDate.of(2026, 10, 10))).thenReturn(2);
        assertThat(service.materializeAhead(task(ReminderSlot.FIRST, ReminderSlotTask.Action.GENERATE))).isEqualTo(2);
        service.materializeAhead(task(ReminderSlot.SECOND, ReminderSlotTask.Action.GENERATE));
        verify(generation).materializeUpcoming(SPACE, LocalDate.of(2026, 10, 6));
    }

    @Test
    void previewSimulatesWithoutWritingAndWithoutRecipient() {
        bills();
        configure(true, true, ADMIN);
        providerAvailable = true;
        when(settings.find(SPACE)).thenReturn(Optional.of(new StoredReminderSettings(SPACE,
                new ReminderSchedule(LocalTime.of(8, 30), LocalTime.of(20, 0)), NUMBER, true, 3, FIRST_SLOT)));
        var preview = service.preview("admin@example.com", null, "FIRST");
        assertThat(preview.date()).isEqualTo(TODAY);
        assertThat(preview.today()).isEqualTo(TODAY);
        assertThat(preview.scheduledTime()).isEqualTo("08:30");
        assertThat(preview.timeZone()).isEqualTo("America/Sao_Paulo");
        var summary = preview.summary();
        assertThat(summary.id()).isNull();
        assertThat(summary.link()).isNull();
        assertThat(summary.generatedAt()).isNull();
        assertThat(summary.count()).isEqualTo(3);
        assertThat(summary.total()).isEqualTo("1920.00");
        assertThat(summary.detailCount()).isEqualTo(3);
        assertThat(summary.items().getFirst().position()).isEqualTo(1);
        assertThat(summary.items().get(1).installmentNumber()).isEqualTo(3);
        assertThat(summary.text()).startsWith("Contas a pagar — 05/10/2026, 08:30")
                .endsWith("Lista completa: " + ReminderSummaryService.PREVIEW_LINK);
        assertThat(summary.channels()).extracting(ReminderSummaryView.ChannelView::status).containsExactly("PLANNED", "PLANNED");
        assertThat(summaries.runs).isEmpty();
        assertThat(summaries.stored).isEmpty();
        verify(generation, never()).materializeUpcoming(any(), any());
        assertThat(service.preview("admin@example.com", "2026-12-04", "SECOND").summary()).isNull();
        assertThat(service.preview("admin@example.com", "", "SECOND").date()).isEqualTo(TODAY);
    }

    @Test
    void previewRejectsInvalidDatesAndSlots() {
        assertThat(service.preview("a", "2026-12-04", "FIRST").date()).isEqualTo(LocalDate.of(2026, 12, 4));
        assertThatThrownBy(() -> service.preview("a", "2026-12-05", "FIRST"))
                .isInstanceOf(ReminderQueryValidationException.class).extracting("field").isEqualTo("date");
        assertThatThrownBy(() -> service.preview("a", "2026-10-04", "FIRST"))
                .isInstanceOf(ReminderQueryValidationException.class);
        assertThatThrownBy(() -> service.preview("a", "05/10/2026", "FIRST"))
                .isInstanceOf(ReminderQueryValidationException.class).hasMessageContaining("AAAA-MM-DD");
        assertThatThrownBy(() -> service.preview("a", null, "first"))
                .isInstanceOf(ReminderQueryValidationException.class).extracting("field").isEqualTo("slot");
        assertThatThrownBy(() -> service.preview("a", null, null)).isInstanceOf(ReminderQueryValidationException.class);
    }

    @Test
    void storedSummaryIsReadWithItsLinkAndOnlyInsideTheSpace() {
        bills();
        service.process(task(ReminderSlot.FIRST, ReminderSlotTask.Action.GENERATE), FIRST_SLOT);
        var view = service.summary("admin@example.com", nextId);
        assertThat(view.id()).isEqualTo(nextId);
        assertThat(view.link()).isEqualTo("https://contas.example/lembretes/resumos/" + nextId);
        assertThat(view.text()).endsWith(view.link());
        assertThat(view.generatedAt()).isEqualTo(FIRST_SLOT);
        assertThat(view.scheduledTime()).isEqualTo("09:00");
        assertThat(view.slot()).isEqualTo("FIRST");
        assertThat(view.channels()).extracting(ReminderSummaryView.ChannelView::reason).containsExactly(null, "RECIPIENT_REQUIRED");
        assertThatThrownBy(() -> service.summary("admin@example.com", UUID.randomUUID()))
                .isInstanceOf(ReminderSummaryNotFoundException.class).hasMessage("Resumo não encontrado.");
        assertThat(new ReminderSummaryService(summaries, settings, expenses, forecasts, generation,
                () -> null, email -> null, Clock.systemUTC(), UUID::randomUUID, "http://x").link(nextId))
                .isEqualTo("http://x/lembretes/resumos/" + nextId);
    }

    private StoredSummary.Channel whatsapp() {
        nextId = UUID.randomUUID();
        summaries.runs.clear();
        service.process(task(ReminderSlot.FIRST, ReminderSlotTask.Action.GENERATE), FIRST_SLOT);
        return summaries.stored.getLast().channels().get(1);
    }

    private void configure(boolean consent, boolean enabled, UUID consentBy) {
        when(settings.find(SPACE)).thenReturn(Optional.of(new StoredReminderSettings(SPACE, ReminderSchedule.DEFAULT,
                NUMBER, enabled, 3, FIRST_SLOT)));
        when(settings.activeConsent(SPACE)).thenReturn(consent
                ? Optional.of(new StoredConsent(UUID.randomUUID(), consentBy, "Admin", NUMBER, "V1", FIRST_SLOT))
                : Optional.empty());
    }

    private void bills() {
        when(expenses.pendingDueThrough(any(), any())).thenReturn(List.of(
                new ReminderExpense(UUID.randomUUID(), "ONE_OFF", null, "Aluguel", LocalDate.of(2026, 10, 1),
                        new BigDecimal("1500.00"), true),
                new ReminderExpense(UUID.randomUUID(), "INSTALLMENT", new InstallmentLink(UUID.randomUUID(), 3, 10),
                        "Geladeira", LocalDate.of(2026, 10, 7), new BigDecimal("300.00"), true)));
        when(forecasts.unmaterialized(any(), any(), any())).thenReturn(List.of(new PlannedForecast(UUID.randomUUID(),
                LocalDate.of(2026, 10, 8), "Internet", new BigDecimal("120.00"), false, null, null, null, null)));
    }

    private static PlannedForecast forecast(UUID recurrence, String due) {
        return new PlannedForecast(recurrence, LocalDate.parse(due), "Água", new BigDecimal("50.00"), false, null,
                null, null, null);
    }

    private ReminderSlotTask task(ReminderSlot slot, ReminderSlotTask.Action action) {
        return new ReminderSlotTask(summaries.spaces.getFirst(),
                ReminderWindow.of(summaries.spaces.getFirst().schedule(), ZONE, TODAY, slot), action);
    }

    private static String key(LocalDate date, ReminderSlot slot) {
        return SPACE + "/" + date + "/" + slot;
    }

    static final class FakeSummaries implements ReminderSummaryRepository {
        final List<SpaceReminderSchedule> spaces = new ArrayList<>();
        final Map<String, String> runs = new HashMap<>();
        final List<StoredSummary> stored = new ArrayList<>();
        UUID administrator;

        @Override public List<SpaceReminderSchedule> spaces() { return spaces; }

        @Override public boolean slotProcessed(UUID spaceId, LocalDate date, ReminderSlot slot) {
            return runs.containsKey(spaceId + "/" + date + "/" + slot);
        }

        @Override public boolean claimSlot(UUID spaceId, LocalDate date, ReminderSlot slot, LocalTime time,
                String outcome, Instant at) {
            return runs.putIfAbsent(spaceId + "/" + date + "/" + slot, outcome) == null;
        }

        @Override public void markGenerated(UUID spaceId, LocalDate date, ReminderSlot slot, UUID summaryId) {
            runs.put(spaceId + "/" + date + "/" + slot, "GENERATED:" + summaryId);
        }

        @Override public void insert(StoredSummary summary) { stored.add(summary); }

        @Override public Optional<StoredSummary> find(UUID spaceId, UUID summaryId) {
            return stored.stream().filter(s -> s.spaceId().equals(spaceId) && s.id().equals(summaryId)).findFirst();
        }

        @Override public Optional<UUID> activeAdministrator(UUID spaceId) { return Optional.ofNullable(administrator); }
    }
}
