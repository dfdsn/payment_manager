package com.malyah.accountmanager.notifications.application;

/** Result of claiming an idempotency key: a replay carries the version the first request left. */
public record SettingsClaim(boolean replayed, Long resultVersion) { }
