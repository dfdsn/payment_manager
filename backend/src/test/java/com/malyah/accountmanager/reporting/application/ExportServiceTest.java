package com.malyah.accountmanager.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import com.malyah.accountmanager.expenses.application.ExpenseDateBasis;
import com.malyah.accountmanager.expenses.application.ExpenseExportQueries;
import com.malyah.accountmanager.expenses.application.ExpenseQueryValidationException;
import com.malyah.accountmanager.expenses.application.ExpenseSelection;
import com.malyah.accountmanager.expenses.application.ExpenseSort;
import com.malyah.accountmanager.expenses.application.ExpenseStatusFilter;
import com.malyah.accountmanager.expenses.application.ExportedExpense;
import com.malyah.accountmanager.expenses.application.InstallmentLink;
import com.malyah.accountmanager.expenses.application.SortDirection;
import com.malyah.accountmanager.expenses.domain.ExpenseStatus;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContext;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContextNotFoundException;
import com.malyah.accountmanager.identity.domain.SpaceRole;
import com.malyah.accountmanager.recurrences.application.PlannedForecast;
import com.malyah.accountmanager.recurrences.application.RecurrenceForecastQueries;

/** H06.4 export rules with fakes: shared defaults, limit before writing, columns and file names. */
class ExportServiceTest {
    private static final UUID SPACE = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID CATEGORY = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final UUID RECURRENCE = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
    // 02:30 UTC on 1 November is still 31 October in São Paulo.
    private static final Instant NEAR_MIDNIGHT = Instant.parse("2026-11-01T02:30:00Z");

    private final List<ExpenseSelection> counted = new ArrayList<>();
    private final List<String> exportCalls = new ArrayList<>();
    private long count;
    private List<ExportedExpense> rows = List.of();
    private List<PlannedForecast> forecasts = List.of();
    private final List<String> forecastCalls = new ArrayList<>();

    private final ExpenseExportQueries expenses = new ExpenseExportQueries() {
        @Override
        public long count(UUID spaceId, ExpenseSelection selection) {
            assertThat(spaceId).isEqualTo(SPACE);
            counted.add(selection);
            return count;
        }

        @Override
        public void export(UUID spaceId, ExpenseSelection selection, ExpenseSort sort, SortDirection direction,
                int limit, Consumer<ExportedExpense> sink) {
            assertThat(selection).isEqualTo(counted.getLast());
            exportCalls.add(sort + " " + direction + " " + limit);
            rows.forEach(sink);
        }
    };

    private final RecurrenceForecastQueries forecastQueries = (spaceId, from, to) -> {
        forecastCalls.add(spaceId + ":" + from + ".." + to);
        return forecasts;
    };

    private ExportService service(int limit) {
        return new ExportService(expenses, forecastQueries, email -> {
            if (!email.equals("ana@example.com")) throw new AuthenticatedUserContextNotFoundException();
            return new AuthenticatedUserContext(USER, "Ana", email, SPACE, "Casa", SpaceRole.GUEST, "BRL", "pt-BR",
                    "America/Sao_Paulo");
        }, Clock.fixed(NEAR_MIDNIGHT, ZoneOffset.UTC), limit);
    }

    private static ExpenseExportQuery query(ExpenseStatusFilter status) {
        return new ExpenseExportQuery(null, null, null, null, null, false, null, false, null, status, null, null);
    }

    private static ExportedExpense expense(String description, ExpenseStatus status, LocalDate due, String charge,
            boolean confirmed, String paid, LocalDate paymentDate, String origin, InstallmentLink installment) {
        return new ExportedExpense(UUID.nameUUIDFromBytes(description.getBytes(StandardCharsets.UTF_8)), origin,
                installment, description, "Casa", due, new BigDecimal(charge), confirmed, status,
                paid == null ? null : new BigDecimal(paid), paymentDate, "Bia", paid == null ? null : "Ana");
    }

