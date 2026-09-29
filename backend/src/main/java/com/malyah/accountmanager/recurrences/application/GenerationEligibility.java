package com.malyah.accountmanager.recurrences.application;

import java.util.Set;
import java.util.UUID;

/**
 * Categories not archived and members still active in a space: the generation drops any other category or
 * responsible, so forecasts show what the materialization would record.
 */
public record GenerationEligibility(Set<UUID> activeCategoryIds, Set<UUID> activeMemberIds) {
    public GenerationEligibility {
        activeCategoryIds = Set.copyOf(activeCategoryIds);
        activeMemberIds = Set.copyOf(activeMemberIds);
    }
}
