package com.malyah.accountmanager.notifications.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import com.malyah.accountmanager.identity.application.AdministrationTransferHandler;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContext;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContextQuery;
import com.malyah.accountmanager.identity.application.FinancialMemberAccess;
import com.malyah.accountmanager.identity.domain.SpaceRole;
import com.malyah.accountmanager.notifications.application.SettingsEvent.Type;
import com.malyah.accountmanager.notifications.application.port.ReminderSettingsRepository;
import com.malyah.accountmanager.notifications.application.port.WhatsAppProviderStatus;
import com.malyah.accountmanager.notifications.domain.ConsentRevocationReason;
import com.malyah.accountmanager.notifications.domain.NotificationValidationException;
import com.malyah.accountmanager.notifications.domain.ReminderSchedule;
import com.malyah.accountmanager.notifications.domain.WhatsAppChannelState;
import com.malyah.accountmanager.notifications.domain.WhatsAppRecipient;

/**
 * H08.1 (RF-ALT-01 to RF-ALT-05, RF-ACC-10). Only the active administrator changes the reminder times and the
 * WhatsApp channel; both members read them. Every change locks the space (the same lock as membership changes, so
 * it never interleaves with a transfer), checks the version the administrator saw and records an audit event
 * without the full phone number. Consent is only ever created by an explicit acceptance, and it stops being usable
 * when the number changes, when it is revoked and when the administration is transferred.
 */
public final class ReminderSettingsService implements ReminderSettingsUseCase, AdministrationTransferHandler {
    public static final String CONSENT_TEXT_VERSION = "WHATSAPP-RESUMOS-V1";
    static final String CONSENT_TEXT = "Autorizo o account_Manager a enviar, a partir do número dedicado do "
            + "aplicativo, resumos de contas a vencer e vencidas para o meu WhatsApp neste número, nos horários "
            + "configurados. Posso desativar o canal ou revogar este consentimento a qualquer momento nesta tela. "
            + "Pagamentos continuam sendo registrados somente no aplicativo.";
    private static final int EVENT_LIMIT = 50;

    private final ReminderSettingsRepository repository;
    private final AuthenticatedUserContextQuery contexts;
    private final FinancialMemberAccess members;
    private final WhatsAppProviderStatus provider;
    private final Clock clock;
    private final Supplier<UUID> identifiers;

    public ReminderSettingsService(ReminderSettingsRepository repository, AuthenticatedUserContextQuery contexts,
            FinancialMemberAccess members, WhatsAppProviderStatus provider, Clock clock, Supplier<UUID> identifiers) {
        this.repository = Objects.requireNonNull(repository);
        this.contexts = Objects.requireNonNull(contexts);
        this.members = Objects.requireNonNull(members);
        this.provider = Objects.requireNonNull(provider);
        this.clock = Objects.requireNonNull(clock);
        this.identifiers = Objects.requireNonNull(identifiers);
    }

    @Override
    public ReminderSettingsView view(String actorEmail) {
        var actor = contexts.findByEmail(actorEmail);
        return view(actor);
    }

    @Override
    public ReminderSettingsView.EventList events(String actorEmail) {
        var actor = contexts.findByEmail(actorEmail);
        requireAdministrator(actor);
        return new ReminderSettingsView.EventList(repository.events(actor.spaceId(), EVENT_LIMIT).stream()
                .map(event -> new ReminderSettingsView.EventItem(event.type().name(), event.actorDisplayName(),
                        event.occurredAt(), event.fromVersion(), event.toVersion(), event.detail()))
                .toList());
    }

    @Override
    public ReminderSettingsView changeSchedule(String actorEmail, ReminderSettingsCommand command) {
        return change(actorEmail, command, "SCHEDULE", () -> hash(command.firstTime(), command.secondTime()),
                (actor, current, now) -> {
                    var schedule = ReminderSchedule.parse(command.firstTime(), command.secondTime());
                    if (schedule.equals(current.schedule())) return current;
                    var next = current.next(schedule, current.recipient(), current.enabled(), now);
                    save(actor, next);
                    event(actor, Type.SCHEDULE_CHANGED, current, next,
                            current.schedule().text() + " -> " + schedule.text(), now);
                    return next;
                });
    }

