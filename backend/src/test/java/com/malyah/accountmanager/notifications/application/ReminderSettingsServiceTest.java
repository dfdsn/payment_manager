package com.malyah.accountmanager.notifications.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.malyah.accountmanager.identity.application.AuthenticatedUserContext;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContextNotFoundException;
import com.malyah.accountmanager.identity.domain.SpaceRole;
import com.malyah.accountmanager.notifications.application.port.ReminderSettingsRepository;
import com.malyah.accountmanager.notifications.application.port.WhatsAppProviderStatus;
import com.malyah.accountmanager.notifications.domain.ConsentRevocationReason;
import com.malyah.accountmanager.notifications.domain.NotificationValidationException;
import com.malyah.accountmanager.notifications.domain.WhatsAppRecipient;

/** H08.1 rules with an in-memory repository; the PostgreSQL matrix is {@code ReminderSettingsPostgresIT}. */
class ReminderSettingsServiceTest {
    static final Instant NOW = Instant.parse("2026-09-29T12:00:00Z");
    static final UUID SPACE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID ADMIN = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    static final UUID GUEST = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    static final String A = "admin@example.com";
    static final String G = "guest@example.com";
    static final String N1 = "(11) 98765-4321";
    static final String N2 = "+55 21 99876-5432";

    private final Map<String, AuthenticatedUserContext> people = new HashMap<>();
    private final List<String> locks = new ArrayList<>();
    private final AtomicInteger ids = new AtomicInteger();
    private FakeRepository repository;
    private boolean providerAvailable;
    private ReminderSettingsService service;

    @BeforeEach
    void setUp() {
        people.put(A, context(ADMIN, "Admin", A, SpaceRole.ADMINISTRATOR));
        people.put(G, context(GUEST, "Convidado", G, SpaceRole.GUEST));
        repository = new FakeRepository();
        providerAvailable = false;
        service = new ReminderSettingsService(repository, email -> {
            var person = people.get(email);
            if (person == null) throw new AuthenticatedUserContextNotFoundException();
            return person;
        }, (space, actor, payer) -> locks.add(space + ":" + actor),
                () -> new WhatsAppProviderStatus.Availability(providerAvailable, providerAvailable ? "OK" : "OFF",
                        providerAvailable ? "ok" : "indisponível"),
                Clock.fixed(NOW, ZoneOffset.UTC), () -> new UUID(0, ids.incrementAndGet()));
    }

    static AuthenticatedUserContext context(UUID id, String name, String email, SpaceRole role) {
        return new AuthenticatedUserContext(id, name, email, SPACE, "Casa", role, "BRL", "pt-BR", "America/Sao_Paulo");
    }

    @Test
    void initialViewShowsDefaultsAndNothingEnabled() {
        var view = service.view(A);
        assertThat(view.canManage()).isTrue();
        assertThat(view.version()).isZero();
        assertThat(view.updatedAt()).isNull();
        assertThat(view.timeZone()).isEqualTo("America/Sao_Paulo");
        assertThat(view.schedule()).isEqualTo(new ReminderSettingsView.Schedule("09:00", "18:00", "09:00", "18:00"));
        assertThat(view.whatsapp().hasRecipient()).isFalse();
        assertThat(view.whatsapp().recipient()).isNull();
        assertThat(view.whatsapp().recipientLastDigits()).isNull();
        assertThat(view.whatsapp().enabled()).isFalse();
        assertThat(view.whatsapp().consent().active()).isFalse();
        assertThat(view.whatsapp().state()).isEqualTo("RECIPIENT_REQUIRED");
        assertThat(view.whatsapp().provider()).isEqualTo(new ReminderSettingsView.Provider(false, "OFF", "indisponível"));
        assertThat(view.whatsapp().consentTextVersion()).isEqualTo("WHATSAPP-RESUMOS-V1");
        assertThat(view.whatsapp().consentText()).contains("revogar");
        assertThat(repository.stored).isNull();
    }

