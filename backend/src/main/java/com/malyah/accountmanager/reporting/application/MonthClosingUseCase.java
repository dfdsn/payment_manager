package com.malyah.accountmanager.reporting.application;

public interface MonthClosingUseCase {
    /** The saved snapshot of the month, if any, and the current data of the same month. */
    MonthClosingView view(String actorEmail, String month);

    /** H07.2: the closed months of a year (default: the current one) and whether each is outdated. */
    MonthClosingListView list(String actorEmail, String year);

    CloseMonthResult close(String actorEmail, CloseMonthCommand command);

    /** H07.3: saves a new version of a closed month from the current data and makes it the version in force. */
    CloseMonthResult generateVersion(String actorEmail, GenerateVersionCommand command);

    /** H07.3: every version of the month, oldest first. */
    ClosingVersionListView versions(String actorEmail, String month);

    /** H07.3: the stored snapshot of one version of the month. */
    ClosingVersionView version(String actorEmail, String month, String number);
}
