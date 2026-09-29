package com.malyah.accountmanager.notifications.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.malyah.accountmanager.identity.application.AuthenticatedUserContext;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContextQuery;
import com.malyah.accountmanager.identity.domain.SpaceRole;
import com.malyah.accountmanager.notifications.application.StoredSummary.ChannelStatus;
import com.malyah.accountmanager.notifications.application.StoredSummary.ChannelType;
import com.malyah.accountmanager.notifications.application.port.MemberNotificationRepository;
import com.malyah.accountmanager.notifications.application.port.ReminderSettingsRepository;
import com.malyah.accountmanager.notifications.application.port.ReminderSummaryRepository;
import com.malyah.accountmanager.notifications.application.port.WhatsAppDeliveryRepository;
import com.malyah.accountmanager.notifications.application.port.WhatsAppDeliveryRepository.NewDelivery;
import com.malyah.accountmanager.notifications.application.port.WhatsAppDeliveryRepository.PlannedSummary;
import com.malyah.accountmanager.notifications.application.port.WhatsAppProviderStatus;
import com.malyah.accountmanager.notifications.application.port.WhatsAppSender;
import com.malyah.accountmanager.notifications.application.port.WhatsAppSender.Kind;
import com.malyah.accountmanager.notifications.application.port.WhatsAppSender.SendResult;
import com.malyah.accountmanager.notifications.domain.ReminderItem;
import com.malyah.accountmanager.notifications.domain.ReminderSummary;
import com.malyah.accountmanager.notifications.domain.ReminderWindow;
import com.malyah.accountmanager.notifications.domain.WhatsAppDeliveryStatus;
import com.malyah.accountmanager.notifications.domain.WhatsAppFailureReason;
import com.malyah.accountmanager.notifications.domain.WhatsAppSkipReason;
import com.malyah.accountmanager.notifications.domain.WhatsAppSummaryTemplate;

/**
 * H08.4 (RF-ALT-01, RF-ALT-13, RF-ALT-15 to RF-ALT-18, RF-ALT-20). Each step runs in the caller's short
 * transaction; the provider call happens between {@link #prepare} and {@link #record}, outside any transaction.
 * <ul>
 * <li>{@link #prepare} locks the space's reminder settings (the lock every settings change takes), revalidates the
 * recipient, consent, activation, provider and window, recomposes the summary from the current bills (only bills of
 * the generated summary that are still eligible) and claims the one delivery of the summary.</li>
 * <li>{@link #record} stores acceptance, refusal, failure or uncertainty and tells the administrator in the
 * application. Nothing is ever resent here: an uncertain result waits for the H08.5 reconciliation.</li>
 * <li>{@link #applyStatuses} applies webhook confirmations of known messages, once and only forward.</li>
 * </ul>
 */
public final class WhatsAppDeliveryService {
    /** A delivery still attempting after this long was interrupted; the provider may have received it. */
    public static final Duration STALE_ATTEMPT = Duration.ofMinutes(10);
    /** Minimum interval between two administrator tests of one space. */
    public static final Duration TEST_INTERVAL = Duration.ofSeconds(60);
    static final Map<String, String> SKIP_MESSAGES = Map.of(
            WhatsAppSkipReason.ADMINISTRATOR_CHANGED.name(), "A administração mudou antes do envio.",
            WhatsAppSkipReason.CONSENT_REVOKED.name(), "O consentimento não estava ativo no momento do envio.",
            WhatsAppSkipReason.RECIPIENT_CHANGED.name(), "O número foi alterado antes do envio.",
            WhatsAppSkipReason.DISABLED.name(), "O canal foi desativado antes do envio.",
            WhatsAppSkipReason.PROVIDER_UNAVAILABLE.name(), "O envio pela Meta estava indisponível.",
            WhatsAppSkipReason.WINDOW_CLOSED.name(), "O horário terminou antes do envio; nada é enviado depois.",
            WhatsAppSkipReason.EMPTY.name(),
            "As contas do resumo já estavam pagas, canceladas ou fora do período; nada foi enviado.");
    static final Map<String, String> CHANNEL_MESSAGES = Map.of(
            "RECIPIENT_REQUIRED", "Sem número cadastrado quando o resumo foi gerado.",
            "CONSENT_REQUIRED", "Sem consentimento do administrador quando o resumo foi gerado.",
            "DISABLED", "Canal desativado quando o resumo foi gerado.",
            "PROVIDER_UNAVAILABLE", "Envio pela Meta indisponível quando o resumo foi gerado.");
    static final Map<String, String> STATE_MESSAGES = Map.ofEntries(
            Map.entry("NOT_PLANNED", "Este resumo não foi planejado para o WhatsApp."),
            Map.entry("WAITING", "Aguardando o envio pelo WhatsApp."),
            Map.entry(WhatsAppDeliveryStatus.ATTEMPTING.name(), "Enviando para a Meta."),
            Map.entry(WhatsAppDeliveryStatus.ACCEPTED.name(), "Aceito pela Meta. A entrega ainda não foi confirmada."),
            Map.entry(WhatsAppDeliveryStatus.SENT.name(), "Enviado pela Meta. A entrega ainda não foi confirmada."),
            Map.entry(WhatsAppDeliveryStatus.DELIVERED.name(), "Entregue no WhatsApp do administrador."),
            Map.entry(WhatsAppDeliveryStatus.READ.name(), "Entregue e lido no WhatsApp do administrador."),
            Map.entry(WhatsAppDeliveryStatus.FAILED.name(), "Não entregue."),
            Map.entry(WhatsAppDeliveryStatus.REJECTED.name(), "Recusado pela Meta."),
            Map.entry(WhatsAppDeliveryStatus.UNCERTAIN.name(),
                    "Resultado incerto: não há confirmação de que a Meta recebeu. Nada foi reenviado."),
            Map.entry(WhatsAppDeliveryStatus.SKIPPED.name(), "Não enviado."));

