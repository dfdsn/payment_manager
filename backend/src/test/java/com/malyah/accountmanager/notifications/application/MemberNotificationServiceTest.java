package com.malyah.accountmanager.notifications.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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

import com.malyah.accountmanager.identity.application.AuthenticatedUserContext;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContextQuery;
import com.malyah.accountmanager.identity.domain.SpaceRole;
import com.malyah.accountmanager.notifications.application.port.MemberNotificationRepository;
import com.malyah.accountmanager.notifications.domain.WhatsAppFailureReason;

/** H08.3 rules with a mocked repository; the SQL behavior is in MemberNotificationPostgresIT. */
class MemberNotificationServiceTest {
    static final UUID SPACE = UUID.fromString("a0000000-0000-0000-0000-000000000001");
    static final UUID ADMIN = UUID.fromString("a0000000-0000-0000-0000-000000000002");
    static final UUID GUEST = UUID.fromString("a0000000-0000-0000-0000-000000000003");
    static final UUID SUMMARY = UUID.fromString("b0000000-0000-0000-0000-000000000001");
    static final Instant NOW = Instant.parse("2026-10-05T15:00:00Z");

    MemberNotificationRepository repository;
    MemberNotificationService service;

    @BeforeEach
    void setUp() {
        repository = mock(MemberNotificationRepository.class);
        AuthenticatedUserContextQuery contexts = email -> new AuthenticatedUserContext(
                email.startsWith("admin") ? ADMIN : GUEST, "Pessoa", email, SPACE, "Casa",
                email.startsWith("admin") ? SpaceRole.ADMINISTRATOR : SpaceRole.GUEST, "BRL", "pt-BR",
                "America/Sao_Paulo");
        service = new MemberNotificationService(repository, contexts, Clock.fixed(NOW, ZoneOffset.UTC),
                "https://contas.example/");
    }

    static StoredNotification summary(int count, int overdue, int estimated) {
        return new StoredNotification(UUID.randomUUID(), StoredNotification.Type.REMINDER_SUMMARY, null, NOW, null,
                null, head(count, overdue, estimated, "FIRST"));
    }

    static StoredNotification.SummaryHead head(int count, int overdue, int estimated, String slot) {
        return new StoredNotification.SummaryHead(SUMMARY, LocalDate.of(2026, 10, 5), slot, LocalTime.of(9, 0),
                "America/Sao_Paulo", count, new BigDecimal("1234.5"), estimated,
                estimated == 0 ? BigDecimal.ZERO : new BigDecimal("90.00"), overdue);
    }

    @Test
    void listsOnlyTheReadersNotificationsAndTellsWhetherTheReaderIsTheAdministrator() {
        when(repository.count(SPACE, GUEST, false, false)).thenReturn(41L);
        when(repository.unread(SPACE, GUEST, false)).thenReturn(3L);
        when(repository.page(SPACE, GUEST, false, false, 40L, 20)).thenReturn(List.of(summary(1, 0, 0)));
        var page = service.list("guest@example.com", null, 2, null);
        assertThat(page.page()).isEqualTo(2);
        assertThat(page.size()).isEqualTo(20);
        assertThat(page.totalItems()).isEqualTo(41);
        assertThat(page.totalPages()).isEqualTo(3);
        assertThat(page.unreadCount()).isEqualTo(3);
        assertThat(page.view()).isEqualTo("ACTIVE");
        assertThat(page.items()).hasSize(1);
        when(repository.count(SPACE, ADMIN, true, true)).thenReturn(0L);
        var dismissed = service.list("admin@example.com", "DISMISSED", 0, 100);
        assertThat(dismissed.view()).isEqualTo("DISMISSED");
        assertThat(dismissed.totalPages()).isZero();
        verify(repository).page(SPACE, ADMIN, true, true, 0L, 100);
    }

    @Test
    void rejectsInvalidQueriesBeforeReadingAnything() {
        for (var invalid : List.<Runnable>of(() -> service.list("guest@example.com", "ALL", 0, 20),
                () -> service.list("guest@example.com", null, -1, 20),
                () -> service.list("guest@example.com", null, 0, 0),
                () -> service.list("guest@example.com", null, 0, 101)))
            assertThatThrownBy(invalid::run).isInstanceOf(NotificationQueryValidationException.class);
        assertThatThrownBy(() -> service.list("guest@example.com", null, 0, 101)).extracting("field").isEqualTo("size");
        assertThatThrownBy(() -> service.list("guest@example.com", null, -1, 1)).extracting("field").isEqualTo("page");
        assertThatThrownBy(() -> service.list("guest@example.com", "X", 0, 1)).extracting("field").isEqualTo("view");
        verifyNoInteractions(repository);
        when(repository.page(any(), any(), anyBoolean(), anyBoolean(), anyLong(), anyInt())).thenReturn(List.of());
        assertThat(service.list("guest@example.com", "", 0, 1).view()).isEqualTo("ACTIVE");
        assertThat(service.list("guest@example.com", "ACTIVE", 0, 1).view()).isEqualTo("ACTIVE");
    }

