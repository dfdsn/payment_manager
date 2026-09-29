package com.malyah.accountmanager.reporting.domain;

/** T28: a month can be closed only after it has started in the space time zone. */
public final class ClosingMonthNotAllowedException extends RuntimeException {
    public ClosingMonthNotAllowedException() {
        super("Só é possível fechar o mês atual ou meses anteriores.");
    }
}
