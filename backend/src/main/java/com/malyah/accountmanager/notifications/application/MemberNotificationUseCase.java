package com.malyah.accountmanager.notifications.application;

import java.util.UUID;

/** H08.3: each member reads, marks as read and dismisses only their own notifications. */
public interface MemberNotificationUseCase {
    MemberNotificationView.Page list(String actorEmail, String view, Integer page, Integer size);

    MemberNotificationView.UnreadCount unreadCount(String actorEmail);

    MemberNotificationView read(String actorEmail, UUID notificationId);

    MemberNotificationView dismiss(String actorEmail, UUID notificationId);
}
