package com.malyah.accountmanager.reporting.domain;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

/**
 * H06.4 CSV for Excel in Brazilian Portuguese: UTF-8 with BOM (accents), {@code ;} separator, CRLF line endings,
 * dates {@code dd/MM/yyyy} and amounts with decimal comma, two places and no thousands separator, so Excel reads
 * them as numbers. Text cells are always quoted (RFC 4180, inner quotes doubled, line breaks kept inside the quotes)
 * and, when they start with a character that a spreadsheet would read as a formula, are prefixed with an
 * apostrophe. Amounts and dates are produced here, never taken from user text, so they are not prefixed.
 */
public final class CsvDocument {
    static final char BOM = '﻿';
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private final StringBuilder content = new StringBuilder().append(BOM);
    private final int columns;
    private long rows;

    public CsvDocument(List<String> header) {
        this.columns = header.size();
        if (columns == 0) throw new IllegalArgumentException("The header needs columns.");
        line(header.stream().map(CsvDocument::text).toList());
    }

    /** Adds one data row of cells already formatted with {@link #text}, {@link #money} or {@link #date}. */
    public void row(List<String> cells) {
        line(cells);
        rows++;
    }

    public long rows() {
        return rows;
    }

    public byte[] bytes() {
        return content.toString().getBytes(StandardCharsets.UTF_8);
    }

    public static String text(String value) {
        if (value == null || value.isEmpty()) return "";
        var safe = startsLikeFormula(value) ? "'" + value : value;
        return '"' + safe.replace("\"", "\"\"") + '"';
    }

    public static String money(BigDecimal value) {
        if (value == null) return "";
        return Money.of(value).toPlainString().replace('.', ',');
    }

    public static String date(LocalDate value) {
        return value == null ? "" : DATE.format(value);
    }

    static boolean startsLikeFormula(String value) {
        var first = value.charAt(0);
        return first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r';
    }

    private void line(List<String> cells) {
        Objects.requireNonNull(cells, "cells");
        if (cells.size() != columns)
            throw new IllegalArgumentException("Expected " + columns + " cells, got " + cells.size() + ".");
        content.append(String.join(";", cells)).append("\r\n");
    }
}
