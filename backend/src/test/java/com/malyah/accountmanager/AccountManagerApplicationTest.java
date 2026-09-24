package com.malyah.accountmanager;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.malyah.accountmanager.identity.application.InitialSetupUseCase;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContextQuery;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration"
})
class AccountManagerApplicationTest {

    @MockitoBean
    private InitialSetupUseCase initialSetupUseCase;

    @MockitoBean
    private AuthenticatedUserContextQuery authenticatedUserContextQuery;

    @Test
    void startsSpringContext() {
    }
}
