package com.malyah.accountmanager.identity.application;

public interface InitialSetupUseCase {

    InitialSetupStatus status();

    InitialSetupResult configure(InitialSetupCommand command);
}