    private final WhatsAppDeliveryRepository deliveries;
    private final ReminderSummaryRepository summaries;
    private final ReminderSettingsRepository settings;
    private final MemberNotificationRepository notifications;
    private final ReminderSummaryService composer;
    private final WhatsAppProviderStatus provider;
    private final AuthenticatedUserContextQuery contexts;
    private final Clock clock;
    private final Supplier<UUID> identifiers;

    public WhatsAppDeliveryService(WhatsAppDeliveryRepository deliveries, ReminderSummaryRepository summaries,
            ReminderSettingsRepository settings, MemberNotificationRepository notifications,
            ReminderSummaryService composer, WhatsAppProviderStatus provider, AuthenticatedUserContextQuery contexts,
            Clock clock, Supplier<UUID> identifiers) {
        this.deliveries = Objects.requireNonNull(deliveries);
        this.summaries = Objects.requireNonNull(summaries);
        this.settings = Objects.requireNonNull(settings);
        this.notifications = Objects.requireNonNull(notifications);
        this.composer = Objects.requireNonNull(composer);
        this.provider = Objects.requireNonNull(provider);
        this.contexts = Objects.requireNonNull(contexts);
        this.clock = Objects.requireNonNull(clock);
        this.identifiers = Objects.requireNonNull(identifiers);
    }

    public sealed interface Preparation permits NotPlanned, Skipped, Ready { }

    /** The summary already has a delivery (another worker or an earlier run) or no planned WhatsApp channel. */
    public record NotPlanned() implements Preparation { }

    public record Skipped(WhatsAppSkipReason reason) implements Preparation { }

    /** Claimed: send {@code message} now, outside the transaction, then {@link #record} the result. */
    public record Ready(UUID deliveryId, UUID attemptId, UUID spaceId, UUID summaryId, WhatsAppSender.Message message)
            implements Preparation, TestPreparation { }

    public List<UUID> pending(int limit) {
        return deliveries.plannedSummaries(limit);
    }

