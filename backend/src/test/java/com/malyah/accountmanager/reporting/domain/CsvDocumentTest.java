package com.malyah.accountmanager.reporting.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

/** H06.4 CSV format for Excel pt-BR: BOM, separator, quoting, formula protection, money and dates. */
class CsvDocumentTest {
    @Test
    void writesBomHeaderAndRowsWithSemicolonsAndCrlf() {
        var document = new CsvDocument(List.of("Descrição", "Valor"));
        document.row(List.of(CsvDocument.text("Água"), CsvDocument.money(new BigDecimal("1234.5"))));

        var bytes = document.bytes();
        assertThat(bytes[0]).isEqualTo((byte) 0xEF);
        assertThat(bytes[1]).isEqualTo((byte) 0xBB);
        assertThat(bytes[2]).isEqualTo((byte) 0xBF);
        assertThat(new String(bytes, StandardCharsets.UTF_8))
                .isEqualTo("﻿\"Descrição\";\"Valor\"\r\n\"Água\";1234,50\r\n");
        assertThat(document.rows()).isEqualTo(1);
    }

    @Test
    void quotesSeparatorsQuotesAndLineBreaksInsideOneCell() {
        assertThat(CsvDocument.text("Luz; água")).isEqualTo("\"Luz; água\"");
        assertThat(CsvDocument.text("TV 50\" sala")).isEqualTo("\"TV 50\"\" sala\"");
        assertThat(CsvDocument.text("linha 1\nlinha 2\r\nlinha 3")).isEqualTo("\"linha 1\nlinha 2\r\nlinha 3\"");
        assertThat(CsvDocument.text("")).isEmpty();
        assertThat(CsvDocument.text(null)).isEmpty();
    }

    @Test
    void textsThatASpreadsheetWouldEvaluateGetAnApostrophe() {
        assertThat(CsvDocument.text("=HYPERLINK(\"http://x\")")).isEqualTo("\"'=HYPERLINK(\"\"http://x\"\")\"");
        assertThat(CsvDocument.text("+55 11")).isEqualTo("\"'+55 11\"");
        assertThat(CsvDocument.text("-10% desconto")).isEqualTo("\"'-10% desconto\"");
        assertThat(CsvDocument.text("@SUM(A1)")).isEqualTo("\"'@SUM(A1)\"");
        assertThat(CsvDocument.text("\t=1")).isEqualTo("\"'\t=1\"");
        assertThat(CsvDocument.text("\r=1")).isEqualTo("\"'\r=1\"");
        assertThat(CsvDocument.text("Conta = luz")).isEqualTo("\"Conta = luz\"");
        assertThat(CsvDocument.text(" =1")).isEqualTo("\" =1\"");
    }

    @Test
    void moneyKeepsEveryCentWithDecimalCommaAndNoGrouping() {
        assertThat(CsvDocument.money(new BigDecimal("0.01"))).isEqualTo("0,01");
        assertThat(CsvDocument.money(new BigDecimal("99999999.99"))).isEqualTo("99999999,99");
        assertThat(CsvDocument.money(new BigDecimal("1000"))).isEqualTo("1000,00");
        assertThat(CsvDocument.money(null)).isEmpty();
        assertThatThrownBy(() -> CsvDocument.money(new BigDecimal("1.005"))).isInstanceOf(ArithmeticException.class);
    }

    @Test
    void datesUseTheBrazilianOrder() {
        assertThat(CsvDocument.date(LocalDate.of(2027, 1, 5))).isEqualTo("05/01/2027");
        assertThat(CsvDocument.date(null)).isEmpty();
    }

    @Test
    void refusesRowsWithAnotherNumberOfCells() {
        assertThatThrownBy(() -> new CsvDocument(List.of())).isInstanceOf(IllegalArgumentException.class);
        var document = new CsvDocument(List.of("A", "B"));
        assertThatThrownBy(() -> document.row(List.of("1"))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Expected 2 cells, got 1");
        assertThat(document.rows()).isZero();
    }
}
