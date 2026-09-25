package com.malyah.accountmanager.identity.infrastructure;

import org.springframework.jdbc.core.JdbcTemplate;

import com.malyah.accountmanager.identity.application.port.SessionRevoker;

final class JdbcSessionRevoker implements SessionRevoker {

    private final JdbcTemplate jdbcTemplate;

    JdbcSessionRevoker(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void revokeAll(String normalizedEmail) {
        jdbcTemplate.update("delete from spring_session where principal_name = ?", normalizedEmail);
    }
}
