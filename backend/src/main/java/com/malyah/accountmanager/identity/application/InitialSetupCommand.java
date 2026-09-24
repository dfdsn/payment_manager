package com.malyah.accountmanager.identity.application;

public record InitialSetupCommand(
        String setupSecret,
        String administratorName,
        String email,
        char[] password,
        String spaceName) {

    @Override
    public String toString() {
        return "InitialSetupCommand[protected]";
    }
}
