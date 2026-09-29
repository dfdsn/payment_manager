package com.malyah.accountmanager.reporting.application;

import java.util.UUID;

/**
 * H07.1: close {@code month} ({@code AAAA-MM}). {@code acknowledgePending} is the member's confirmation of the
 * warning about pending entries (RF-FEC-02); it is required only when the month has pending entries.
 */
public record CloseMonthCommand(String month, boolean acknowledgePending, UUID idempotencyKey) { }
