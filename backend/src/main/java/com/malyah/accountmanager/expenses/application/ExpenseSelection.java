package com.malyah.accountmanager.expenses.application;

import java.time.LocalDate;
import java.util.UUID;

/**
 * H03.4 filter contract shared by the expense list and, since E06, by the aggregated reports: the same selection
 * always yields the same population, whether it is paged or summed. {@code today} is the local business date of
 * the space and defines the overdue projection.
 */
public record ExpenseSelection(String search, LocalDate dateFrom, LocalDate dateTo, ExpenseDateBasis dateBasis,
        UUID categoryId, boolean withoutCategory, UUID responsibleUserId, boolean withoutResponsible,
        UUID payerUserId, ExpenseStatusFilter status, LocalDate today) {
    public static final int MAXIMUM_SEARCH_LENGTH = 200;

    /** Rejects combinations that have no meaning; shared so reports and the list refuse the same inputs. */
    public void validate() {
        if (search != null && search.trim().length() > MAXIMUM_SEARCH_LENGTH)
            throw new ExpenseQueryValidationException("search", "A busca deve ter até 200 caracteres.");
        if (dateFrom != null && dateTo != null && dateFrom.isAfter(dateTo))
            throw new ExpenseQueryValidationException("dateFrom", "A data inicial não pode ser posterior à final.");
        if (categoryId != null && withoutCategory)
            throw new ExpenseQueryValidationException("category", "Escolha uma categoria ou Sem categoria.");
        if (responsibleUserId != null && withoutResponsible)
            throw new ExpenseQueryValidationException("responsible", "Escolha um responsável ou Sem responsável.");
    }

    /**
     * The defaults of the list (H03.4) applied once, so the list and the CSV export select the same population:
     * trimmed search, due-date basis, active status and, when neither date is given, the current local month.
     */
    public ExpenseSelection effective(LocalDate localToday) {
        var from = dateFrom;
        var to = dateTo;
        if (from == null && to == null) {
            from = localToday.withDayOfMonth(1);
            to = localToday.withDayOfMonth(localToday.lengthOfMonth());
        }
        return new ExpenseSelection(search == null ? null : search.trim(), from, to,
                dateBasis == null ? ExpenseDateBasis.DUE_DATE : dateBasis, categoryId, withoutCategory,
                responsibleUserId, withoutResponsible, payerUserId,
                status == null ? ExpenseStatusFilter.ACTIVE : status, localToday);
    }

    public ExpenseSelection withPeriod(LocalDate from, LocalDate to) {
        return new ExpenseSelection(search, from, to, dateBasis, categoryId, withoutCategory, responsibleUserId,
                withoutResponsible, payerUserId, status, today);
    }

    public ExpenseSelection withStatus(ExpenseStatusFilter newStatus) {
        return new ExpenseSelection(search, dateFrom, dateTo, dateBasis, categoryId, withoutCategory,
                responsibleUserId, withoutResponsible, payerUserId, newStatus, today);
    }
}
