package com.malyah.accountmanager;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.malyah.accountmanager.identity.application.InitialSetupUseCase;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContextQuery;
import com.malyah.accountmanager.identity.application.AccountAccessUseCase;
import com.malyah.accountmanager.identity.application.LoginUseCase;
import com.malyah.accountmanager.identity.application.InvitationUseCase;
import com.malyah.accountmanager.identity.application.MembershipManagementUseCase;
import com.malyah.accountmanager.identity.application.port.SessionRevoker;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration"
})
class AccountManagerApplicationTest {

    @MockitoBean
    private InitialSetupUseCase initialSetupUseCase;

    @MockitoBean
    private AuthenticatedUserContextQuery authenticatedUserContextQuery;

    @MockitoBean
    private AccountAccessUseCase accountAccessUseCase;

    @MockitoBean
    private LoginUseCase loginUseCase;

    @MockitoBean
    private InvitationUseCase invitationUseCase;

    @MockitoBean
    private MembershipManagementUseCase membershipManagementUseCase;

    @MockitoBean
    private SessionRevoker sessionRevoker;

    @Test
    void startsSpringContext() {
    }
}
