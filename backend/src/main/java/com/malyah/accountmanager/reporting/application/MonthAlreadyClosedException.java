package com.malyah.accountmanager.reporting.application;

public final class MonthAlreadyClosedException extends RuntimeException {
    public MonthAlreadyClosedException() {
        super("Este mês já foi fechado. Consulte o retrato salvo.");
    }
}