    /** RF-ALT-13: revalidation and claim, immediately before the provider call. */
    public Preparation prepare(UUID summaryId, Instant now) {
        var planned = deliveries.planned(summaryId);
        if (planned.isEmpty()) return new NotPlanned();
        var summary = planned.get();
        var current = settings.lock(summary.spaceId(), now);
        var administrator = summaries.activeAdministrator(summary.spaceId());
        var consent = settings.activeConsent(summary.spaceId());
        WhatsAppSkipReason reason = null;
        Optional<ReminderSummary> content = Optional.empty();
        if (administrator.isEmpty() || !administrator.get().equals(summary.recipientUserId()))
            reason = WhatsAppSkipReason.ADMINISTRATOR_CHANGED;
        else if (consent.isEmpty() || !consent.get().userId().equals(summary.recipientUserId()))
            reason = WhatsAppSkipReason.CONSENT_REVOKED;
        else if (current.recipient() == null || !consent.get().recipient().equals(current.recipient()))
            reason = WhatsAppSkipReason.RECIPIENT_CHANGED;
        else if (!current.enabled()) reason = WhatsAppSkipReason.DISABLED;
        else if (!provider.availability().available()) reason = WhatsAppSkipReason.PROVIDER_UNAVAILABLE;
        else if (!windowOpen(summary, current, now)) reason = WhatsAppSkipReason.WINDOW_CLOSED;
        else {
            content = revalidatedContent(summary);
            if (content.isEmpty()) reason = WhatsAppSkipReason.EMPTY;
        }
        var deliveryId = identifiers.get();
        if (reason != null) {
            if (!deliveries.insert(new NewDelivery(deliveryId, summary.spaceId(), Kind.SUMMARY, summaryId, null,
                    summary.recipientUserId(), null, null, WhatsAppDeliveryStatus.SKIPPED, reason.name(), null, null,
                    now))) return new NotPlanned();
            if (reason == WhatsAppSkipReason.PROVIDER_UNAVAILABLE)
                notifications.recordWhatsAppFailure(summary.spaceId(), summaryId,
                        WhatsAppFailureReason.PROVIDER_UNAVAILABLE, now);
            if (reason == WhatsAppSkipReason.WINDOW_CLOSED)
                notifications.recordWhatsAppFailure(summary.spaceId(), summaryId,
                        WhatsAppFailureReason.NOT_SENT_IN_WINDOW, now);
            return new Skipped(reason);
        }
        var recipient = consent.get().recipient();
        var message = content.get();
        if (!deliveries.insert(new NewDelivery(deliveryId, summary.spaceId(), Kind.SUMMARY, summaryId, null,
                summary.recipientUserId(), consent.get().id(), recipient.lastDigits(),
                WhatsAppDeliveryStatus.ATTEMPTING, null, null, message.count(), now))) return new NotPlanned();
        var attemptId = identifiers.get();
        deliveries.insertAttempt(attemptId, deliveryId, 1, now);
        return new Ready(deliveryId, attemptId, summary.spaceId(), summaryId, new WhatsAppSender.Message(Kind.SUMMARY,
                recipient.e164(), WhatsAppSummaryTemplate.parameters(message, summary.scheduledTime(),
                        composer.link(summaryId))));
    }

    /** Stores the provider result and, for a summary, tells the administrator about anything but acceptance. */
    public WhatsAppDeliveryStatus record(Ready ready, SendResult result, Instant now) {
        var accepted = result.outcome() == WhatsAppSender.Outcome.ACCEPTED && result.providerMessageId() != null
                && !result.providerMessageId().isBlank();
        var outcome = accepted ? WhatsAppSender.Outcome.ACCEPTED
                : result.outcome() == WhatsAppSender.Outcome.ACCEPTED ? WhatsAppSender.Outcome.UNCERTAIN
                        : result.outcome();
        var status = switch (outcome) {
            case ACCEPTED -> WhatsAppDeliveryStatus.ACCEPTED;
            case REJECTED, RECIPIENT_INVALID -> WhatsAppDeliveryStatus.REJECTED;
            case UNAVAILABLE -> WhatsAppDeliveryStatus.FAILED;
            case UNCERTAIN -> WhatsAppDeliveryStatus.UNCERTAIN;
        };
        var failure = switch (outcome) {
            case ACCEPTED -> null;
            case REJECTED -> WhatsAppFailureReason.PROVIDER_REJECTED;
            case RECIPIENT_INVALID -> WhatsAppFailureReason.RECIPIENT_INVALID;
            case UNAVAILABLE -> WhatsAppFailureReason.PROVIDER_UNAVAILABLE;
            case UNCERTAIN -> WhatsAppFailureReason.RESULT_UNCERTAIN;
        };
        var finished = deliveries.finish(ready.deliveryId(), ready.attemptId(), status,
                accepted ? result.providerMessageId() : null, result.errorCode(),
                failure == null ? null : failure.name(), now);
        if (finished && failure != null && ready.summaryId() != null)
            notifications.recordWhatsAppFailure(ready.spaceId(), ready.summaryId(), failure, now);
        return status;
    }

    /** RF-ALT-16: an interrupted attempt becomes uncertain; it is never resent blindly. */
    public int expireStale(Instant now) {
        var stale = deliveries.staleAttempts(now.minus(STALE_ATTEMPT));
        for (var delivery : stale)
            if (deliveries.finish(delivery.id(), delivery.attemptId(), WhatsAppDeliveryStatus.UNCERTAIN, null, null,
                    WhatsAppFailureReason.RESULT_UNCERTAIN.name(), now) && delivery.summaryId() != null)
                notifications.recordWhatsAppFailure(delivery.spaceId(), delivery.summaryId(),
                        WhatsAppFailureReason.RESULT_UNCERTAIN, now);
        return stale.size();
    }

    /** One status of a webhook, as the provider reported it (only its code when it failed). */
    public record StatusUpdate(String providerMessageId, String status, Instant occurredAt, String errorCode) { }

