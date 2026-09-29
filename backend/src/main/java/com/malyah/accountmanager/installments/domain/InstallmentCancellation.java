package com.malyah.accountmanager.installments.domain;

import java.util.List;
import java.util.TreeSet;

/**
 * RF-PAR-07/08, H05.3: cancels the selected pending installments with a reason. Nothing is refunded or reversed:
 * paid installments cannot be selected and stay as they are.
 */
public record InstallmentCancellation(List<Integer> numbers, String reason) {
    public InstallmentCancellation {
        if (numbers == null || numbers.isEmpty())
            throw new InstallmentValidationException("installmentNumbers", "Selecione ao menos uma parcela pendente.");
        var distinct = new TreeSet<Integer>();
        for (var number : numbers) {
            if (number == null || !distinct.add(number))
                throw new InstallmentValidationException("installmentNumbers", "Selecione cada parcela uma única vez.");
        }
        numbers = List.copyOf(distinct);
        reason = reason == null ? "" : reason.trim();
        if (reason.isEmpty() || reason.length() > 2000)
            throw new InstallmentValidationException("reason", "Informe o motivo do cancelamento com até 2.000 caracteres.");
    }

    /** Fails when a selected number does not exist or is no longer pending. */
    public List<Integer> select(List<InstallmentState> installments) {
        for (var number : numbers) {
            var installment = installments.stream().filter(i -> i.number() == number).findFirst()
                    .orElseThrow(() -> new InstallmentValidationException("installmentNumbers",
                            "A parcela " + number + " não existe nesta compra."));
            if (!installment.pending())
                throw new InstallmentStateConflictException("A parcela " + number + " não está pendente; pagas e canceladas não mudam.");
        }
        return numbers;
    }
}
