package com.malyah.accountmanager.notifications.infrastructure;

import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.UnresolvedAddressException;
import java.time.Duration;
import java.util.Locale;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.malyah.accountmanager.notifications.application.port.WhatsAppProviderStatus;
import com.malyah.accountmanager.notifications.application.port.WhatsAppSender;

/**
 * T36: the Evolution API adapter (self-hosted by Diego). Each send is two calls with the {@code apikey} header:
 * <ol>
 * <li>{@code GET /instance/connectionState/<instance>}: when the instance is not {@code open} (WhatsApp
 * disconnected, waiting for the QR code) nothing is sent and the attempt is unavailable, so H08.5 tries the same
 * delivery again inside its window; a refused key or an unknown instance is a permanent refusal.</li>
 * <li>{@code POST /message/sendText/<instance>} with {@code {"number", "text"}}.</li>
 * </ol>
 * The answer is classified conservatively: only a 2xx carrying {@code key.id} is an acceptance (never a delivery);
 * a request that certainly did not leave (connection refused, unknown host, connect timeout) or was throttled (429)
 * is unavailable; a number without WhatsApp is an invalid recipient; any other 4xx is a refusal; everything else,
 * including a timeout after sending and a 5xx, is uncertain and never resent. Neither the key, the request body nor
 * the response body is ever logged or returned.
 */
final class EvolutionWhatsAppProvider implements WhatsAppProviderStatus, WhatsAppSender {
    static final String DISABLED = "PROVIDER_DISABLED";
    static final String NOT_CONFIGURED = "PROVIDER_NOT_CONFIGURED";
    static final String READY = "PROVIDER_READY";
    /** The number is not on WhatsApp (Evolution answers 400 with {@code exists: false}). */
    static final String NOT_ON_WHATSAPP = "NOT_ON_WHATSAPP";
    /** The connection-state check itself failed before anything was sent. */
    static final String STATE_UNKNOWN = "CONN_UNKNOWN";
    /** Longest provider wait taken into account; anything longer ends the window anyway. */
    static final Duration MAX_RETRY_AFTER = Duration.ofHours(1);
    private static final int MAX_ID_LENGTH = 128;

    private final EvolutionWhatsAppProperties properties;
    private final HttpClient client;
    private final JsonMapper json = JsonMapper.builder().build();

    EvolutionWhatsAppProvider(EvolutionWhatsAppProperties properties) {
        this.properties = properties;
        this.client = HttpClient.newBuilder().connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override
    public Availability availability() {
        if (!properties.enabled())
            return new Availability(false, DISABLED, "O envio real pela Evolution API está desligado na configuração "
                    + "do servidor. A configuração fica salva e nenhum resumo é enviado pelo WhatsApp.");
        if (!properties.configured())
            return new Availability(false, NOT_CONFIGURED, "O envio pela Evolution API está ligado, mas a "
                    + "configuração do servidor está incompleta. Nenhum resumo é enviado pelo WhatsApp até ela ser "
                    + "corrigida.");
        return new Availability(true, READY, "Envio pela Evolution API configurado. Aceite da Evolution API não é "
                + "entrega: a confirmação chega depois pelo webhook. Se a instância estiver desconectada do "
                + "WhatsApp, o resumo espera uma nova tentativa dentro do horário.");
    }

    @Override
    public SendResult send(Message message) {
        if (!properties.configured()) return SendResult.of(Outcome.UNAVAILABLE, null);
        var blocked = connectionState();
        if (blocked != null) return blocked;
        var request = HttpRequest.newBuilder(properties.sendTextUri())
                .timeout(properties.requestTimeout())
                .header("apikey", properties.apiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body(message)))
                .build();
        HttpResponse<byte[]> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (HttpConnectTimeoutException | ConnectException | UnknownHostException notSent) {
            return SendResult.of(Outcome.UNAVAILABLE, null);
        } catch (IOException | UnresolvedAddressException lostAfterSending) {
            // A timeout or a dropped connection does not prove the Evolution API did not receive the request.
            return SendResult.of(Outcome.UNCERTAIN, null);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return SendResult.of(Outcome.UNCERTAIN, null);
        }
        return classify(response.statusCode(), response.body(),
                response.headers().firstValue("Retry-After").orElse(null));
    }