    @Test
    void fullAdministratorFlowRecordsEventsWithoutTheFullNumber() {
        var recipient = service.changeRecipient(A, ReminderSettingsCommand.recipient(0L, N1, key()));
        assertThat(recipient.version()).isOne();
        assertThat(recipient.updatedAt()).isEqualTo(NOW);
        assertThat(recipient.whatsapp().recipient()).isEqualTo("+5511987654321");
        assertThat(recipient.whatsapp().recipientFormatted()).isEqualTo("+55 11 98765-4321");
        assertThat(recipient.whatsapp().state()).isEqualTo("CONSENT_REQUIRED");
        assertThat(locks).containsExactly(SPACE + ":" + ADMIN);

        assertThatThrownBy(() -> service.changeChannel(A, ReminderSettingsCommand.channel(1L, true, key())))
                .isInstanceOfSatisfying(WhatsAppActivationRequiredException.class,
                        error -> assertThat(error.code()).isEqualTo("WHATSAPP_CONSENT_REQUIRED"));

        var consent = service.grantConsent(A, ReminderSettingsCommand.consent(1L, "11987654321", true, key()));
        assertThat(consent.version()).isEqualTo(2);
        assertThat(consent.whatsapp().consent()).isEqualTo(new ReminderSettingsView.Consent(true, NOW, "Admin", "4321"));
        assertThat(consent.whatsapp().state()).isEqualTo("DISABLED");
        assertThat(repository.consents).hasSize(1);
        assertThat(repository.consents.getFirst().textVersion()).isEqualTo("WHATSAPP-RESUMOS-V1");

        var enabled = service.changeChannel(A, ReminderSettingsCommand.channel(2L, true, key()));
        assertThat(enabled.version()).isEqualTo(3);
        assertThat(enabled.whatsapp().enabled()).isTrue();
        assertThat(enabled.whatsapp().state()).isEqualTo("PROVIDER_UNAVAILABLE");
        providerAvailable = true;
        assertThat(service.view(A).whatsapp().state()).isEqualTo("READY");
        providerAvailable = false;

        var disabled = service.changeChannel(A, ReminderSettingsCommand.channel(3L, false, key()));
        assertThat(disabled.version()).isEqualTo(4);
        assertThat(disabled.whatsapp().state()).isEqualTo("DISABLED");
        assertThat(disabled.whatsapp().consent().active()).isTrue();

        assertThat(repository.events).extracting(SettingsEvent::type).containsExactly(
                SettingsEvent.Type.RECIPIENT_CHANGED, SettingsEvent.Type.CONSENT_GRANTED,
                SettingsEvent.Type.CHANNEL_ENABLED, SettingsEvent.Type.CHANNEL_DISABLED);
        assertThat(repository.events).extracting(SettingsEvent::detail).containsExactly(
                "nenhum -> +55 ** *****-4321", "WHATSAPP-RESUMOS-V1 +55 ** *****-4321", "ENABLED",
                "DISABLED_BY_ADMINISTRATOR");
        assertThat(repository.events).allSatisfy(event -> {
            assertThat(event.detail()).doesNotContain("98765");
            assertThat(event.actorId()).isEqualTo(ADMIN);
            assertThat(event.toVersion()).isEqualTo(event.fromVersion() + 1);
            assertThat(event.occurredAt()).isEqualTo(NOW);
        });
        var events = service.events(A).items();
        assertThat(events).hasSize(4);
        assertThat(events.getFirst().type()).isEqualTo("RECIPIENT_CHANGED");
        assertThat(events.getFirst().actorDisplayName()).isEqualTo("Admin");
    }

    @Test
    void guestReadsWithoutTheNumberAndCannotChangeAnything() {
        service.changeRecipient(A, ReminderSettingsCommand.recipient(0L, N1, key()));
        service.grantConsent(A, ReminderSettingsCommand.consent(1L, N1, true, key()));
        var view = service.view(G);
        assertThat(view.canManage()).isFalse();
        assertThat(view.whatsapp().hasRecipient()).isTrue();
        assertThat(view.whatsapp().recipient()).isNull();
        assertThat(view.whatsapp().recipientFormatted()).isNull();
        assertThat(view.whatsapp().recipientLastDigits()).isEqualTo("4321");
        assertThat(view.whatsapp().consent()).isEqualTo(new ReminderSettingsView.Consent(true, null, null, "4321"));
        var before = repository.stored;
        var eventCount = repository.events.size();
        locks.clear();
        assertThatThrownBy(() -> service.changeSchedule(G, ReminderSettingsCommand.schedule(2L, "08:00", "19:00", key())))
                .isInstanceOf(NotificationAdministratorRequiredException.class);
        assertThatThrownBy(() -> service.changeRecipient(G, ReminderSettingsCommand.recipient(2L, N2, key())))
                .isInstanceOf(NotificationAdministratorRequiredException.class);
        assertThatThrownBy(() -> service.grantConsent(G, ReminderSettingsCommand.consent(2L, N1, true, key())))
                .isInstanceOf(NotificationAdministratorRequiredException.class);
        assertThatThrownBy(() -> service.revokeConsent(G, ReminderSettingsCommand.revocation(2L, key())))
                .isInstanceOf(NotificationAdministratorRequiredException.class);
        assertThatThrownBy(() -> service.changeChannel(G, ReminderSettingsCommand.channel(2L, true, key())))
                .isInstanceOf(NotificationAdministratorRequiredException.class);
        assertThatThrownBy(() -> service.events(G)).isInstanceOf(NotificationAdministratorRequiredException.class);
        assertThatThrownBy(() -> service.view("nobody@example.com"))
                .isInstanceOf(AuthenticatedUserContextNotFoundException.class);
        assertThat(repository.stored).isEqualTo(before);
        assertThat(repository.events).hasSize(eventCount);
        assertThat(locks).isEmpty();
        assertThat(repository.claims).hasSize(2);
    }

