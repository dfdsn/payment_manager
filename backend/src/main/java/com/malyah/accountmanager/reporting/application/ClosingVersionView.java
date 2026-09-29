package com.malyah.accountmanager.reporting.application;

/** H07.3: the stored snapshot of one version of a month, current or not; never recalculated. */
public record ClosingVersionView(String month, int currentVersion, boolean current, ClosingSnapshotView snapshot) { }
