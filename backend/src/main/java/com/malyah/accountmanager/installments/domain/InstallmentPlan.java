package com.malyah.accountmanager.installments.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import com.malyah.accountmanager.recurrences.domain.RecurrenceCalendar;
import com.malyah.accountmanager.recurrences.domain.RecurrenceFrequency;

/**
 * RF-PAR-01 to 03, D18: a purchase total split into 2 to 360 monthly installments. The division works on whole
 * cents: every installment gets the integer quotient and the last one also gets the remainder, so the sum is exactly
 * the total and no installment is below R$ 0,01. Due dates follow the approved monthly calendar of H04.1: the first
 * due date fixes the day, short months use their last day and later months return to the original day.
 */
public record InstallmentPlan(String description, BigDecimal total, int count, LocalDate firstDueDate,
        List<Installment> installments) {
    public static final int MINIMUM_COUNT = 2;
    public static final int MAXIMUM_COUNT = 360;
    private static final BigDecimal MAXIMUM_TOTAL = new BigDecimal("99999999.99");
    private static final BigDecimal CENTS = BigDecimal.valueOf(100);
    private static final RecurrenceCalendar CALENDAR = new RecurrenceCalendar();

    public InstallmentPlan {
        installments = List.copyOf(installments);
    }

    public static InstallmentPlan calculate(String description, BigDecimal total, Integer count, LocalDate firstDueDate) {
        var normalized = description == null ? "" : description.trim();
        if (normalized.isEmpty() || normalized.length() > 200)
            throw new InstallmentValidationException("description", "Informe uma descrição de até 200 caracteres.");
        if (total == null || total.scale() > 2 || total.signum() <= 0 || total.compareTo(MAXIMUM_TOTAL) > 0)
            throw new InstallmentValidationException("totalAmount",
                    "Informe um valor total entre R$ 0,01 e R$ 99.999.999,99 com até duas casas decimais.");
        if (count == null || count < MINIMUM_COUNT || count > MAXIMUM_COUNT)
            throw new InstallmentValidationException("installmentCount", "Informe de 2 a 360 parcelas.");
        if (firstDueDate == null)
            throw new InstallmentValidationException("firstDueDate", "Informe o vencimento da primeira parcela.");
        var cents = total.multiply(CENTS).longValueExact();
        if (cents < count)
            throw new InstallmentValidationException("totalAmount",
                    "O valor total não permite " + count + " parcelas de pelo menos R$ 0,01.");
        var base = cents / count;
        var last = base + cents % count;
        var first = YearMonth.from(firstDueDate);
        var result = new ArrayList<Installment>(count);
        for (int number = 1; number <= count; number++) {
            var month = first.plusMonths(number - 1L);
            var due = CALENDAR.occurrenceInMonth(firstDueDate, null, RecurrenceFrequency.MONTHLY, month).orElseThrow();
            result.add(new Installment(number, BigDecimal.valueOf(number == count ? last : base, 2), due));
        }
        return new InstallmentPlan(normalized, total.setScale(2), count, firstDueDate, result);
    }

    public LocalDate lastDueDate() { return installments.getLast().dueDate(); }

    /** Cents added to the last installment so that the sum matches the total exactly. */
    public BigDecimal lastInstallmentAdjustment() {
        return installments.getLast().amount().subtract(installments.getFirst().amount());
    }
}