    @Test
    void roleIsCheckedAgainAfterTheSpaceLock() {
        var demoted = context(ADMIN, "Admin", A, SpaceRole.GUEST);
        service = new ReminderSettingsService(repository, new com.malyah.accountmanager.identity.application
                .AuthenticatedUserContextQuery() {
            int calls;

            @Override
            public AuthenticatedUserContext findByEmail(String email) {
                return ++calls == 1 ? people.get(A) : demoted;
            }
        }, (space, actor, payer) -> { }, () -> new WhatsAppProviderStatus.Availability(false, "OFF", "x"),
                Clock.fixed(NOW, ZoneOffset.UTC), UUID::randomUUID);
        assertThatThrownBy(() -> service.changeSchedule(A, ReminderSettingsCommand.schedule(0L, "08:00", "19:00", key())))
                .isInstanceOf(NotificationAdministratorRequiredException.class);
        assertThat(repository.stored).isNull();
        assertThat(repository.claims).isEmpty();
    }

    @Test
    void consentRequiresExplicitAcceptanceOfTheRegisteredNumber() {
        assertThatThrownBy(() -> service.grantConsent(A, ReminderSettingsCommand.consent(0L, N1, true, key())))
                .isInstanceOfSatisfying(WhatsAppActivationRequiredException.class,
                        error -> assertThat(error.code()).isEqualTo("WHATSAPP_RECIPIENT_REQUIRED"));
        assertThatThrownBy(() -> service.changeChannel(A, ReminderSettingsCommand.channel(0L, true, key())))
                .isInstanceOfSatisfying(WhatsAppActivationRequiredException.class,
                        error -> assertThat(error.code()).isEqualTo("WHATSAPP_RECIPIENT_REQUIRED"));
        service.changeRecipient(A, ReminderSettingsCommand.recipient(0L, N1, key()));
        assertThatThrownBy(() -> service.grantConsent(A, ReminderSettingsCommand.consent(1L, N1, false, key())))
                .isInstanceOfSatisfying(NotificationValidationException.class,
                        error -> assertThat(error.code()).isEqualTo("WHATSAPP_CONSENT_NOT_ACCEPTED"));
        assertThatThrownBy(() -> service.grantConsent(A, ReminderSettingsCommand.consent(1L, N1, null, key())))
                .isInstanceOf(NotificationValidationException.class);
        assertThatThrownBy(() -> service.grantConsent(A, ReminderSettingsCommand.consent(1L, N2, true, key())))
                .isInstanceOf(WhatsAppRecipientMismatchException.class);
        assertThatThrownBy(() -> service.grantConsent(A, ReminderSettingsCommand.consent(1L, "123", true, key())))
                .isInstanceOf(NotificationValidationException.class);
        assertThat(repository.consents).isEmpty();
        assertThat(repository.stored.version()).isOne();

        service.grantConsent(A, ReminderSettingsCommand.consent(1L, N1, true, key()));
        var again = service.grantConsent(A, ReminderSettingsCommand.consent(2L, N1, true, key()));
        assertThat(again.version()).isEqualTo(2);
        assertThat(repository.consents).hasSize(1);
        assertThat(repository.events).hasSize(2);
    }

