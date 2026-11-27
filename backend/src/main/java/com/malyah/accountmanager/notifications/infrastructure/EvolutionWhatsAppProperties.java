package com.malyah.accountmanager.notifications.infrastructure;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * T36: Evolution API configuration, from environment variables or secret files (never from the database or the
 * repository). Everything is off by default; the provider is available only when enabled and every value needed to
 * send and to authenticate the webhook is present. {@link #toString()} never prints a secret.
 */
record EvolutionWhatsAppProperties(boolean enabled, String apiBaseUrl, String apiKey, String instance,
        String webhookToken, Duration connectTimeout, Duration requestTimeout) {
    /** A webhook secret shorter than this is treated as missing. */
    static final int MIN_WEBHOOK_TOKEN = 16;

    EvolutionWhatsAppProperties {
        apiBaseUrl = trim(apiBaseUrl);
        apiKey = trim(apiKey);
        instance = trim(instance);
        webhookToken = trim(webhookToken);
    }

    /** Everything off: the provider reports itself disabled and never sends. */
    static EvolutionWhatsAppProperties disabled() {
        return new EvolutionWhatsAppProperties(false, "", "", "", "", Duration.ofSeconds(5), Duration.ofSeconds(15));
    }

    /** Names of the missing settings (never their values), for the startup log and the diagnosis. */
    List<String> missing() {
        var missing = new ArrayList<String>();
        if (!apiBaseUrl.matches("https?://[^\\s/?#]+(/[^\\s?#]*)?")) missing.add("EVOLUTION_API_BASE_URL");
        if (apiKey.isEmpty()) missing.add("EVOLUTION_API_KEY");
        if (!instance.matches("[A-Za-z0-9_.-]{1,64}")) missing.add("EVOLUTION_API_INSTANCE");
        if (webhookToken.length() < MIN_WEBHOOK_TOKEN) missing.add("EVOLUTION_WEBHOOK_TOKEN");
        return missing;
    }

    boolean configured() {
        return enabled && missing().isEmpty();
    }

    /** The webhook answers only when it can authenticate the Evolution API and knows which instance is ours. */
    boolean webhookConfigured() {
        return configured();
    }

    URI sendTextUri() {
        return URI.create(base() + "/message/sendText/" + encodedInstance());
    }

    URI connectionStateUri() {
        return URI.create(base() + "/instance/connectionState/" + encodedInstance());
    }

    /**
     * Production talks to the Evolution API over HTTPS, except inside the server itself: {@code localhost}, a
     * loopback address or a single-label host (a service name of the Docker network, such as
     * {@code http://evolution-api:8080}), which never leaves the machine.
     */
    void requireSecureIn(boolean production) {
        if (!production || !enabled || apiBaseUrl.startsWith("https://")) return;
        if (!internal(host()))
            throw new IllegalStateException("EVOLUTION_API_BASE_URL precisa usar HTTPS em produção, salvo um "
                    + "endereço interno do servidor (localhost ou nome de serviço da rede Docker)");
    }

    @Override
    public String toString() {
        return "EvolutionWhatsAppProperties[enabled=" + enabled + ", instance=" + instance + ", missing=" + missing()
                + "]";
    }

    private String host() {
        try {
            var host = URI.create(apiBaseUrl).getHost();
            return host == null ? "" : host.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException invalid) {
            return "";
        }
    }

    private static boolean internal(String host) {
        return host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]")
                || host.matches("[a-z0-9]([a-z0-9-]*[a-z0-9])?");
    }

    private String base() {
        return apiBaseUrl.endsWith("/") ? apiBaseUrl.substring(0, apiBaseUrl.length() - 1) : apiBaseUrl;
    }

    private String encodedInstance() {
        return URLEncoder.encode(instance, StandardCharsets.UTF_8);
    }

    private static String trim(String value) {
        return value == null ? "" : value.strip();
    }
}
