package com.malyah.accountmanager.reporting.application;

public interface MonthClosingUseCase {
    /** The saved snapshot of the month, if any, and the current data of the same month. */
    MonthClosingView view(String actorEmail, String month);

    /** H07.2: the closed months of a year (default: the current one) and whether each is outdated. */
    MonthClosingListView list(String actorEmail, String year);

    CloseMonthResult close(String actorEmail, CloseMonthCommand command);
}