    @Test
    void revocationDisablesTheChannelAndANewConsentIsANewRecord() {
        enabledWithConsent();
        assertThatThrownBy(() -> service.revokeConsent(A, ReminderSettingsCommand.revocation(2L, key())))
                .isInstanceOf(ReminderSettingsVersionConflictException.class);
        var revoked = service.revokeConsent(A, ReminderSettingsCommand.revocation(3L, key()));
        assertThat(revoked.version()).isEqualTo(4);
        assertThat(revoked.whatsapp().enabled()).isFalse();
        assertThat(revoked.whatsapp().state()).isEqualTo("CONSENT_REQUIRED");
        assertThat(repository.revocations).containsExactly(
                new Revocation(repository.consents.getFirst().id(), ADMIN, ConsentRevocationReason.REVOKED_BY_ADMINISTRATOR));
        assertThat(repository.events.subList(3, 5)).extracting(SettingsEvent::type, SettingsEvent::detail).containsExactly(
                org.assertj.core.groups.Tuple.tuple(SettingsEvent.Type.CONSENT_REVOKED, "REVOKED_BY_ADMINISTRATOR"),
                org.assertj.core.groups.Tuple.tuple(SettingsEvent.Type.CHANNEL_DISABLED, "REVOKED_BY_ADMINISTRATOR"));
        assertThatThrownBy(() -> service.revokeConsent(A, ReminderSettingsCommand.revocation(4L, key())))
                .isInstanceOf(WhatsAppConsentNotActiveException.class);

        service.grantConsent(A, ReminderSettingsCommand.consent(4L, N1, true, key()));
        assertThat(repository.consents).hasSize(2);
        assertThat(repository.activeConsent(SPACE)).map(StoredConsent::id).contains(repository.consents.get(1).id());
        var disabledRevocation = service.revokeConsent(A, ReminderSettingsCommand.revocation(5L, key()));
        assertThat(disabledRevocation.version()).isEqualTo(6);
        assertThat(repository.events.getLast().type()).isEqualTo(SettingsEvent.Type.CONSENT_REVOKED);
    }

    @Test
    void changingTheNumberRevokesTheConsentAndDisablesTheChannel() {
        enabledWithConsent();
        var changed = service.changeRecipient(A, ReminderSettingsCommand.recipient(3L, N2, key()));
        assertThat(changed.version()).isEqualTo(4);
        assertThat(changed.whatsapp().recipient()).isEqualTo("+5521998765432");
        assertThat(changed.whatsapp().enabled()).isFalse();
        assertThat(changed.whatsapp().consent().active()).isFalse();
        assertThat(changed.whatsapp().state()).isEqualTo("CONSENT_REQUIRED");
        assertThat(repository.revocations).extracting(Revocation::reason)
                .containsExactly(ConsentRevocationReason.RECIPIENT_CHANGED);
        assertThat(repository.events.subList(3, 6)).extracting(SettingsEvent::type, SettingsEvent::detail).containsExactly(
                org.assertj.core.groups.Tuple.tuple(SettingsEvent.Type.RECIPIENT_CHANGED,
                        "+55 ** *****-4321 -> +55 ** *****-5432"),
                org.assertj.core.groups.Tuple.tuple(SettingsEvent.Type.CONSENT_REVOKED, "RECIPIENT_CHANGED"),
                org.assertj.core.groups.Tuple.tuple(SettingsEvent.Type.CHANNEL_DISABLED, "RECIPIENT_CHANGED"));

        var same = service.changeRecipient(A, ReminderSettingsCommand.recipient(4L, "21998765432", key()));
        assertThat(same.version()).isEqualTo(4);
        assertThat(repository.events).hasSize(6);

        var withoutConsent = service.changeRecipient(A, ReminderSettingsCommand.recipient(4L, N1, key()));
        assertThat(withoutConsent.version()).isEqualTo(5);
        assertThat(repository.events).hasSize(7);
        assertThat(repository.revocations).hasSize(1);
    }