    /**
     * {@code retryLater}: a status named a message still unknown while some delivery waits for the provider's
     * answer, so its id may simply not be recorded yet; the provider should deliver the event again.
     */
    public record WebhookOutcome(int applied, int duplicated, int stale, int ignored, boolean retryLater) { }

    /** Applies webhook statuses of known messages, each once, never moving a delivery backwards. */
    public WebhookOutcome applyStatuses(List<StatusUpdate> updates, Instant now) {
        int applied = 0;
        int duplicated = 0;
        int stale = 0;
        int ignored = 0;
        var retry = false;
        for (var update : updates) {
            var status = WhatsAppDeliveryStatus.fromWebhook(update.status());
            if (status.isEmpty() || update.providerMessageId() == null || update.providerMessageId().isBlank()) {
                ignored++;
                continue;
            }
            var delivery = deliveries.lockByProviderMessageId(update.providerMessageId());
            if (delivery.isEmpty()) {
                ignored++;
                retry = retry || deliveries.anyAttempting();
                continue;
            }
            var ref = delivery.get();
            var at = update.occurredAt() == null ? now : update.occurredAt();
            if (!deliveries.recordEvent(update.providerMessageId(), ref.id(), status.get(), at, update.errorCode(),
                    now)) {
                duplicated++;
                continue;
            }
            var advance = ref.status().canAdvanceTo(status.get());
            // A late confirmation of an earlier step still fills its instant, without moving the state back.
            var late = !advance && status.get() != WhatsAppDeliveryStatus.FAILED && confirmed(ref.status());
            if (advance || late) deliveries.confirm(ref.id(), status.get(), at, update.errorCode(), advance, now);
            if (!advance) {
                stale++;
                continue;
            }
            applied++;
            if (status.get() == WhatsAppDeliveryStatus.FAILED && ref.summaryId() != null)
                notifications.recordWhatsAppFailure(ref.spaceId(), ref.summaryId(),
                        WhatsAppFailureReason.DELIVERY_FAILED, now);
        }
        return new WebhookOutcome(applied, duplicated, stale, ignored, retry);
    }

    private static boolean confirmed(WhatsAppDeliveryStatus status) {
        return status == WhatsAppDeliveryStatus.SENT || status == WhatsAppDeliveryStatus.DELIVERED
                || status == WhatsAppDeliveryStatus.READ;
    }

    /** The WhatsApp side of a summary of the actor's space, for the administrator only. */
    public WhatsAppDeliveryView summaryDelivery(String actorEmail, UUID summaryId) {
        var actor = administrator(actorEmail);
        var stored = summaries.find(actor.spaceId(), summaryId).orElseThrow(ReminderSummaryNotFoundException::new);
        var delivery = deliveries.forSummary(actor.spaceId(), summaryId);
        if (delivery.isPresent()) return view(delivery.get());
        var channel = stored.channels().stream().filter(item -> item.channel() == ChannelType.WHATSAPP).findFirst();
        if (channel.isPresent() && channel.get().status() == ChannelStatus.PLANNED)
            return empty("WAITING", null, null);
        var reason = channel.map(StoredSummary.Channel::skipReason).orElse(null);
        return empty("NOT_PLANNED", reason, reason == null ? null : CHANNEL_MESSAGES.get(reason));
    }

    public sealed interface TestPreparation permits ExistingTest, Ready { }

    public record ExistingTest(WhatsAppDeliveryView view) implements TestPreparation { }

