package com.malyah.accountmanager.notifications.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import com.malyah.accountmanager.expenses.application.ExpenseReminderQueries;
import com.malyah.accountmanager.expenses.application.ReminderExpenseState;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContextQuery;
import com.malyah.accountmanager.notifications.application.StoredSummary.Channel;
import com.malyah.accountmanager.notifications.application.StoredSummary.ChannelStatus;
import com.malyah.accountmanager.notifications.application.StoredSummary.ChannelType;
import com.malyah.accountmanager.notifications.application.port.MemberNotificationRepository;
import com.malyah.accountmanager.notifications.application.port.ReminderSettingsRepository;
import com.malyah.accountmanager.notifications.application.port.ReminderSummaryRepository;
import com.malyah.accountmanager.notifications.application.port.WhatsAppProviderStatus;
import com.malyah.accountmanager.notifications.domain.ReminderCalendar;
import com.malyah.accountmanager.notifications.domain.ReminderItem;
import com.malyah.accountmanager.notifications.domain.ReminderSchedule;
import com.malyah.accountmanager.notifications.domain.ReminderSlot;
import com.malyah.accountmanager.notifications.domain.ReminderSummary;
import com.malyah.accountmanager.notifications.domain.ReminderSummaryText;
import com.malyah.accountmanager.notifications.domain.ReminderWindow;
import com.malyah.accountmanager.notifications.domain.WhatsAppChannelState;
import com.malyah.accountmanager.notifications.domain.WhatsAppFailureReason;
import com.malyah.accountmanager.recurrences.application.RecurrenceForecastQueries;
import com.malyah.accountmanager.recurrences.application.UpcomingOccurrenceGeneration;

/**
 * H08.2 (PRD 10.2, RF-ALT-06 to RF-ALT-11, RF-ALT-13/14/15/18/20, RF-REC-07). Decides which slots are due, reads
 * the eligible bills from the current state when a slot is processed and records at most one logical summary per
 * space, local date and slot, with one record per channel. Nothing is sent here: the WhatsApp record is only
 * planned when the channel is ready (H08.4 sends); otherwise it is skipped with the reason, and the in-app summary
 * exists anyway. The bills come from the public contracts of the expenses and recurrences modules.
 * H08.3: the same transaction delivers the in-app notification to every active member and, when an enabled channel
 * could not use the provider, tells the administrator in the application.
 */
public final class ReminderSummaryService implements ReminderSummaryUseCase {
    public static final int PREVIEW_DAYS_AHEAD = 60;
    public static final String SUMMARY_PATH = "/lembretes/resumos/";
    static final String PREVIEW_LINK = "(o link é criado quando o resumo é gerado)";

    private final ReminderSummaryRepository summaries;
    private final MemberNotificationRepository notifications;
    private final ReminderSettingsRepository settings;
    private final ExpenseReminderQueries expenses;
    private final RecurrenceForecastQueries forecasts;
    private final UpcomingOccurrenceGeneration generation;
    private final WhatsAppProviderStatus provider;
    private final AuthenticatedUserContextQuery contexts;
    private final Clock clock;
    private final Supplier<UUID> identifiers;
    private final String publicBaseUrl;