    @Test
    void scheduleChangesKeepOrderAndIgnoreRepeatedValues() {
        var changed = service.changeSchedule(A, ReminderSettingsCommand.schedule(0L, "08:30", "20:00", key()));
        assertThat(changed.schedule().firstTime()).isEqualTo("08:30");
        assertThat(changed.schedule().secondTime()).isEqualTo("20:00");
        assertThat(changed.version()).isOne();
        assertThat(repository.events.getFirst().detail()).isEqualTo("09:00/18:00 -> 08:30/20:00");
        assertThatThrownBy(() -> service.changeSchedule(A, ReminderSettingsCommand.schedule(1L, "18:00", "09:00", key())))
                .isInstanceOfSatisfying(NotificationValidationException.class,
                        error -> assertThat(error.code()).isEqualTo("REMINDER_SCHEDULE_INVALID"));
        assertThatThrownBy(() -> service.changeSchedule(A, ReminderSettingsCommand.schedule(1L, "25:00", "20:00", key())))
                .isInstanceOfSatisfying(NotificationValidationException.class,
                        error -> assertThat(error.code()).isEqualTo("REMINDER_SCHEDULE_INVALID_FORMAT"));
        var same = service.changeSchedule(A, ReminderSettingsCommand.schedule(1L, "08:30", "20:00", key()));
        assertThat(same.version()).isOne();
        assertThat(repository.events).hasSize(1);
        assertThatThrownBy(() -> service.changeSchedule(A, ReminderSettingsCommand.schedule(0L, "07:00", "20:00", key())))
                .isInstanceOf(ReminderSettingsVersionConflictException.class);
        assertThat(repository.stored.schedule().text()).isEqualTo("08:30/20:00");
    }

    @Test
    void channelChangesNeedAFlagAndIgnoreTheCurrentState() {
        enabledWithConsent();
        assertThatThrownBy(() -> service.changeChannel(A, ReminderSettingsCommand.channel(3L, null, key())))
                .isInstanceOfSatisfying(NotificationValidationException.class,
                        error -> assertThat(error.code()).isEqualTo("WHATSAPP_CHANNEL_INVALID"));
        assertThat(service.changeChannel(A, ReminderSettingsCommand.channel(3L, true, key())).version()).isEqualTo(3);
        service.changeChannel(A, ReminderSettingsCommand.channel(3L, false, key()));
        assertThat(service.changeChannel(A, ReminderSettingsCommand.channel(4L, false, key())).version()).isEqualTo(4);
    }

    @Test
    void consentOfAnotherAdministratorIsNeverReused() {
        service.changeRecipient(A, ReminderSettingsCommand.recipient(0L, N1, key()));
        var foreign = new StoredConsent(UUID.randomUUID(), GUEST, "Convidado", WhatsAppRecipient.parse(N1),
                "WHATSAPP-RESUMOS-V1", NOW.minusSeconds(60));
        repository.insertConsent(foreign, SPACE);
        assertThatThrownBy(() -> service.changeChannel(A, ReminderSettingsCommand.channel(1L, true, key())))
                .isInstanceOf(WhatsAppActivationRequiredException.class);
        service.grantConsent(A, ReminderSettingsCommand.consent(1L, N1, true, key()));
        assertThat(repository.revocations).containsExactly(
                new Revocation(foreign.id(), ADMIN, ConsentRevocationReason.ADMINISTRATION_TRANSFERRED));
        assertThat(repository.activeConsent(SPACE)).map(StoredConsent::userId).contains(ADMIN);
    }

    @Test
    void consentOfAnotherNumberIsRevokedAsRecipientChange() {
        service.changeRecipient(A, ReminderSettingsCommand.recipient(0L, N1, key()));
        var stale = new StoredConsent(UUID.randomUUID(), ADMIN, "Admin", WhatsAppRecipient.parse(N2),
                "WHATSAPP-RESUMOS-V1", NOW.minusSeconds(60));
        repository.insertConsent(stale, SPACE);
        assertThat(service.view(A).whatsapp().consent().active()).isFalse();
        service.grantConsent(A, ReminderSettingsCommand.consent(1L, N1, true, key()));
        assertThat(repository.revocations).containsExactly(
                new Revocation(stale.id(), ADMIN, ConsentRevocationReason.RECIPIENT_CHANGED));
    }

    @Test
    void repetitionReplaysWithoutNewEffectsAndRejectsAnotherPayload() {
        service.changeRecipient(A, ReminderSettingsCommand.recipient(0L, N1, key()));
        var key = key();
        var first = service.grantConsent(A, ReminderSettingsCommand.consent(1L, N1, true, key));
        var replay = service.grantConsent(A, ReminderSettingsCommand.consent(1L, N1, true, key));
        assertThat(replay).isEqualTo(first);
        assertThat(repository.consents).hasSize(1);
        assertThat(repository.events).hasSize(2);
        assertThatThrownBy(() -> service.grantConsent(A, ReminderSettingsCommand.consent(1L, N2, true, key)))
                .isInstanceOf(ReminderSettingsIdempotencyConflictException.class);
        assertThatThrownBy(() -> service.grantConsent(A, ReminderSettingsCommand.consent(2L, N1, true, key)))
                .isInstanceOf(ReminderSettingsIdempotencyConflictException.class);
        assertThat(repository.completed.get(SPACE + ":" + ADMIN + ":GRANT_CONSENT:" + key)).isEqualTo(2L);
        // The same key may be used by another operation: the scope includes the operation.
        assertThat(service.changeChannel(A, ReminderSettingsCommand.channel(2L, true, key)).whatsapp().enabled()).isTrue();
    }

