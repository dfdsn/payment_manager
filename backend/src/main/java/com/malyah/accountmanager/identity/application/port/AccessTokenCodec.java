package com.malyah.accountmanager.identity.application.port;

public interface AccessTokenCodec {

    String generate();

    String hash(String rawToken);
}
