package com.malyah.accountmanager.reporting.application;

/** A finished CSV: ASCII file name, the whole content (bounded by the row limit) and its data row count. */
public record CsvFile(String fileName, byte[] content, long rows) { }