    @Test
    void commandsNeedKeyAndVersion() {
        assertThatThrownBy(() -> service.changeSchedule(A, ReminderSettingsCommand.schedule(0L, "08:00", "19:00", null)))
                .isInstanceOfSatisfying(NotificationValidationException.class,
                        error -> assertThat(error.field()).isEqualTo("Idempotency-Key"));
        assertThatThrownBy(() -> service.changeSchedule(A, ReminderSettingsCommand.schedule(null, "08:00", "19:00", key())))
                .isInstanceOfSatisfying(NotificationValidationException.class,
                        error -> assertThat(error.field()).isEqualTo("expectedVersion"));
        assertThatThrownBy(() -> service.changeSchedule(A, ReminderSettingsCommand.schedule(-1L, "08:00", "19:00", key())))
                .isInstanceOfSatisfying(NotificationValidationException.class,
                        error -> assertThat(error.field()).isEqualTo("expectedVersion"));
        assertThatThrownBy(() -> service.changeSchedule(A, null)).isInstanceOf(NullPointerException.class);
        assertThat(repository.claims).isEmpty();
    }

    @Test
    void lostUpdateIsAVersionConflict() {
        repository.failUpdates = true;
        assertThatThrownBy(() -> service.changeSchedule(A, ReminderSettingsCommand.schedule(0L, "08:00", "19:00", key())))
                .isInstanceOf(ReminderSettingsVersionConflictException.class);
    }

    @Test
    void transferRevokesConsentClearsNumberAndDisablesButKeepsTheSchedule() {
        service.changeSchedule(A, ReminderSettingsCommand.schedule(0L, "08:30", "20:00", key()));
        service.changeRecipient(A, ReminderSettingsCommand.recipient(1L, N1, key()));
        service.grantConsent(A, ReminderSettingsCommand.consent(2L, N1, true, key()));
        service.changeChannel(A, ReminderSettingsCommand.channel(3L, true, key()));
        var consent = repository.consents.getFirst().id();
        var at = NOW.plusSeconds(3600);

        service.afterAdministrationTransferred(SPACE, ADMIN, GUEST, at);

        assertThat(repository.stored.recipient()).isNull();
        assertThat(repository.stored.enabled()).isFalse();
        assertThat(repository.stored.version()).isEqualTo(5);
        assertThat(repository.stored.updatedAt()).isEqualTo(at);
        assertThat(repository.stored.schedule().text()).isEqualTo("08:30/20:00");
        assertThat(repository.revocations).containsExactly(
                new Revocation(consent, ADMIN, ConsentRevocationReason.ADMINISTRATION_TRANSFERRED));
        assertThat(repository.events.subList(4, 7)).extracting(SettingsEvent::type, SettingsEvent::detail,
                SettingsEvent::occurredAt).containsExactly(
                org.assertj.core.groups.Tuple.tuple(SettingsEvent.Type.RECIPIENT_CHANGED,
                        "+55 ** *****-4321 -> nenhum (ADMINISTRATION_TRANSFERRED)", at),
                org.assertj.core.groups.Tuple.tuple(SettingsEvent.Type.CONSENT_REVOKED, "ADMINISTRATION_TRANSFERRED", at),
                org.assertj.core.groups.Tuple.tuple(SettingsEvent.Type.CHANNEL_DISABLED, "ADMINISTRATION_TRANSFERRED", at));

        people.put(G, context(GUEST, "Convidado", G, SpaceRole.ADMINISTRATOR));
        people.put(A, context(ADMIN, "Admin", A, SpaceRole.GUEST));
        var newAdministrator = service.view(G);
        assertThat(newAdministrator.canManage()).isTrue();
        assertThat(newAdministrator.whatsapp().hasRecipient()).isFalse();
        assertThat(newAdministrator.whatsapp().consent().active()).isFalse();
        assertThat(newAdministrator.whatsapp().state()).isEqualTo("RECIPIENT_REQUIRED");
        assertThatThrownBy(() -> service.changeChannel(A, ReminderSettingsCommand.channel(5L, true, key())))
                .isInstanceOf(NotificationAdministratorRequiredException.class);
        service.changeRecipient(G, ReminderSettingsCommand.recipient(5L, N2, key()));
        service.grantConsent(G, ReminderSettingsCommand.consent(6L, N2, true, key()));
        assertThat(repository.activeConsent(SPACE)).map(StoredConsent::userId).contains(GUEST);
    }

