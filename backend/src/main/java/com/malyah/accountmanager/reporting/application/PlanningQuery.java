package com.malyah.accountmanager.reporting.application;

import java.util.UUID;

/**
 * H06.3 query. The horizon is fixed (current month of the space plus 12); {@code month} picks which month of it is
 * listed, absent meaning the current one. The filters are those of the expense list that also apply to a forecast:
 * description, category and responsible. Payer and situation do not apply: a forecast has no payer, and the
 * planning always holds the active (pending and paid) expenses plus forecasts.
 */
public record PlanningQuery(String month, String search, UUID categoryId, boolean withoutCategory,
        UUID responsibleUserId, boolean withoutResponsible, int page, int size) {
    public static final int DEFAULT_SIZE = 20;
    public static final int MAXIMUM_SIZE = 100;

    public static PlanningQuery currentMonth() {
        return new PlanningQuery(null, null, null, false, null, false, 0, DEFAULT_SIZE);
    }
}
