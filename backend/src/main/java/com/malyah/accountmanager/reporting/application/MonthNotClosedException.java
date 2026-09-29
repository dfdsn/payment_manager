package com.malyah.accountmanager.reporting.application;

public final class MonthNotClosedException extends RuntimeException {
    public MonthNotClosedException() {
        super("Este mês ainda não foi fechado. Feche o mês antes de gerar uma nova versão.");
    }
}
