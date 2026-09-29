package com.malyah.accountmanager.notifications.infrastructure;

import java.net.URI;
import java.time.Duration;

/**
 * H08.4: Meta Cloud API configuration, from environment variables or secret files (never from the database or the
 * repository). Everything is off by default; the provider is available only when enabled and every value needed to
 * send and to authenticate the webhook is present. {@link #toString()} never prints a secret.
 */
record MetaWhatsAppProperties(boolean enabled, String apiBaseUrl, String apiVersion, String phoneNumberId,
        String accessToken, String appSecret, String verifyToken, String summaryTemplate, String templateLanguage,
        String testTemplate, String testTemplateLanguage, Duration connectTimeout, Duration requestTimeout) {

    MetaWhatsAppProperties {
        apiBaseUrl = trim(apiBaseUrl);
        apiVersion = trim(apiVersion);
        phoneNumberId = trim(phoneNumberId);
        accessToken = trim(accessToken);
        appSecret = trim(appSecret);
        verifyToken = trim(verifyToken);
        summaryTemplate = trim(summaryTemplate);
        templateLanguage = trim(templateLanguage);
        testTemplate = trim(testTemplate);
        testTemplateLanguage = trim(testTemplateLanguage);
    }

    /** Everything off: the provider reports itself disabled and never sends. */
    static MetaWhatsAppProperties disabled() {
        return new MetaWhatsAppProperties(false, "https://graph.facebook.com", "", "", "", "", "", "", "pt_BR", "",
                "pt_BR", Duration.ofSeconds(5), Duration.ofSeconds(15));
    }

    /** Names of the missing settings (never their values), for the startup log and the diagnosis. */
    java.util.List<String> missing() {
        var missing = new java.util.ArrayList<String>();
        if (apiBaseUrl.isEmpty()) missing.add("META_WHATSAPP_API_BASE_URL");
        if (!apiVersion.matches("v[0-9]+\\.[0-9]+")) missing.add("META_WHATSAPP_API_VERSION");
        if (!phoneNumberId.matches("[0-9]{5,32}")) missing.add("META_WHATSAPP_PHONE_NUMBER_ID");
        if (accessToken.isEmpty()) missing.add("META_WHATSAPP_TOKEN");
        if (appSecret.isEmpty()) missing.add("META_WHATSAPP_APP_SECRET");
        if (verifyToken.isEmpty()) missing.add("META_WHATSAPP_VERIFY_TOKEN");
        if (!summaryTemplate.matches("[a-z0-9_]{1,512}")) missing.add("META_WHATSAPP_SUMMARY_TEMPLATE");
        if (!templateLanguage.matches("[a-z]{2,3}(_[A-Z]{2})?")) missing.add("META_WHATSAPP_TEMPLATE_LANGUAGE");
        return missing;
    }

    boolean configured() {
        return enabled && missing().isEmpty();
    }

    boolean testTemplateConfigured() {
        return testTemplate.matches("[a-z0-9_]{1,512}") && testTemplateLanguage.matches("[a-z]{2,3}(_[A-Z]{2})?");
    }

    /** The webhook answers only when it can authenticate Meta. */
    boolean webhookConfigured() {
        return enabled && !appSecret.isEmpty() && !verifyToken.isEmpty();
    }

    URI messagesUri() {
        var base = apiBaseUrl.endsWith("/") ? apiBaseUrl.substring(0, apiBaseUrl.length() - 1) : apiBaseUrl;
        return URI.create(base + "/" + apiVersion + "/" + phoneNumberId + "/messages");
    }

    /** Production only talks to the provider over HTTPS. */
    void requireSecureIn(boolean production) {
        if (production && enabled && !apiBaseUrl.startsWith("https://"))
            throw new IllegalStateException("META_WHATSAPP_API_BASE_URL precisa usar HTTPS em produção");
    }

    @Override
    public String toString() {
        return "MetaWhatsAppProperties[enabled=" + enabled + ", apiVersion=" + apiVersion + ", missing=" + missing()
                + "]";
    }

    private static String trim(String value) {
        return value == null ? "" : value.strip();
    }
}