    @Test
    void summaryNotificationTextComesFromTheHistoricalHead() {
        var plain = service.view(summary(1, 0, 0));
        assertThat(plain.type()).isEqualTo("REMINDER_SUMMARY");
        assertThat(plain.title()).isEqualTo("Contas a pagar: primeiro horário de 05/10, 09:00");
        assertThat(plain.message()).isEqualTo("1 conta, total R$ 1.234,50");
        assertThat(plain.failure()).isNull();
        assertThat(plain.summary().total()).isEqualTo("1234.50");
        assertThat(plain.summary().remaining()).isZero();
        assertThat(plain.summary().link()).isEqualTo("https://contas.example/lembretes/resumos/" + SUMMARY);
        var busy = service.view(summary(7, 2, 1));
        assertThat(busy.message()).isEqualTo("7 contas, total R$ 1.234,50, 2 atrasadas (1 estimada). Abra para ver todas.");
        assertThat(busy.summary().remaining()).isEqualTo(2);
        assertThat(busy.summary().estimatedTotal()).isEqualTo("90.00");
        assertThat(service.view(summary(5, 1, 2)).message()).isEqualTo("5 contas, total R$ 1.234,50, 1 atrasada (2 estimadas)");
        var second = new StoredNotification(UUID.randomUUID(), StoredNotification.Type.REMINDER_SUMMARY, null, NOW,
                null, null, head(1, 0, 0, "SECOND"));
        assertThat(service.view(second).title()).isEqualTo("Contas a pagar: segundo horário de 05/10, 09:00");
    }

    @Test
    void failureNotificationsOnlyCarryCatalogText() {
        for (var reason : WhatsAppFailureReason.values()) {
            var view = service.view(new StoredNotification(UUID.randomUUID(),
                    StoredNotification.Type.WHATSAPP_DELIVERY_FAILURE, reason.name(), NOW, null, null, head(1, 0, 0, "FIRST")));
            assertThat(view.failure().code()).isEqualTo(reason.name());
            assertThat(view.failure().message()).isEqualTo(reason.message()).isEqualTo(view.message());
            assertThat(view.title()).isEqualTo(reason == WhatsAppFailureReason.RESULT_UNCERTAIN
                    ? "WhatsApp não confirmado: resumo de 05/10, 09:00" : "WhatsApp não enviado: resumo de 05/10, 09:00");
        }
        var unknown = service.view(new StoredNotification(UUID.randomUUID(),
                StoredNotification.Type.WHATSAPP_DELIVERY_FAILURE, "SOMETHING_NEW", NOW, null, null, head(1, 0, 0, "FIRST")));
        assertThat(unknown.message()).isEqualTo("O resumo não seguiu pelo WhatsApp. O resumo continua disponível aqui no aplicativo.");
        assertThat(unknown.title()).startsWith("WhatsApp não enviado");
    }

    @Test
    void readAndDismissOnlyTouchTheReadersOwnNotification() {
        var id = UUID.randomUUID();
        var stored = summary(1, 0, 0);
        when(repository.markRead(SPACE, GUEST, false, id, NOW)).thenReturn(Optional.of(stored));
        when(repository.markDismissed(SPACE, ADMIN, true, id, NOW)).thenReturn(Optional.of(stored));
        assertThat(service.read("guest@example.com", id).id()).isEqualTo(stored.id());
        assertThat(service.dismiss("admin@example.com", id).id()).isEqualTo(stored.id());
        when(repository.markRead(SPACE, ADMIN, true, id, NOW)).thenReturn(Optional.empty());
        when(repository.markDismissed(SPACE, GUEST, false, id, NOW)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.read("admin@example.com", id))
                .isInstanceOf(MemberNotificationNotFoundException.class).hasMessage("Aviso não encontrado.");
        assertThatThrownBy(() -> service.dismiss("guest@example.com", id))
                .isInstanceOf(MemberNotificationNotFoundException.class);
    }

    @Test
    void unreadCountAndTheFailureContractForTheDelivery() {
        when(repository.unread(SPACE, ADMIN, true)).thenReturn(4L);
        assertThat(service.unreadCount("admin@example.com").unreadCount()).isEqualTo(4);
        when(repository.recordWhatsAppFailure(SPACE, SUMMARY, WhatsAppFailureReason.RESULT_UNCERTAIN, NOW))
                .thenReturn(true);
        assertThat(service.recordWhatsAppFailure(SPACE, SUMMARY, WhatsAppFailureReason.RESULT_UNCERTAIN)).isTrue();
        assertThat(service.recordWhatsAppFailure(SPACE, SUMMARY, WhatsAppFailureReason.DELIVERY_FAILED)).isFalse();
        assertThatThrownBy(() -> service.recordWhatsAppFailure(SPACE, SUMMARY, null))
                .isInstanceOf(NullPointerException.class);
        assertThat(new MemberNotificationService(repository, email -> null, Clock.systemUTC(), "http://x")
                .view(summary(1, 0, 0)).summary().link()).isEqualTo("http://x/lembretes/resumos/" + SUMMARY);
    }

    @Test
    void catalogCodesRoundTrip() {
        for (var reason : WhatsAppFailureReason.values())
            assertThat(WhatsAppFailureReason.fromCode(reason.name())).contains(reason);
        assertThat(WhatsAppFailureReason.fromCode("nope")).isEmpty();
        assertThat(WhatsAppFailureReason.fromCode(null)).isEmpty();
        assertThat(WhatsAppFailureReason.values()).allSatisfy(reason ->
                assertThat(reason.message()).doesNotContainIgnoringCase("token").doesNotContain("+55"));
    }
}