    @Test
    void appliesTheListDefaultsInTheSpaceTimeZoneAndWritesEveryRowInTheListOrder() {
        count = 4;
        rows = List.of(
                expense("Luz", ExpenseStatus.PENDING, LocalDate.of(2026, 10, 30), "210.00", false, null, null,
                        "RECURRENCE", null),
                expense("Água", ExpenseStatus.PENDING, LocalDate.of(2026, 10, 31), "80.10", true, null, null,
                        "ONE_OFF", null),
                expense("Sofá", ExpenseStatus.PAID, LocalDate.of(2026, 10, 5), "333.33", true, "340.00",
                        LocalDate.of(2026, 10, 7), "INSTALLMENT", new InstallmentLink(UUID.randomUUID(), 2, 3)),
                expense("Revisão", ExpenseStatus.CANCELLED, LocalDate.of(2026, 10, 1), "300.00", true, null, null,
                        "ONE_OFF", null));

        var file = service(10).expenses("ana@example.com", new ExpenseExportQuery("  luz ", null, null, null, null,
                false, null, false, null, null, null, null));

        var selection = counted.getFirst();
        assertThat(selection.search()).isEqualTo("luz");
        assertThat(selection.dateFrom()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(selection.dateTo()).isEqualTo(LocalDate.of(2026, 10, 31));
        assertThat(selection.dateBasis()).isEqualTo(ExpenseDateBasis.DUE_DATE);
        assertThat(selection.status()).isEqualTo(ExpenseStatusFilter.ACTIVE);
        assertThat(selection.today()).isEqualTo(LocalDate.of(2026, 10, 31));
        assertThat(exportCalls).containsExactly("REFERENCE_DATE ASC 10");
        assertThat(file.fileName()).isEqualTo("despesas_vencimento_2026-10-01_a_2026-10-31.csv");
        assertThat(file.rows()).isEqualTo(4);
        var lines = new String(file.content(), StandardCharsets.UTF_8).split("\r\n");
        assertThat(lines).hasSize(5);
        assertThat(lines[0]).isEqualTo("﻿\"Descrição\";\"Categoria\";\"Vencimento\";\"Valor da cobrança\";"
                + "\"Estimativa\";\"Situação\";\"Valor pago\";\"Data do pagamento\";\"Responsável\";\"Pagador\";"
                + "\"Origem\";\"Parcela\";\"ID do lançamento\"");
        assertThat(lines[1]).startsWith("\"Luz\";\"Casa\";30/10/2026;210,00;Sim;Atrasada;;;\"Bia\";;Recorrência;;");
        // Due today (31/10 in São Paulo, already 1/11 in UTC) is not overdue yet.
        assertThat(lines[2]).startsWith("\"Água\";\"Casa\";31/10/2026;80,10;Não;Pendente;;;\"Bia\";;Avulsa;;");
        assertThat(lines[3]).startsWith("\"Sofá\";\"Casa\";05/10/2026;333,33;Não;Paga;340,00;07/10/2026;\"Bia\";"
                + "\"Ana\";Parcela;2 de 3;");
        assertThat(lines[4]).startsWith("\"Revisão\";\"Casa\";01/10/2026;300,00;Não;Cancelada;;;\"Bia\";;Avulsa;;");
        assertThat(lines[4]).endsWith(rows.get(3).id().toString());
    }

    @Test
    void refusesASelectionAboveTheLimitBeforeReadingAnyRow() {
        count = 11;
        assertThatThrownBy(() -> service(10).expenses("ana@example.com", query(ExpenseStatusFilter.ALL)))
                .isInstanceOf(ExportLimitExceededException.class)
                .hasMessage("A seleção tem 11 registros e a exportação aceita até 10. Reduza o período ou aplique "
                        + "filtros e exporte em partes.")
                .satisfies(e -> {
                    assertThat(((ExportLimitExceededException) e).rows()).isEqualTo(11);
                    assertThat(((ExportLimitExceededException) e).limit()).isEqualTo(10);
                });
        assertThat(exportCalls).isEmpty();

        count = 10;
        assertThat(service(10).expenses("ana@example.com", query(ExpenseStatusFilter.ALL)).rows()).isZero();
        assertThat(exportCalls).containsExactly("REFERENCE_DATE ASC 10");
        assertThat(new ExportLimitExceededException(12_345, ExportService.MAXIMUM_ROWS).getMessage())
                .startsWith("A seleção tem 12.345 registros e a exportação aceita até 10.000.");
    }

    @Test
    void anEmptySelectionStillProducesTheHeaderOnly() {
        var file = service(10).expenses("ana@example.com", new ExpenseExportQuery(null, LocalDate.of(2026, 1, 1),
                null, ExpenseDateBasis.PAYMENT_DATE, null, false, null, false, null, ExpenseStatusFilter.PAID,
                ExpenseSort.AMOUNT, SortDirection.DESC));

        assertThat(file.rows()).isZero();
        assertThat(new String(file.content(), StandardCharsets.UTF_8)).endsWith("\"ID do lançamento\"\r\n")
                .doesNotContain("\r\n\r\n");
        assertThat(file.fileName()).isEqualTo("despesas_pagamento_desde_2026-01-01.csv");
        assertThat(exportCalls).containsExactly("AMOUNT DESC 10");
        assertThat(counted.getFirst().dateTo()).isNull();
    }

    @Test
    void fileNamesDescribeTheBasisAndThePeriod() {
        var until = new ExpenseSelection(null, null, LocalDate.of(2026, 3, 31), ExpenseDateBasis.DUE_DATE, null,
                false, null, false, null, ExpenseStatusFilter.ACTIVE, null);
        assertThat(ExportService.expenseFileName(until)).isEqualTo("despesas_vencimento_ate_2026-03-31.csv");
        assertThat(ExportService.origin("ONE_OFF")).isEqualTo("Avulsa");
    }

    @Test
    void invalidFiltersAndUnknownMembersAreRefusedBeforeAnyRead() {
        assertThatThrownBy(() -> service(10).expenses("ana@example.com", new ExpenseExportQuery(null, null, null,
                null, CATEGORY, true, null, false, null, null, null, null)))
                .isInstanceOf(ExpenseQueryValidationException.class);
        assertThatThrownBy(() -> service(10).expenses("ana@example.com", new ExpenseExportQuery(null,
                LocalDate.of(2026, 2, 1), LocalDate.of(2026, 1, 1), null, null, false, null, false, null, null, null,
                null))).isInstanceOf(ExpenseQueryValidationException.class);
        assertThatThrownBy(() -> service(10).forecasts("ana@example.com", new ForecastExportQuery(null, null, false,
                USER, true))).isInstanceOf(ExpenseQueryValidationException.class);
        assertThatThrownBy(() -> service(10).forecasts("ana@example.com", new ForecastExportQuery("x".repeat(201),
                null, false, null, false))).isInstanceOf(ExpenseQueryValidationException.class);
        assertThatThrownBy(() -> service(10).expenses("nobody@example.com", query(null)))
                .isInstanceOf(AuthenticatedUserContextNotFoundException.class);
        assertThat(counted).isEmpty();
        assertThat(forecastCalls).isEmpty();
    }

    @Test
    void forecastsGoToTheirOwnFileWithThePlanningFiltersAndHorizon() {
        forecasts = List.of(
                new PlannedForecast(RECURRENCE, LocalDate.of(2026, 11, 5), "Internet", new BigDecimal("100.00"),
                        false, CATEGORY, "Casa", USER, "Bia"),
                new PlannedForecast(RECURRENCE, LocalDate.of(2026, 11, 20), "=Luz", new BigDecimal("210.00"), true,
                        null, null, null, null));

        var all = service(10).forecasts("ana@example.com", new ForecastExportQuery(null, null, false, null, false));

        assertThat(forecastCalls).containsExactly(SPACE + ":2026-10..2027-10");
        assertThat(all.fileName()).isEqualTo("previsoes_2026-10_a_2027-10.csv");
        assertThat(all.rows()).isEqualTo(2);
        var lines = new String(all.content(), StandardCharsets.UTF_8).split("\r\n");
        assertThat(lines[0]).isEqualTo("﻿\"Descrição\";\"Categoria\";\"Vencimento previsto\";\"Valor previsto\";"
                + "\"Estimativa\";\"Responsável\";\"Tipo\";\"ID da recorrência\"");
        assertThat(lines[1]).isEqualTo("\"Internet\";\"Casa\";05/11/2026;100,00;Não;\"Bia\";Previsão de recorrência;"
                + RECURRENCE);
        assertThat(lines[2]).isEqualTo("\"'=Luz\";;20/11/2026;210,00;Sim;;Previsão de recorrência;" + RECURRENCE);

        var home = service(10).forecasts("ana@example.com", new ForecastExportQuery(" INTER ", CATEGORY, false, USER,
                false));
        assertThat(home.rows()).isEqualTo(1);
        assertThat(service(1).forecasts("ana@example.com", new ForecastExportQuery(null, null, true, null, false))
                .rows()).isEqualTo(1);
        assertThatThrownBy(() -> service(1).forecasts("ana@example.com", new ForecastExportQuery(null, null, false,
                null, false))).isInstanceOf(ExportLimitExceededException.class);
    }

    @Test
    void theLimitMustBePositive() {
        assertThatThrownBy(() -> service(0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(ExportService.MAXIMUM_ROWS).isEqualTo(10_000);
    }
}
