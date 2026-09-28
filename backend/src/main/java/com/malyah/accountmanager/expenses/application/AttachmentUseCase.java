package com.malyah.accountmanager.expenses.application;

import java.util.List;
import java.util.UUID;

public interface AttachmentUseCase {
    AttachmentView upload(String email, UUID expenseId, UUID key, String name, byte[] content);
    List<AttachmentView> list(String email, UUID expenseId);
    AttachmentContent download(String email, UUID expenseId, UUID attachmentId);
    void remove(String email, UUID expenseId, UUID attachmentId);
}
