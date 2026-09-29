package com.malyah.accountmanager.reporting.application;

public interface MonthClosingUseCase {
    /** The saved snapshot of the month, if any, and the current data of the same month. */
    MonthClosingView view(String actorEmail, String month);

    CloseMonthResult close(String actorEmail, CloseMonthCommand command);
}
