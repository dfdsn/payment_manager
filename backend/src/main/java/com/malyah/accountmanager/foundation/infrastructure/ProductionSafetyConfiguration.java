package com.malyah.accountmanager.foundation.infrastructure;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
class ProductionSafetyConfiguration {

    ProductionSafetyConfiguration(Environment environment) {
        var appEnvironment = environment.getProperty("APP_ENVIRONMENT", "local");
        var integrationsMode = environment.getProperty("APP_INTEGRATIONS_MODE", "disabled");
        if ("production".equalsIgnoreCase(appEnvironment)
                && !"real".equalsIgnoreCase(integrationsMode)) {
            throw new IllegalStateException("Produção exige APP_INTEGRATIONS_MODE=real");
        }
    }
}

