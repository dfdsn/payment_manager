package com.malyah.accountmanager.reporting.application;

import java.time.Instant;
import java.util.List;

/** H07.2: the closed months of one year, each with its version in force and its situation computed now. */
public record MonthClosingListView(int year, List<Item> closings) {
    public record Item(String month, int version, String authorDisplayName, Instant closedAt, String status) { }
}
