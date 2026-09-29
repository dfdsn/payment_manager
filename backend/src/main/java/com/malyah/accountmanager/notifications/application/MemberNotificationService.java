package com.malyah.accountmanager.notifications.application;

import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.UUID;

import com.malyah.accountmanager.identity.application.AuthenticatedUserContext;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContextQuery;
import com.malyah.accountmanager.identity.domain.SpaceRole;
import com.malyah.accountmanager.notifications.application.port.MemberNotificationRepository;
import com.malyah.accountmanager.notifications.domain.ReminderSchedule;
import com.malyah.accountmanager.notifications.domain.ReminderSummary;
import com.malyah.accountmanager.notifications.domain.ReminderSummaryText;
import com.malyah.accountmanager.notifications.domain.WhatsAppFailureReason;

/**
 * H08.3 (RF-ALT-01, RF-ALT-07, RF-ALT-18, RF-ALT-20). Every member reads only their own notifications of the active
 * space; the administrative ones (WhatsApp failures) only while the reader is still the administrator. Reading and
 * dismissing change nothing but the reader's own notification: no bill is paid and no reminder is stopped.
 */
public final class MemberNotificationService implements MemberNotificationUseCase {
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM");

    private final MemberNotificationRepository notifications;
    private final AuthenticatedUserContextQuery contexts;
    private final Clock clock;
    private final String publicBaseUrl;

    public MemberNotificationService(MemberNotificationRepository notifications, AuthenticatedUserContextQuery contexts,
            Clock clock, String publicBaseUrl) {
        this.notifications = Objects.requireNonNull(notifications);
        this.contexts = Objects.requireNonNull(contexts);
        this.clock = Objects.requireNonNull(clock);
        this.publicBaseUrl = publicBaseUrl.endsWith("/") ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1)
                : publicBaseUrl;
    }

    @Override
    public MemberNotificationView.Page list(String actorEmail, String view, Integer page, Integer size) {
        var dismissed = parseView(view);
        var pageNumber = page == null ? 0 : page;
        var pageSize = size == null ? DEFAULT_PAGE_SIZE : size;
        if (pageNumber < 0) throw new NotificationQueryValidationException("page", "A página começa em 0.");
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE)
            throw new NotificationQueryValidationException("size",
                    "Escolha de 1 a " + MAX_PAGE_SIZE + " avisos por página.");
        var actor = contexts.findByEmail(actorEmail);
        var administrator = administrator(actor);
        var total = notifications.count(actor.spaceId(), actor.userId(), administrator, dismissed);
        var items = notifications.page(actor.spaceId(), actor.userId(), administrator, dismissed,
                (long) pageNumber * pageSize, pageSize).stream().map(this::view).toList();
        return new MemberNotificationView.Page(items, pageNumber, pageSize, total,
                (int) ((total + pageSize - 1) / pageSize), notifications.unread(actor.spaceId(), actor.userId(),
                        administrator), dismissed ? "DISMISSED" : "ACTIVE");
    }

    @Override
    public MemberNotificationView.UnreadCount unreadCount(String actorEmail) {
        var actor = contexts.findByEmail(actorEmail);
        return new MemberNotificationView.UnreadCount(
                notifications.unread(actor.spaceId(), actor.userId(), administrator(actor)));
    }

    @Override
    public MemberNotificationView read(String actorEmail, UUID notificationId) {
        var actor = contexts.findByEmail(actorEmail);
        return notifications.markRead(actor.spaceId(), actor.userId(), administrator(actor), notificationId,
                clock.instant()).map(this::view).orElseThrow(MemberNotificationNotFoundException::new);
    }

    @Override
    public MemberNotificationView dismiss(String actorEmail, UUID notificationId) {
        var actor = contexts.findByEmail(actorEmail);
        return notifications.markDismissed(actor.spaceId(), actor.userId(), administrator(actor), notificationId,
                clock.instant()).map(this::view).orElseThrow(MemberNotificationNotFoundException::new);
    }

    /**
     * Contract for the WhatsApp delivery (H08.4): tells the active administrator, in the application, that the
     * summary did not go (or may not have gone) through WhatsApp. Runs in the caller's transaction.
     */
    public boolean recordWhatsAppFailure(UUID spaceId, UUID summaryId, WhatsAppFailureReason reason) {
        return notifications.recordWhatsAppFailure(spaceId, summaryId, Objects.requireNonNull(reason),
                clock.instant());
    }

    MemberNotificationView view(StoredNotification stored) {
        var head = stored.summary();
        var slot = "FIRST".equals(head.slot()) ? "primeiro horário" : "segundo horário";
        var when = DAY.format(head.date()) + ", " + ReminderSchedule.format(head.scheduledTime());
        var remaining = Math.max(0, head.count() - ReminderSummary.DETAIL_LIMIT);
        var summary = new MemberNotificationView.Summary(head.id(), head.date(), head.slot(),
                ReminderSchedule.format(head.scheduledTime()), head.timeZone(), head.count(),
                head.total().setScale(2).toPlainString(), head.estimatedCount(),
                head.estimatedTotal().setScale(2).toPlainString(), head.overdueCount(), remaining,
                publicBaseUrl + ReminderSummaryService.SUMMARY_PATH + head.id());
        if (stored.type() == StoredNotification.Type.WHATSAPP_DELIVERY_FAILURE) {
            var reason = WhatsAppFailureReason.fromCode(stored.failureCode());
            var message = reason.map(WhatsAppFailureReason::message)
                    .orElse("O resumo não seguiu pelo WhatsApp. O resumo continua disponível aqui no aplicativo.");
            var title = reason.filter(WhatsAppFailureReason.RESULT_UNCERTAIN::equals).isPresent()
                    ? "WhatsApp não confirmado: resumo de " + when
                    : "WhatsApp não enviado: resumo de " + when;
            return new MemberNotificationView(stored.id(), stored.type().name(), title, message, stored.createdAt(),
                    stored.readAt(), stored.dismissedAt(), summary,
                    new MemberNotificationView.Failure(stored.failureCode(), message));
        }
        var message = new StringBuilder().append(head.count()).append(head.count() == 1 ? " conta" : " contas")
                .append(", total ").append(ReminderSummaryText.money(head.total()));
        if (head.overdueCount() > 0)
            message.append(", ").append(head.overdueCount()).append(head.overdueCount() == 1 ? " atrasada" : " atrasadas");
        if (head.estimatedCount() > 0)
            message.append(" (").append(head.estimatedCount())
                    .append(head.estimatedCount() == 1 ? " estimada)" : " estimadas)");
        if (remaining > 0) message.append(". Abra para ver todas.");
        return new MemberNotificationView(stored.id(), stored.type().name(),
                "Contas a pagar: " + slot + " de " + when, message.toString(), stored.createdAt(), stored.readAt(),
                stored.dismissedAt(), summary, null);
    }

    private static boolean administrator(AuthenticatedUserContext actor) {
        return actor.role() == SpaceRole.ADMINISTRATOR;
    }

    private static boolean parseView(String view) {
        if (view == null || view.isBlank() || "ACTIVE".equals(view)) return false;
        if ("DISMISSED".equals(view)) return true;
        throw new NotificationQueryValidationException("view", "Escolha avisos ativos (ACTIVE) ou dispensados (DISMISSED).");
    }
}
