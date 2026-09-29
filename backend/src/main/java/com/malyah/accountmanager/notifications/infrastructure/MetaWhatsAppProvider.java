package com.malyah.accountmanager.notifications.infrastructure;

import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.UnresolvedAddressException;
import java.util.Set;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.malyah.accountmanager.notifications.application.port.WhatsAppProviderStatus;
import com.malyah.accountmanager.notifications.application.port.WhatsAppSender;

/**
 * H08.4: the Meta WhatsApp Cloud API adapter ({@code POST /<version>/<phone-number-id>/messages} with a template).
 * The answer is classified conservatively: only a 2xx carrying a message id is an acceptance (never a delivery);
 * a request that certainly did not leave (connection refused, unknown host, connect timeout) or was throttled is
 * unavailable; a refusal is rejected; everything else, including a timeout after sending and a 5xx, is uncertain.
 * Neither the token, the request body nor the response body is ever logged or returned.
 */
final class MetaWhatsAppProvider implements WhatsAppProviderStatus, WhatsAppSender {
    static final String DISABLED = "PROVIDER_DISABLED";
    static final String NOT_CONFIGURED = "PROVIDER_NOT_CONFIGURED";
    static final String READY = "PROVIDER_READY";
    /** Error codes that point at the recipient (to confirm against the current Meta reference, P03). */
    static final Set<Long> RECIPIENT_CODES = Set.of(131026L, 131030L);
    /** Rate and capacity limits: the request was not accepted and may be tried later (H08.5). */
    static final Set<Long> THROTTLE_CODES = Set.of(130429L, 131048L, 131056L, 80007L);
    private static final int MAX_ID_LENGTH = 128;

    private final MetaWhatsAppProperties properties;
    private final HttpClient client;
    private final JsonMapper json = JsonMapper.builder().build();

    MetaWhatsAppProvider(MetaWhatsAppProperties properties) {
        this.properties = properties;
        this.client = HttpClient.newBuilder().connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override
    public Availability availability() {
        if (!properties.enabled())
            return new Availability(false, DISABLED, "O envio real pela Meta está desligado na configuração do "
                    + "servidor. A configuração fica salva e nenhum resumo é enviado pelo WhatsApp.");
        if (!properties.configured())
            return new Availability(false, NOT_CONFIGURED, "O envio pela Meta está ligado, mas a configuração do "
                    + "servidor está incompleta. Nenhum resumo é enviado pelo WhatsApp até ela ser corrigida.");
        return new Availability(true, READY, "Envio pela Meta configurado. Aceite da Meta não é entrega: a "
                + "confirmação chega depois pelo webhook.");
    }

    @Override
    public boolean testTemplateConfigured() {
        return properties.testTemplateConfigured();
    }

    @Override
    public SendResult send(Message message) {
        if (!properties.configured()) return SendResult.of(Outcome.UNAVAILABLE, null);
        var summary = message.kind() == Kind.SUMMARY;
        if (!summary && !properties.testTemplateConfigured()) return SendResult.of(Outcome.UNAVAILABLE, null);
        var request = HttpRequest.newBuilder(properties.messagesUri())
                .timeout(properties.requestTimeout())
                .header("Authorization", "Bearer " + properties.accessToken())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body(message, summary)))
                .build();
        HttpResponse<byte[]> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (HttpConnectTimeoutException | ConnectException | UnknownHostException notSent) {
            return SendResult.of(Outcome.UNAVAILABLE, null);
        } catch (IOException | UnresolvedAddressException lostAfterSending) {
            // A timeout or a dropped connection does not prove Meta did not receive the request.
            return SendResult.of(Outcome.UNCERTAIN, null);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return SendResult.of(Outcome.UNCERTAIN, null);
        }
        return classify(response.statusCode(), response.body());
    }

    SendResult classify(int status, byte[] body) {
        if (status >= 200 && status < 300) {
            var id = read(body).path("messages").path(0).path("id");
            var value = id.isString() ? id.stringValue().strip() : "";
            return value.isEmpty() || value.length() > MAX_ID_LENGTH ? SendResult.of(Outcome.UNCERTAIN, null)
                    : SendResult.accepted(value);
        }
        var code = read(body).path("error").path("code");
        var errorCode = code.canConvertToLong() && code.isNumber() ? code.asLong() : null;
        var reported = errorCode == null ? String.valueOf(status) : String.valueOf(errorCode);
        if (status == 429 || (errorCode != null && THROTTLE_CODES.contains(errorCode)))
            return SendResult.of(Outcome.UNAVAILABLE, reported);
        if (status >= 400 && status < 500)
            return SendResult.of(errorCode != null && RECIPIENT_CODES.contains(errorCode) ? Outcome.RECIPIENT_INVALID
                    : Outcome.REJECTED, reported);
        return SendResult.of(Outcome.UNCERTAIN, reported);
    }

    private byte[] body(Message message, boolean summary) {
        var root = json.createObjectNode();
        root.put("messaging_product", "whatsapp");
        root.put("recipient_type", "individual");
        root.put("to", message.recipientE164().replace("+", ""));
        root.put("type", "template");
        var template = root.putObject("template");
        template.put("name", summary ? properties.summaryTemplate() : properties.testTemplate());
        template.putObject("language").put("code",
                summary ? properties.templateLanguage() : properties.testTemplateLanguage());
        if (!message.parameters().isEmpty()) {
            var parameters = template.putArray("components").addObject().put("type", "body").putArray("parameters");
            for (var parameter : message.parameters()) parameters.addObject().put("type", "text").put("text", parameter);
        }
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
