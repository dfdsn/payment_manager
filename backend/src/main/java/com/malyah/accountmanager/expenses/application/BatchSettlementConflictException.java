package com.malyah.accountmanager.expenses.application;

import java.util.List;

public final class BatchSettlementConflictException extends RuntimeException {
    private final List<BatchSettlementItemProblem> problems;

    public BatchSettlementConflictException(List<BatchSettlementItemProblem> problems) {
        super("O lote não foi aplicado. Revise os lançamentos indicados e tente novamente.");
        this.problems = List.copyOf(problems);
    }

    public List<BatchSettlementItemProblem> problems() {
        return problems;
    }
}
