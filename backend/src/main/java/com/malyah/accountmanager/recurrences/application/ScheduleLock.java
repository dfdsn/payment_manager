package com.malyah.accountmanager.recurrences.application;

/**
 * Row lock taken on the recurrence definition. Readers that materialize take SHARE; changes, closures and charge
 * confirmations take UPDATE (FOR NO KEY UPDATE), which serializes them with materialization.
 */
public enum ScheduleLock { NONE, SHARE, UPDATE }
