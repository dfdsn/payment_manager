package com.malyah.accountmanager.reporting.application;

import java.text.NumberFormat;
import java.util.Locale;

/** The selection has more rows than an export accepts; nothing was written, the member narrows the filters. */
public final class ExportLimitExceededException extends RuntimeException {
    private final long rows;
    private final int limit;

    public ExportLimitExceededException(long rows, int limit) {
        super("A seleção tem " + grouped(rows) + " registros e a exportação aceita até " + grouped(limit)
                + ". Reduza o período ou aplique filtros e exporte em partes.");
        this.rows = rows;
        this.limit = limit;
    }

    public long rows() {
        return rows;
    }

    public int limit() {
        return limit;
    }

    private static String grouped(long value) {
        return NumberFormat.getIntegerInstance(Locale.forLanguageTag("pt-BR")).format(value);
    }
}