    @Test
    void transferWithoutSettingsOrOnlyAScheduleChangesNothing() {
        service.afterAdministrationTransferred(SPACE, ADMIN, GUEST, NOW);
        assertThat(repository.stored).isNull();
        service.changeSchedule(A, ReminderSettingsCommand.schedule(0L, "08:30", "20:00", key()));
        service.afterAdministrationTransferred(SPACE, ADMIN, GUEST, NOW);
        assertThat(repository.stored.version()).isOne();
        assertThat(repository.events).hasSize(1);
    }

    @Test
    void transferWithNumberOnlyRecordsTheRecipientChange() {
        service.changeRecipient(A, ReminderSettingsCommand.recipient(0L, N1, key()));
        service.afterAdministrationTransferred(SPACE, ADMIN, GUEST, NOW);
        assertThat(repository.stored.recipient()).isNull();
        assertThat(repository.events).extracting(SettingsEvent::type).containsExactly(
                SettingsEvent.Type.RECIPIENT_CHANGED, SettingsEvent.Type.RECIPIENT_CHANGED);
        assertThat(repository.revocations).isEmpty();
    }

    @Test
    void transferWithAConsentButNoNumberStillRevokesIt() {
        var orphan = new StoredConsent(UUID.randomUUID(), ADMIN, "Admin", WhatsAppRecipient.parse(N1),
                "WHATSAPP-RESUMOS-V1", NOW);
        repository.insertConsent(orphan, SPACE);
        service.afterAdministrationTransferred(SPACE, ADMIN, GUEST, NOW);
        assertThat(repository.revocations).extracting(Revocation::consentId).containsExactly(orphan.id());
        assertThat(repository.events).extracting(SettingsEvent::type).containsExactly(SettingsEvent.Type.CONSENT_REVOKED);
    }

    @Test
    void transferFailsWhenTheLockedRowChanged() {
        service.changeRecipient(A, ReminderSettingsCommand.recipient(0L, N1, key()));
        repository.failUpdates = true;
        assertThatThrownBy(() -> service.afterAdministrationTransferred(SPACE, ADMIN, GUEST, NOW))
                .isInstanceOf(IllegalStateException.class);
    }

    /** H08.5: a suspension shows why and what to correct; enabling again or a new number ends it. */
    @Test
    void suspensionIsShownKeptByOtherChangesAndEndedByReenablingOrANewNumber() {
        enabledWithConsent();
        repository.stored = repository.stored.suspend(
                com.malyah.accountmanager.notifications.domain.WhatsAppSuspensionReason.RECIPIENT_INVALID, NOW);
        var suspended = service.view(A);
        assertThat(suspended.version()).isEqualTo(4);
        assertThat(suspended.whatsapp().state()).isEqualTo("SUSPENDED");
        assertThat(suspended.whatsapp().enabled()).isFalse();
        assertThat(suspended.whatsapp().consent().active()).isTrue();
        assertThat(suspended.whatsapp().recipient()).isEqualTo("+5511987654321");
        assertThat(suspended.whatsapp().suspension().reason()).isEqualTo("RECIPIENT_INVALID");
        assertThat(suspended.whatsapp().suspension().suspendedAt()).isEqualTo(NOW);
        assertThat(suspended.whatsapp().suspension().message()).contains("reative o canal");
        assertThat(service.view(G).whatsapp().state()).isEqualTo("SUSPENDED");

        service.changeSchedule(A, ReminderSettingsCommand.schedule(4L, "08:00", "19:00", key()));
        assertThat(service.view(A).whatsapp().suspension()).isNotNull();
        var enabled = service.changeChannel(A, ReminderSettingsCommand.channel(5L, true, key()));
        assertThat(enabled.whatsapp().suspension()).isNull();
        assertThat(enabled.whatsapp().enabled()).isTrue();
        assertThat(repository.stored.suspendedAt()).isNull();

        repository.stored = repository.stored.suspend(
                com.malyah.accountmanager.notifications.domain.WhatsAppSuspensionReason.PROVIDER_REJECTED, NOW);
        var changed = service.changeRecipient(A, ReminderSettingsCommand.recipient(7L, N2, key()));
        assertThat(changed.whatsapp().suspension()).isNull();
        assertThat(changed.whatsapp().state()).isEqualTo("CONSENT_REQUIRED");
    }