    @Override
    public ReminderSettingsView changeRecipient(String actorEmail, ReminderSettingsCommand command) {
        return change(actorEmail, command, "RECIPIENT", () -> hash(command.phone()), (actor, current, now) -> {
            var recipient = WhatsAppRecipient.parse(command.phone());
            if (recipient.equals(current.recipient())) return current;
            var next = current.next(current.schedule(), recipient, false, now);
            // A consent covers one number: a new number needs a new, explicit consent.
            var revoked = revokeActive(actor, ConsentRevocationReason.RECIPIENT_CHANGED, now);
            save(actor, next);
            event(actor, Type.RECIPIENT_CHANGED, current, next, masked(current.recipient()) + " -> "
                    + recipient.masked(), now);
            if (revoked) event(actor, Type.CONSENT_REVOKED, current, next,
                    ConsentRevocationReason.RECIPIENT_CHANGED.name(), now);
            if (current.enabled()) event(actor, Type.CHANNEL_DISABLED, current, next,
                    ConsentRevocationReason.RECIPIENT_CHANGED.name(), now);
            return next;
        });
    }

    @Override
    public ReminderSettingsView grantConsent(String actorEmail, ReminderSettingsCommand command) {
        return change(actorEmail, command, "GRANT_CONSENT",
                () -> hash(command.phone(), String.valueOf(command.flag())), (actor, current, now) -> {
            if (!Boolean.TRUE.equals(command.flag()))
                throw new NotificationValidationException("WHATSAPP_CONSENT_NOT_ACCEPTED", "accepted",
                        "Marque o aceite do texto para autorizar os resumos pelo WhatsApp.");
            var confirmed = WhatsAppRecipient.parse(command.phone());
            if (current.recipient() == null)
                throw new WhatsAppActivationRequiredException(WhatsAppActivationRequiredException.RECIPIENT_REQUIRED,
                        "Informe o número que vai receber os resumos antes de autorizar.");
            if (!confirmed.equals(current.recipient())) throw new WhatsAppRecipientMismatchException();
            var active = repository.activeConsent(actor.spaceId());
            if (active.filter(consent -> usable(consent, actor.userId(), current.recipient())).isPresent())
                return current;
            // Defensive: a consent of someone else or of another number is never reused.
            active.ifPresent(consent -> repository.revokeConsent(consent.id(), actor.userId(),
                    consent.recipient().equals(current.recipient())
                            ? ConsentRevocationReason.ADMINISTRATION_TRANSFERRED
                            : ConsentRevocationReason.RECIPIENT_CHANGED, now));
            repository.insertConsent(new StoredConsent(identifiers.get(), actor.userId(), actor.displayName(),
                    current.recipient(), CONSENT_TEXT_VERSION, now), actor.spaceId());
            var next = current.next(current.schedule(), current.recipient(), current.enabled(), now);
            save(actor, next);
            event(actor, Type.CONSENT_GRANTED, current, next, CONSENT_TEXT_VERSION + " " + current.recipient().masked(),
                    now);
            return next;
        });
    }

    @Override
    public ReminderSettingsView revokeConsent(String actorEmail, ReminderSettingsCommand command) {
        return change(actorEmail, command, "REVOKE_CONSENT", () -> hash(), (actor, current, now) -> {
            if (repository.activeConsent(actor.spaceId()).isEmpty()) throw new WhatsAppConsentNotActiveException();
            revokeActive(actor, ConsentRevocationReason.REVOKED_BY_ADMINISTRATOR, now);
            var next = current.next(current.schedule(), current.recipient(), false, now);
            save(actor, next);
            event(actor, Type.CONSENT_REVOKED, current, next, ConsentRevocationReason.REVOKED_BY_ADMINISTRATOR.name(),
                    now);
            if (current.enabled()) event(actor, Type.CHANNEL_DISABLED, current, next,
                    ConsentRevocationReason.REVOKED_BY_ADMINISTRATOR.name(), now);
            return next;
        });
    }

