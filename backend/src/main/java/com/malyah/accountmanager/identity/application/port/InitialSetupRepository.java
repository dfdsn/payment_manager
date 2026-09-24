package com.malyah.accountmanager.identity.application.port;

import com.malyah.accountmanager.identity.application.InitialSetupRegistration;

public interface InitialSetupRepository {

    boolean isCompleted();

    boolean lockAndCheckCompleted();

    void create(InitialSetupRegistration registration);

    void markCompleted(InitialSetupRegistration registration);
}
