package com.malyah.accountmanager.installments.application;

import java.util.UUID;

/** Result of claiming an idempotency key: a replay carries the purchase created by the first request. */
public record PurchaseClaim(boolean replayed, UUID purchaseId) { }
