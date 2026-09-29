package com.malyah.accountmanager.reporting.application;

import java.util.List;
import java.util.UUID;

import com.malyah.accountmanager.reporting.domain.ClosingChange;
import com.malyah.accountmanager.reporting.domain.ClosingField;

/** One difference between the saved version and the current data, with both lines as they are. */
public record ClosingChangeView(String kind, UUID expenseId, List<String> fields, ClosingLineView saved,
        ClosingLineView current) {
    static ClosingChangeView of(ClosingChange change) {
        return new ClosingChangeView(change.kind().name(), change.expenseId(),
                change.fields().stream().map(ClosingField::name).toList(),
                change.saved() == null ? null : ClosingLineView.of(change.saved()),
                change.current() == null ? null : ClosingLineView.of(change.current()));
    }
}
