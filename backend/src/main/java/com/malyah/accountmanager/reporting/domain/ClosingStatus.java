package com.malyah.accountmanager.reporting.domain;

/** E07 situation of a month: not closed, or closed with current data equal to or different from the saved version. */
public enum ClosingStatus {
    NOT_CLOSED, UP_TO_DATE, OUTDATED
}
