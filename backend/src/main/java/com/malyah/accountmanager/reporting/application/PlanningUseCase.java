package com.malyah.accountmanager.reporting.application;

public interface PlanningUseCase {
    PlanningView planning(String actorEmail, PlanningQuery query);
}
