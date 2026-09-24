package com.malyah.accountmanager.identity.api;

import java.security.Principal;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.malyah.accountmanager.identity.application.AuthenticatedUserContext;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContextQuery;

@RestController
@RequestMapping("/identity")
class AuthenticatedUserContextController {

    private final AuthenticatedUserContextQuery query;

    AuthenticatedUserContextController(AuthenticatedUserContextQuery query) {
        this.query = query;
    }

    @GetMapping("/me")
    AuthenticatedUserContext current(Principal principal) {
        return query.findByEmail(principal.getName());
    }
}