    @Override
    public ReminderSettingsView changeChannel(String actorEmail, ReminderSettingsCommand command) {
        return change(actorEmail, command, "CHANNEL", () -> hash(String.valueOf(command.flag())),
                (actor, current, now) -> {
            if (command.flag() == null)
                throw new NotificationValidationException("WHATSAPP_CHANNEL_INVALID", "enabled",
                        "Informe se o canal deve ficar ativo.");
            boolean enable = command.flag();
            if (enable == current.enabled()) return current;
            if (enable) {
                if (current.recipient() == null)
                    throw new WhatsAppActivationRequiredException(WhatsAppActivationRequiredException.RECIPIENT_REQUIRED,
                            "Informe o número que vai receber os resumos antes de ativar.");
                // Activation never implies consent: it needs an explicit one for this number and administrator.
                if (repository.activeConsent(actor.spaceId())
                        .filter(consent -> usable(consent, actor.userId(), current.recipient())).isEmpty())
                    throw new WhatsAppActivationRequiredException(WhatsAppActivationRequiredException.CONSENT_REQUIRED,
                            "Autorize o recebimento pelo WhatsApp antes de ativar o canal.");
            }
            var next = current.next(current.schedule(), current.recipient(), enable, now);
            save(actor, next);
            event(actor, enable ? Type.CHANNEL_ENABLED : Type.CHANNEL_DISABLED, current, next,
                    enable ? "ENABLED" : "DISABLED_BY_ADMINISTRATOR", now);
            return next;
        });
    }

    /**
     * RF-ACC-10 / T07: runs inside the transfer transaction, which already holds the space lock. The previous
     * administrator's consent and number are not transferred: the new administrator starts without them.
     */
    @Override
    public void afterAdministrationTransferred(UUID spaceId, UUID previousAdministratorId, UUID newAdministratorId,
            Instant occurredAt) {
        var stored = repository.find(spaceId);
        var active = repository.activeConsent(spaceId);
        if (stored.map(settings -> settings.recipient() == null && !settings.enabled()).orElse(true)
                && active.isEmpty()) return;
        var current = repository.lock(spaceId, occurredAt);
        active.ifPresent(consent -> repository.revokeConsent(consent.id(), previousAdministratorId,
                ConsentRevocationReason.ADMINISTRATION_TRANSFERRED, occurredAt));
        var next = current.next(current.schedule(), null, false, occurredAt);
        if (!repository.update(next, previousAdministratorId))
            throw new IllegalStateException("The locked reminder settings changed during the transfer.");
        var reason = ConsentRevocationReason.ADMINISTRATION_TRANSFERRED.name();
        if (current.recipient() != null) appendEvent(spaceId, previousAdministratorId, Type.RECIPIENT_CHANGED,
                current, next, masked(current.recipient()) + " -> nenhum (" + reason + ")", occurredAt);
        if (active.isPresent()) appendEvent(spaceId, previousAdministratorId, Type.CONSENT_REVOKED, current, next,
                reason, occurredAt);
        if (current.enabled()) appendEvent(spaceId, previousAdministratorId, Type.CHANNEL_DISABLED, current, next,
                reason, occurredAt);
    }

    private ReminderSettingsView change(String actorEmail, ReminderSettingsCommand command, String operation,
            Supplier<String> requestHash, Mutation mutation) {
        Objects.requireNonNull(command, "command");
        if (command.idempotencyKey() == null)
            throw new NotificationValidationException("REMINDER_SETTINGS_INVALID", "Idempotency-Key",
                    "Informe uma chave de repetição válida.");
        if (command.expectedVersion() == null || command.expectedVersion() < 0)
            throw new NotificationValidationException("REMINDER_SETTINGS_INVALID", "expectedVersion",
                    "Informe a versão das configurações que você revisou.");
        var initial = contexts.findByEmail(actorEmail);
        requireAdministrator(initial);
        // Serializes with the administration transfer and member departure; the role is read again after it.
        members.requireActiveParticipants(initial.spaceId(), initial.userId(), null);
        var actor = contexts.findByEmail(actorEmail);
        requireAdministrator(actor);
        var now = clock.instant();
        var claim = repository.claim(actor.spaceId(), actor.userId(), operation, command.idempotencyKey(),
                hash(operation, String.valueOf(command.expectedVersion()), requestHash.get()), now);
        if (claim.replayed()) return view(actor);
        var current = repository.lock(actor.spaceId(), now);
        if (current.version() != command.expectedVersion())
            throw new ReminderSettingsVersionConflictException();
        var result = mutation.apply(actor, current, now);
        repository.complete(actor.spaceId(), actor.userId(), operation, command.idempotencyKey(), result.version(),
                now);
        return view(actor);
    }

