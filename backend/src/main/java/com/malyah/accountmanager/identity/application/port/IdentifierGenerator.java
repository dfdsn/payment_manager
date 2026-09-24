package com.malyah.accountmanager.identity.application.port;

import java.util.UUID;

public interface IdentifierGenerator {

    UUID next();
}