    /**
     * {@code null} when the instance is connected ({@code state: "open"}); otherwise why nothing is sent. Nothing
     * has left at this point, so every failure of the check itself is a certain unavailability.
     */
    SendResult connectionState() {
        var request = HttpRequest.newBuilder(properties.connectionStateUri())
                .timeout(properties.requestTimeout())
                .header("apikey", properties.apiKey())
                .GET()
                .build();
        HttpResponse<byte[]> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException | UnresolvedAddressException unreachable) {
            return SendResult.of(Outcome.UNAVAILABLE, STATE_UNKNOWN);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return SendResult.of(Outcome.UNAVAILABLE, STATE_UNKNOWN);
        }
        return classifyState(response.statusCode(), response.body(),
                response.headers().firstValue("Retry-After").orElse(null));
    }

    SendResult classifyState(int status, byte[] body, String retryAfterHeader) {
        if (status >= 200 && status < 300) {
            var state = read(body).path("instance").path("state");
            var value = state.isString() ? state.stringValue().strip().toLowerCase(Locale.ROOT) : "";
            if (value.equals("open")) return null;
            return SendResult.of(Outcome.UNAVAILABLE, value.matches("[a-z_]{1,11}")
                    ? "CONN_" + value.toUpperCase(Locale.ROOT) : STATE_UNKNOWN);
        }
        if (status == 401 || status == 403 || status == 404) return SendResult.of(Outcome.REJECTED, "STATE_" + status);
        if (status == 429)
            return new SendResult(Outcome.UNAVAILABLE, null, "STATE_429", retryAfter(retryAfterHeader));
        return SendResult.of(Outcome.UNAVAILABLE, "STATE_" + status);
    }

    SendResult classify(int status, byte[] body) {
        return classify(status, body, null);
    }

    /** H08.5: a {@code Retry-After} in seconds is kept for a throttled request; other forms are ignored. */
    SendResult classify(int status, byte[] body, String retryAfterHeader) {
        var root = read(body);
        if (status >= 200 && status < 300) {
            var id = root.path("key").path("id");
            var value = id.isString() ? id.stringValue().strip() : "";
            return value.isEmpty() || value.length() > MAX_ID_LENGTH ? SendResult.of(Outcome.UNCERTAIN, null)
                    : SendResult.accepted(value);
        }
        var reported = String.valueOf(status);
        if (status == 429) return new SendResult(Outcome.UNAVAILABLE, null, reported, retryAfter(retryAfterHeader));
        if (status == 400 && numberMissing(root)) return SendResult.of(Outcome.RECIPIENT_INVALID, NOT_ON_WHATSAPP);
        if (status >= 400 && status < 500) return SendResult.of(Outcome.REJECTED, reported);
        return SendResult.of(Outcome.UNCERTAIN, reported);
    }

    /** {@code {"response":{"message":[{"exists":false, ...}]}}}: the number has no WhatsApp account. */
    private static boolean numberMissing(JsonNode root) {
        for (var item : root.path("response").path("message"))
            if (item.path("exists").isBoolean() && !item.path("exists").booleanValue()) return true;
        return false;
    }

    static Duration retryAfter(String header) {
        if (header == null || !header.strip().matches("[0-9]{1,6}")) return null;
        var seconds = Duration.ofSeconds(Long.parseLong(header.strip()));
        return seconds.compareTo(MAX_RETRY_AFTER) > 0 ? MAX_RETRY_AFTER : seconds;
    }

    private byte[] body(Message message) {
        var root = json.createObjectNode();
        root.put("number", message.recipientE164().replace("+", ""));
        root.put("text", message.text());
        // The summary link is ours; a preview would only fetch the login page.
        root.put("linkPreview", false);
        return json.writeValueAsBytes(root);
    }

    private JsonNode read(byte[] body) {
        try {
            return body == null || body.length == 0 ? json.missingNode() : json.readTree(body);
        } catch (JacksonException invalid) {
            return json.missingNode();
        }
    }
}