    @Test
    void hashSeparatesParts() {
        assertThat(ReminderSettingsService.hash("ab", "c")).isNotEqualTo(ReminderSettingsService.hash("a", "bc"));
        assertThat(ReminderSettingsService.hash("a")).hasSize(64).isEqualTo(ReminderSettingsService.hash("a"));
        assertThat(ReminderSettingsService.hash((String) null)).isEqualTo(ReminderSettingsService.hash("null"));
    }

    private void enabledWithConsent() {
        service.changeRecipient(A, ReminderSettingsCommand.recipient(0L, N1, key()));
        service.grantConsent(A, ReminderSettingsCommand.consent(1L, N1, true, key()));
        service.changeChannel(A, ReminderSettingsCommand.channel(2L, true, key()));
    }

    private static UUID key() {
        return UUID.randomUUID();
    }

    record Revocation(UUID consentId, UUID actorId, ConsentRevocationReason reason) { }

    /** Mirrors the PostgreSQL adapter: lock creates the default row, update checks the previous version. */
    final class FakeRepository implements ReminderSettingsRepository {
        StoredReminderSettings stored;
        boolean failUpdates;
        final List<StoredConsent> consents = new ArrayList<>();
        final Map<UUID, Boolean> revoked = new HashMap<>();
        final List<Revocation> revocations = new ArrayList<>();
        final List<SettingsEvent> events = new ArrayList<>();
        final Map<String, String> claims = new HashMap<>();
        final Map<String, Long> completed = new HashMap<>();

        @Override
        public Optional<StoredReminderSettings> find(UUID spaceId) {
            return Optional.ofNullable(stored);
        }

        @Override
        public StoredReminderSettings lock(UUID spaceId, Instant at) {
            if (stored == null) stored = new StoredReminderSettings(spaceId,
                    com.malyah.accountmanager.notifications.domain.ReminderSchedule.DEFAULT, null, false, 0, at);
            return stored;
        }

        @Override
        public boolean update(StoredReminderSettings settings, UUID actorId) {
            if (failUpdates || stored.version() != settings.version() - 1) return false;
            stored = settings;
            return true;
        }

        @Override
        public Optional<StoredConsent> activeConsent(UUID spaceId) {
            return consents.stream().filter(consent -> !revoked.containsKey(consent.id())).findFirst();
        }

        @Override
        public void insertConsent(StoredConsent consent, UUID spaceId) {
            if (activeConsent(spaceId).isPresent()) throw new IllegalStateException("unique active consent");
            consents.add(consent);
        }

        @Override
        public void revokeConsent(UUID consentId, UUID actorId, ConsentRevocationReason reason, Instant at) {
            revoked.put(consentId, true);
            revocations.add(new Revocation(consentId, actorId, reason));
        }

        @Override
        public void appendEvent(SettingsEvent event) {
            events.add(event);
        }

        @Override
        public List<SettingsEvent> events(UUID spaceId, int limit) {
            assertThat(limit).isEqualTo(50);
            return events.stream().map(event -> new SettingsEvent(event.id(), event.spaceId(), event.actorId(),
                    event.actorId().equals(ADMIN) ? "Admin" : "Convidado", event.type(), event.fromVersion(),
                    event.toVersion(), event.detail(), event.occurredAt())).toList();
        }

        @Override
        public SettingsClaim claim(UUID spaceId, UUID actorId, String operation, UUID key, String requestHash,
                Instant at) {
            var id = spaceId + ":" + actorId + ":" + operation + ":" + key;
            var existing = claims.putIfAbsent(id, requestHash);
            if (existing == null) return new SettingsClaim(false, null);
            if (!existing.equals(requestHash) || !completed.containsKey(id))
                throw new ReminderSettingsIdempotencyConflictException();
            return new SettingsClaim(true, completed.get(id));
        }

        @Override
        public void complete(UUID spaceId, UUID actorId, String operation, UUID key, long resultVersion, Instant at) {
            completed.put(spaceId + ":" + actorId + ":" + operation + ":" + key, resultVersion);
        }
    }
}
