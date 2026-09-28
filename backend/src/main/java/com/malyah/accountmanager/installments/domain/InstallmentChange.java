package com.malyah.accountmanager.installments.domain;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import com.malyah.accountmanager.recurrences.domain.RecurrenceCalendar;
import com.malyah.accountmanager.recurrences.domain.RecurrenceFrequency;

/**
 * RF-PAR-05/06, H05.3: description, category and responsible change on the chosen installment only, or on it and the
 * following pending installments. A new due date moves the chosen installment; with "this and following" the next
 * pending installments are recalculated month by month from it with the purchase calendar (short months use their
 * last day). Paid and cancelled installments are never part of the plan, and the amount never changes.
 */
public record InstallmentChange(int fromNumber, InstallmentChangeScope scope, Set<String> fields, String description,
        UUID categoryId, UUID responsibleUserId, LocalDate dueDate) {
    public static final List<String> FIELDS = List.of("description", "categoryId", "responsibleUserId", "dueDate");
    private static final RecurrenceCalendar CALENDAR = new RecurrenceCalendar();

    public InstallmentChange {
        if (scope == null) throw new InstallmentValidationException("scope", "Escolha o alcance da alteração.");
        if (fields == null || fields.isEmpty())
            throw new InstallmentValidationException("changedFields", "Escolha ao menos um campo para alterar.");
        if (!FIELDS.containsAll(fields))
            throw new InstallmentValidationException("changedFields",
                    "Só descrição, categoria, responsável e vencimento podem ser alterados; valor e quantidade exigem cancelar e criar nova compra.");
        fields = Set.copyOf(fields);
        if (fields.contains("description")) {
            description = description == null ? "" : description.trim();
            if (description.isEmpty() || description.length() > 200)
                throw new InstallmentValidationException("description", "Informe uma descrição de até 200 caracteres.");
        } else description = null;
        if (fields.contains("dueDate") && dueDate == null)
            throw new InstallmentValidationException("dueDate", "Informe o novo vencimento.");
        if (!fields.contains("dueDate")) dueDate = null;
        if (!fields.contains("categoryId")) categoryId = null;
        if (!fields.contains("responsibleUserId")) responsibleUserId = null;
    }

    /** The updates to apply, in number order; fails when the starting installment is missing or not pending. */
    public List<PlannedInstallmentUpdate> plan(List<InstallmentState> installments) {
        var start = installments.stream().filter(i -> i.number() == fromNumber).findFirst()
                .orElseThrow(() -> new InstallmentValidationException("fromNumber", "Escolha uma parcela desta compra."));
        if (!start.pending())
            throw new InstallmentStateConflictException("A parcela " + fromNumber + " não está pendente; pagas e canceladas não mudam.");
        var result = new ArrayList<PlannedInstallmentUpdate>();
        for (var installment : installments) {
            if (!installment.pending() || installment.number() < fromNumber) continue;
            if (scope == InstallmentChangeScope.THIS && installment.number() != fromNumber) continue;
            var newDescription = fields.contains("description") ? description : installment.description();
            var newCategory = fields.contains("categoryId") ? categoryId : installment.categoryId();
            var newResponsible = fields.contains("responsibleUserId") ? responsibleUserId : installment.responsibleUserId();
            var newDue = fields.contains("dueDate") ? dueDateOf(installment.number()) : installment.dueDate();
            var changed = new LinkedHashSet<String>();
            if (!newDescription.equals(installment.description())) changed.add("description");
            if (!Objects.equals(newCategory, installment.categoryId())) changed.add("categoryId");
            if (!Objects.equals(newResponsible, installment.responsibleUserId())) changed.add("responsibleUserId");
            if (!newDue.equals(installment.dueDate())) changed.add("dueDate");
            if (!changed.isEmpty()) result.add(new PlannedInstallmentUpdate(installment.number(), newDescription,
                    newDue, newCategory, newResponsible, List.copyOf(changed)));
        }
        if (result.isEmpty())
            throw new InstallmentValidationException("changedFields", "Nenhuma parcela pendente muda com esses valores.");
        return List.copyOf(result);
    }

    private LocalDate dueDateOf(int number) {
        var month = YearMonth.from(dueDate).plusMonths(number - (long) fromNumber);
        return CALENDAR.occurrenceInMonth(dueDate, null, RecurrenceFrequency.MONTHLY, month).orElseThrow();
    }
}
