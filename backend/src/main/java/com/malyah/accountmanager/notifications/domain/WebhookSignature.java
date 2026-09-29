package com.malyah.accountmanager.notifications.domain;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * H08.4: the Meta webhook signature, {@code X-Hub-Signature-256: sha256=<hex>}, an HMAC-SHA256 of the raw request
 * body keyed with the app secret. Compared in constant time; any missing or malformed part is simply invalid.
 */
public final class WebhookSignature {
    private static final String PREFIX = "sha256=";

    private WebhookSignature() { }

    public static boolean matches(String appSecret, byte[] body, String header) {
        if (appSecret == null || appSecret.isEmpty() || body == null || header == null || !header.startsWith(PREFIX))
            return false;
        byte[] received;
        try {
            received = HexFormat.of().parseHex(header.substring(PREFIX.length()).toLowerCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException malformed) {
            return false;
        }
        return MessageDigest.isEqual(sign(appSecret, body), received);
    }

    public static String header(String appSecret, byte[] body) {
        return PREFIX + HexFormat.of().formatHex(sign(appSecret, body));
    }

    /** Constant-time comparison of two secrets (the webhook verify token). */
    public static boolean sameSecret(String expected, String received) {
        if (expected == null || expected.isEmpty() || received == null) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), received.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] sign(String appSecret, byte[] body) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(appSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(body);
        } catch (NoSuchAlgorithmException | InvalidKeyException unavailable) {
            throw new IllegalStateException("HmacSHA256 indisponível", unavailable);
        }
    }
}