    /**
     * RF-ALT-04: a test to the administrator's consented number with the configured test template (no financial
     * data). Idempotent by key; at most one per {@link #TEST_INTERVAL}. The channel does not need to be enabled.
     */
    public TestPreparation prepareTest(String actorEmail, UUID key, Instant now) {
        var actor = administrator(actorEmail);
        var existing = deliveries.test(actor.spaceId(), key);
        if (existing.isPresent()) return new ExistingTest(view(existing.get()));
        var current = settings.lock(actor.spaceId(), now);
        existing = deliveries.test(actor.spaceId(), key);
        if (existing.isPresent()) return new ExistingTest(view(existing.get()));
        var consent = settings.activeConsent(actor.spaceId()).filter(active -> active.userId().equals(actor.userId())
                && active.recipient().equals(current.recipient()));
        if (consent.isEmpty())
            throw new WhatsAppTestUnavailableException("WHATSAPP_CONSENT_REQUIRED",
                    "Cadastre o número e registre o consentimento antes do teste.");
        var availability = provider.availability();
        if (!availability.available())
            throw new WhatsAppTestUnavailableException(availability.code(), availability.message());
        if (!provider.testTemplateConfigured())
            throw new WhatsAppTestUnavailableException("WHATSAPP_TEST_TEMPLATE_MISSING",
                    "Nenhum modelo de teste está configurado no servidor.");
        var last = deliveries.lastTestAt(actor.spaceId());
        if (last.isPresent() && last.get().plus(TEST_INTERVAL).isAfter(now))
            throw new WhatsAppTestUnavailableException("WHATSAPP_TEST_TOO_SOON",
                    "Aguarde um minuto entre dois testes.");
        var deliveryId = identifiers.get();
        var recipient = consent.get().recipient();
        deliveries.insert(new NewDelivery(deliveryId, actor.spaceId(), Kind.TEST, null, key, actor.userId(),
                consent.get().id(), recipient.lastDigits(), WhatsAppDeliveryStatus.ATTEMPTING, null, null, null, now));
        var attemptId = identifiers.get();
        deliveries.insertAttempt(attemptId, deliveryId, 1, now);
        return new Ready(deliveryId, attemptId, actor.spaceId(), null,
                new WhatsAppSender.Message(Kind.TEST, recipient.e164(), List.of()));
    }

    public WhatsAppDeliveryView testView(UUID spaceId, UUID key) {
        return deliveries.test(spaceId, key).map(WhatsAppDeliveryService::view)
                .orElseThrow(IllegalStateException::new);
    }

    public Instant now() {
        return clock.instant();
    }

    private AuthenticatedUserContext administrator(String actorEmail) {
        var actor = contexts.findByEmail(actorEmail);
        if (actor.role() != SpaceRole.ADMINISTRATOR) throw new WhatsAppAdministratorRequiredException();
        return actor;
    }

    /** From the scheduled instant until one hour later or the next slot of the current schedule. */
    private static boolean windowOpen(PlannedSummary summary, StoredReminderSettings current, Instant now) {
        var window = ReminderWindow.of(current.schedule(), ZoneId.of(summary.timeZone()), summary.date(),
                summary.slot());
        var limit = summary.scheduledAt().plus(ReminderWindow.MAXIMUM_DELAY);
        var deadline = window.deadline().isBefore(limit) && window.deadline().isAfter(summary.scheduledAt())
                ? window.deadline() : limit;
        return !now.isBefore(summary.scheduledAt()) && now.isBefore(deadline);
    }

    /**
     * The generated summary reduced to its bills that are still eligible now: paid, cancelled or rescheduled bills
     * leave, and nothing that was not in the generated summary enters (RF-ALT-06/13/14).
     */
    private Optional<ReminderSummary> revalidatedContent(PlannedSummary summary) {
        var generated = summaries.find(summary.spaceId(), summary.summaryId());
        if (generated.isEmpty()) return Optional.empty();
        Set<String> keys = generated.get().summary().items().stream().map(ReminderItem::key)
                .collect(Collectors.toSet());
        return composer.compose(summary.spaceId(), summary.date(), summary.slot())
                .flatMap(now -> ReminderSummary.compose(summary.date(), summary.slot(),
                        now.items().stream().filter(item -> keys.contains(item.key())).toList()));
    }

    static WhatsAppDeliveryView view(StoredDelivery delivery) {
        var state = delivery.status().name();
        var reason = delivery.skipReason() != null ? delivery.skipReason() : delivery.failureCode();
        var reasonMessage = delivery.skipReason() != null ? SKIP_MESSAGES.get(delivery.skipReason())
                : WhatsAppFailureReason.fromCode(delivery.failureCode()).map(WhatsAppFailureReason::message)
                        .orElse(null);
        return new WhatsAppDeliveryView(state, STATE_MESSAGES.get(state), delivery.kind().name(), reason,
                reasonMessage, delivery.recipientLastDigits() == null ? null
                        : "+55 ** *****-" + delivery.recipientLastDigits(),
                delivery.itemCount(), delivery.createdAt(), delivery.attemptedAt(), delivery.acceptedAt(),
                delivery.sentAt(), delivery.deliveredAt(), delivery.readAt(), delivery.failedAt(),
                delivery.attempts().stream().map(attempt -> new WhatsAppDeliveryView.Attempt(attempt.number(),
                        attempt.startedAt(), attempt.finishedAt(), attempt.outcome())).toList());
    }

    private static WhatsAppDeliveryView empty(String state, String reason, String reasonMessage) {
        return new WhatsAppDeliveryView(state, STATE_MESSAGES.get(state), Kind.SUMMARY.name(), reason, reasonMessage,
                null, null, null, null, null, null, null, null, null, List.of());
    }
}