    private ReminderSettingsView view(AuthenticatedUserContext actor) {
        var settings = repository.find(actor.spaceId()).orElseGet(() -> StoredReminderSettings.defaults(actor.spaceId()));
        var consent = repository.activeConsent(actor.spaceId())
                .filter(active -> settings.recipient() != null && active.recipient().equals(settings.recipient()));
        var availability = provider.availability();
        var manager = actor.role() == SpaceRole.ADMINISTRATOR;
        var recipient = settings.recipient();
        var state = WhatsAppChannelState.of(recipient != null, consent.isPresent(), settings.enabled(),
                settings.suspended(), availability.available());
        var consentView = consent.map(active -> new ReminderSettingsView.Consent(true,
                        manager ? active.grantedAt() : null, manager ? active.grantedByDisplayName() : null,
                        active.recipient().lastDigits()))
                .orElse(new ReminderSettingsView.Consent(false, null, null, null));
        return new ReminderSettingsView(manager, actor.timeZone(), settings.version(), settings.updatedAt(),
                new ReminderSettingsView.Schedule(ReminderSchedule.format(settings.schedule().first()),
                        ReminderSchedule.format(settings.schedule().second()),
                        ReminderSchedule.format(ReminderSchedule.DEFAULT.first()),
                        ReminderSchedule.format(ReminderSchedule.DEFAULT.second())),
                new ReminderSettingsView.WhatsApp(recipient != null, manager && recipient != null ? recipient.e164() : null,
                        manager && recipient != null ? recipient.formatted() : null,
                        recipient == null ? null : recipient.lastDigits(), settings.enabled(), consentView,
                        new ReminderSettingsView.Provider(availability.available(), availability.code(),
                                availability.message()),
                        state.name(), CONSENT_TEXT_VERSION, CONSENT_TEXT, settings.suspended()
                                ? new ReminderSettingsView.Suspension(settings.suspension().name(),
                                        settings.suspendedAt(), settings.suspension().guidance())
                                : null));
    }

    private static boolean usable(StoredConsent consent, UUID administratorId, WhatsAppRecipient recipient) {
        return consent.userId().equals(administratorId) && consent.recipient().equals(recipient);
    }

    private boolean revokeActive(AuthenticatedUserContext actor, ConsentRevocationReason reason, Instant now) {
        Optional<StoredConsent> active = repository.activeConsent(actor.spaceId());
        active.ifPresent(consent -> repository.revokeConsent(consent.id(), actor.userId(), reason, now));
        return active.isPresent();
    }

    private void save(AuthenticatedUserContext actor, StoredReminderSettings next) {
        if (!repository.update(next, actor.userId()))
            throw new ReminderSettingsVersionConflictException();
    }

    private void event(AuthenticatedUserContext actor, Type type, StoredReminderSettings from,
            StoredReminderSettings to, String detail, Instant at) {
        appendEvent(actor.spaceId(), actor.userId(), type, from, to, detail, at);
    }

    private void appendEvent(UUID spaceId, UUID actorId, Type type, StoredReminderSettings from,
            StoredReminderSettings to, String detail, Instant at) {
        repository.appendEvent(new SettingsEvent(identifiers.get(), spaceId, actorId, null, type, from.version(),
                to.version(), detail, at));
    }

    private static String masked(WhatsAppRecipient recipient) {
        return recipient == null ? "nenhum" : recipient.masked();
    }

    private static void requireAdministrator(AuthenticatedUserContext actor) {
        if (actor.role() != SpaceRole.ADMINISTRATOR) throw new NotificationAdministratorRequiredException();
    }

    static String hash(String... parts) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (var part : parts) {
                digest.update(String.valueOf(part).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @FunctionalInterface
    private interface Mutation {
        StoredReminderSettings apply(AuthenticatedUserContext actor, StoredReminderSettings current, Instant now);
    }
}
