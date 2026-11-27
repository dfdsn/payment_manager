package com.malyah.accountmanager.notifications.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * T36: the Evolution API webhook carries no body signature, so it is authenticated by a shared secret sent in a
 * custom header configured on the instance's webhook. Compared in constant time; an empty secret never matches.
 */
public final class WebhookToken {
    private WebhookToken() { }

    public static boolean matches(String expected, String received) {
        if (expected == null || expected.isEmpty() || received == null) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                received.getBytes(StandardCharsets.UTF_8));
    }
}