    public ReminderSummaryService(ReminderSummaryRepository summaries, MemberNotificationRepository notifications,
            ReminderSettingsRepository settings,
            ExpenseReminderQueries expenses, RecurrenceForecastQueries forecasts,
            UpcomingOccurrenceGeneration generation, WhatsAppProviderStatus provider,
            AuthenticatedUserContextQuery contexts, Clock clock, Supplier<UUID> identifiers, String publicBaseUrl) {
        this.summaries = Objects.requireNonNull(summaries);
        this.notifications = Objects.requireNonNull(notifications);
        this.settings = Objects.requireNonNull(settings);
        this.expenses = Objects.requireNonNull(expenses);
        this.forecasts = Objects.requireNonNull(forecasts);
        this.generation = Objects.requireNonNull(generation);
        this.provider = Objects.requireNonNull(provider);
        this.contexts = Objects.requireNonNull(contexts);
        this.clock = Objects.requireNonNull(clock);
        this.identifiers = Objects.requireNonNull(identifiers);
        this.publicBaseUrl = publicBaseUrl.endsWith("/") ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1)
                : publicBaseUrl;
    }

    /**
     * The slots to handle at {@code now}: a started, unprocessed slot inside its window is generated; one of the
     * local today whose window has passed is recorded as missed (no late or accumulated summary). Yesterday is
     * looked at only for a second slot whose window crosses midnight.
     */
    public List<ReminderSlotTask> dueSlots(Instant now) {
        var tasks = new ArrayList<ReminderSlotTask>();
        for (var space : summaries.spaces()) {
            var today = now.atZone(space.zone()).toLocalDate();
            for (var date : List.of(today.minusDays(1), today))
                for (var slot : ReminderSlot.values()) {
                    var window = ReminderWindow.of(space.schedule(), space.zone(), date, slot);
                    if (!window.started(now) || summaries.slotProcessed(space.spaceId(), date, slot)) continue;
                    if (window.open(now)) tasks.add(new ReminderSlotTask(space, window, ReminderSlotTask.Action.GENERATE));
                    else if (date.equals(today)) tasks.add(new ReminderSlotTask(space, window, ReminderSlotTask.Action.MISS));
                }
        }
        return List.copyOf(tasks);
    }

    /**
     * RF-REC-07: before a slot is composed, the recurrence occurrences due in its reach are materialized through the
     * E04 generation. Must run outside a transaction; a failure leaves them as forecasts in the summary.
     */
    public int materializeAhead(ReminderSlotTask task) {
        return generation.materializeUpcoming(task.space().spaceId(),
                ReminderCalendar.lastDueDate(task.window().slot(), task.window().date()));
    }

    /** Processes one slot in the caller's transaction, reading the bills as they are now. */
    public ReminderSlotOutcome process(ReminderSlotTask task, Instant now) {
        var space = task.space();
        var window = task.window();
        var time = space.schedule().at(window.slot());
        if (task.action() == ReminderSlotTask.Action.MISS || !window.open(now))
            return summaries.claimSlot(space.spaceId(), window.date(), window.slot(), time, "MISSED", now)
                    ? ReminderSlotOutcome.MISSED : ReminderSlotOutcome.ALREADY_PROCESSED;
        if (!summaries.claimSlot(space.spaceId(), window.date(), window.slot(), time, "EMPTY", now))
            return ReminderSlotOutcome.ALREADY_PROCESSED;
        var summary = compose(space.spaceId(), window.date(), window.slot());
        if (summary.isEmpty()) return ReminderSlotOutcome.EMPTY;
        var stored = new StoredSummary(identifiers.get(), space.spaceId(), time, space.zone().getId(),
                window.scheduledAt(), now, summary.get(), channels(space.spaceId(), true));
        summaries.insert(stored);
        summaries.markGenerated(space.spaceId(), window.date(), window.slot(), stored.id());
        notifications.deliverSummary(space.spaceId(), stored.id(), now);
        if (stored.channels().stream().anyMatch(ReminderSummaryService::providerUnavailable))
            notifications.recordWhatsAppFailure(space.spaceId(), stored.id(),
                    WhatsAppFailureReason.PROVIDER_UNAVAILABLE, now);
        return ReminderSlotOutcome.GENERATED;
    }

    /** The eligible bills of the slot: pending expenses and, once each, forecasts not materialized yet. */
    public Optional<ReminderSummary> compose(UUID spaceId, LocalDate today, ReminderSlot slot) {
        var lastDue = ReminderCalendar.lastDueDate(slot, today);
        var candidates = new ArrayList<ReminderItem>();
        for (var expense : expenses.pendingDueThrough(spaceId, lastDue))
            candidates.add(ReminderItem.expense(expense.id(), expense.origin(), expense.description(),
                    expense.chargeAmount(), expense.dueDate(), !expense.chargeConfirmed(),
                    expense.installment() == null ? null : expense.installment().number(),
                    expense.installment() == null ? null : expense.installment().count()));
        for (var forecast : forecasts.unmaterialized(spaceId, YearMonth.from(today), YearMonth.from(lastDue)))
            if (!forecast.dueDate().isBefore(today) && !forecast.dueDate().isAfter(lastDue))
                candidates.add(ReminderItem.forecast(forecast.recurrenceId(), forecast.dueDate(),
                        forecast.description(), forecast.amount(), forecast.estimated()));
        return ReminderSummary.compose(today, slot, candidates);
    }

    @Override
    public ReminderSummaryView.Preview preview(String actorEmail, String date, String slot) {
        var actor = contexts.findByEmail(actorEmail);
        var zone = ZoneId.of(actor.timeZone());
        var today = clock.instant().atZone(zone).toLocalDate();
        var day = parseDate(date, today);
        if (day.isBefore(today) || day.isAfter(today.plusDays(PREVIEW_DAYS_AHEAD)))
            throw new ReminderQueryValidationException("date",
                    "Escolha uma data de hoje até " + PREVIEW_DAYS_AHEAD + " dias à frente.");
        var reminderSlot = parseSlot(slot);
        var schedule = settings.find(actor.spaceId()).map(StoredReminderSettings::schedule)
                .orElse(ReminderSchedule.DEFAULT);
        var time = schedule.at(reminderSlot);
        var view = compose(actor.spaceId(), day, reminderSlot).map(summary -> view(actor.spaceId(), null, summary,
                ReminderSchedule.format(time), zone.getId(), null, channels(actor.spaceId(), false), time));
        return new ReminderSummaryView.Preview(day, reminderSlot.name(), ReminderSchedule.format(time), zone.getId(),
                today, view.orElse(null));
    }

    @Override
    public ReminderSummaryView summary(String actorEmail, UUID summaryId) {
        var actor = contexts.findByEmail(actorEmail);
        var stored = summaries.find(actor.spaceId(), summaryId).orElseThrow(ReminderSummaryNotFoundException::new);
        return view(actor.spaceId(), stored.id(), stored.summary(), ReminderSchedule.format(stored.scheduledTime()), stored.timeZone(),
                stored.generatedAt(), stored.channels(), stored.scheduledTime());
    }

    public String link(UUID summaryId) {
        return publicBaseUrl + SUMMARY_PATH + summaryId;
    }

    /**
     * RF-ALT-01/20: the in-app record always exists (both members); the WhatsApp one is planned only for a ready
     * channel whose consent belongs to the current administrator, and skipped with the reason otherwise.
     */
    private List<Channel> channels(UUID spaceId, boolean withRecipient) {
        var stored = settings.find(spaceId).orElse(StoredReminderSettings.defaults(spaceId));
        var administrator = summaries.activeAdministrator(spaceId);
        var consent = settings.activeConsent(spaceId).filter(active -> administrator.isPresent()
                && active.userId().equals(administrator.get()) && active.recipient().equals(stored.recipient()));
        var state = WhatsAppChannelState.of(stored.recipient() != null, consent.isPresent(), stored.enabled(),
                provider.availability().available());
        var whatsapp = state == WhatsAppChannelState.READY
                ? new Channel(ChannelType.WHATSAPP, ChannelStatus.PLANNED, null,
                        withRecipient ? consent.get().userId() : null)
                : new Channel(ChannelType.WHATSAPP, ChannelStatus.SKIPPED, state.name(), null);
        return List.of(new Channel(ChannelType.IN_APP, ChannelStatus.PLANNED, null, null), whatsapp);
    }

    /** Only an enabled, consented channel that the provider could not serve is a failure the administrator sees. */
    private static boolean providerUnavailable(Channel channel) {
        return channel.channel() == ChannelType.WHATSAPP && channel.status() == ChannelStatus.SKIPPED
                && WhatsAppChannelState.PROVIDER_UNAVAILABLE.name().equals(channel.skipReason());
    }

    private ReminderSummaryView view(UUID spaceId, UUID id, ReminderSummary summary, String time, String zone,
            Instant generatedAt, List<Channel> channels, LocalTime scheduledTime) {
        // H08.3: the summary stays as generated; the current situation of each bill is read now, next to it.
        var current = new HashMap<UUID, ReminderExpenseState>();
        for (var state : expenses.currentStates(spaceId, summary.items().stream()
                .map(item -> item.expenseId()).filter(Objects::nonNull).toList()))
            current.put(state.id(), state);
        var items = new ArrayList<ReminderSummaryView.Item>();
        var position = 1;
        for (var item : summary.items()) {
            var state = item.expenseId() == null ? null : current.get(item.expenseId());
            items.add(new ReminderSummaryView.Item(position++, item.expenseId(), item.recurrenceId(),
                    item.description(), item.label(), item.amount().setScale(2).toPlainString(), item.dueDate(),
                    item.estimated(), item.overdue(summary.date()), item.forecast(), item.origin(),
                    item.installmentNumber(), item.installmentCount(), state == null ? null : state.status(),
                    state == null ? null : state.dueDate()));
        }
        var link = id == null ? null : link(id);
        return new ReminderSummaryView(id, summary.date(), summary.slot().name(), time, zone, generatedAt,
                summary.count(), summary.total().setScale(2).toPlainString(), summary.estimatedCount(),
                summary.estimatedTotal().setScale(2).toPlainString(), summary.overdueCount(),
                summary.details().size(), summary.remaining(), items, link,
                ReminderSummaryText.render(summary, scheduledTime, link == null ? PREVIEW_LINK : link),
                channels.stream().map(channel -> new ReminderSummaryView.ChannelView(channel.channel().name(),
                        channel.status().name(), channel.skipReason())).toList());
    }

    private static LocalDate parseDate(String value, LocalDate today) {
        if (value == null || value.isBlank()) return today;
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException invalid) {
            throw new ReminderQueryValidationException("date", "Use a data no formato AAAA-MM-DD.");
        }
    }

    private static ReminderSlot parseSlot(String value) {
        if ("FIRST".equals(value)) return ReminderSlot.FIRST;
        if ("SECOND".equals(value)) return ReminderSlot.SECOND;
        throw new ReminderQueryValidationException("slot", "Escolha o primeiro (FIRST) ou o segundo (SECOND) horário.");
    }
}
