package com.malyah.accountmanager.notifications.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import com.malyah.accountmanager.notifications.application.port.WhatsAppSender.Kind;
import com.malyah.accountmanager.notifications.application.port.WhatsAppSender.Message;
import com.malyah.accountmanager.notifications.application.port.WhatsAppSender.Outcome;
import com.malyah.accountmanager.notifications.application.port.WhatsAppSender.SendResult;

/** T36 adapter rules: configuration, availability, the connection check and the classification of answers. */
class EvolutionWhatsAppProviderTest {
    static final String KEY = "evolution-key-not-real";
    static final String SECRET = "webhook-secret-not-real-1234";

    static EvolutionWhatsAppProperties props(boolean enabled, String baseUrl, String instance) {
        return new EvolutionWhatsAppProperties(enabled, baseUrl, KEY, instance, SECRET, Duration.ofSeconds(1),
                Duration.ofSeconds(1));
    }

    @Test
    void availabilityNeedsEverySettingAndNeverPrintsSecrets() {
        assertThat(new EvolutionWhatsAppProvider(EvolutionWhatsAppProperties.disabled()).availability().code())
                .isEqualTo(EvolutionWhatsAppProvider.DISABLED);
        assertThat(EvolutionWhatsAppProperties.disabled().missing()).containsExactly("EVOLUTION_API_BASE_URL",
                "EVOLUTION_API_KEY", "EVOLUTION_API_INSTANCE", "EVOLUTION_WEBHOOK_TOKEN");
        var incomplete = props(true, " http://evolution-api:8080/ ", "conta com espaço");
        assertThat(new EvolutionWhatsAppProvider(incomplete).availability().code())
                .isEqualTo(EvolutionWhatsAppProvider.NOT_CONFIGURED);
        assertThat(incomplete.missing()).containsExactly("EVOLUTION_API_INSTANCE");
        assertThat(new EvolutionWhatsAppProperties(true, "ftp://x", KEY, "contas", SECRET, Duration.ofSeconds(1),
                Duration.ofSeconds(1)).missing()).containsExactly("EVOLUTION_API_BASE_URL");
        var shortSecret = new EvolutionWhatsAppProperties(true, "https://evo.example.com", KEY, "contas",
                "x".repeat(15), Duration.ofSeconds(1), Duration.ofSeconds(1));
        assertThat(shortSecret.missing()).containsExactly("EVOLUTION_WEBHOOK_TOKEN");
        assertThat(shortSecret.webhookConfigured()).isFalse();
        var ready = props(true, " http://evolution-api:8080/ ", "contas.casa_1");
        assertThat(ready.missing()).isEmpty();
        assertThat(ready.webhookConfigured()).isTrue();
        assertThat(props(false, "http://evolution-api:8080", "contas").webhookConfigured()).isFalse();
        var provider = new EvolutionWhatsAppProvider(ready);
        assertThat(provider.availability().available()).isTrue();
        assertThat(provider.availability().code()).isEqualTo(EvolutionWhatsAppProvider.READY);
        assertThat(ready.sendTextUri().toString())
                .isEqualTo("http://evolution-api:8080/message/sendText/contas.casa_1");
        assertThat(ready.connectionStateUri().toString())
                .isEqualTo("http://evolution-api:8080/instance/connectionState/contas.casa_1");
        assertThat(ready.toString()).contains("contas.casa_1").doesNotContain(KEY, SECRET);
        assertThat(new EvolutionWhatsAppProvider(incomplete).send(new Message(Kind.SUMMARY, "+5511987654321", "x"))
                .outcome()).isEqualTo(Outcome.UNAVAILABLE);
    }

    @Test
    void productionRequiresHttpsOutsideTheServer() {
        assertThatThrownBy(() -> props(true, "http://evo.example.com", "contas").requireSecureIn(true))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("HTTPS");
        assertThatThrownBy(() -> props(true, "http://10.0.0.5:8080", "contas").requireSecureIn(true))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> props(true, "http://evo.local:8080", "contas").requireSecureIn(true))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> props(true, "not a url", "contas").requireSecureIn(true))
                .isInstanceOf(IllegalStateException.class);
        props(true, "http://evo.example.com", "contas").requireSecureIn(false);
        props(false, "http://evo.example.com", "contas").requireSecureIn(true);
        props(true, "https://evo.example.com", "contas").requireSecureIn(true);
        props(true, "http://evolution-api:8080", "contas").requireSecureIn(true);
        props(true, "http://localhost:8080", "contas").requireSecureIn(true);
        props(true, "http://127.0.0.1:8080", "contas").requireSecureIn(true);
        props(true, "http://[::1]:8080", "contas").requireSecureIn(true);
        EvolutionWhatsAppProperties.disabled().requireSecureIn(true);
    }

    @Test
    void sendAnswersAreClassifiedConservatively() {
        var provider = new EvolutionWhatsAppProvider(props(true, "http://evolution-api:8080", "contas"));
        assertThat(provider.classify(201, bytes("{\"key\":{\"id\":\"BAE5F1\",\"fromMe\":true}}")))
                .isEqualTo(SendResult.accepted("BAE5F1"));
        assertThat(provider.classify(200, bytes("{\"key\":{\"id\":\" BAE5F2 \"}}")))
                .isEqualTo(SendResult.accepted("BAE5F2"));
        assertThat(provider.classify(299, bytes("{\"key\":{\"id\":\"x\"}}")).outcome()).isEqualTo(Outcome.ACCEPTED);
        assertThat(provider.classify(201, bytes("{\"key\":{\"id\":\" \"}}")).outcome()).isEqualTo(Outcome.UNCERTAIN);
        assertThat(provider.classify(201, bytes("{\"key\":{\"id\":\"" + "x".repeat(129) + "\"}}")).outcome())
                .isEqualTo(Outcome.UNCERTAIN);
        assertThat(provider.classify(201, bytes("{\"key\":{\"id\":\"" + "x".repeat(128) + "\"}}")).outcome())
                .isEqualTo(Outcome.ACCEPTED);
        assertThat(provider.classify(201, bytes("{\"key\":{\"id\":7}}")).outcome()).isEqualTo(Outcome.UNCERTAIN);
        assertThat(provider.classify(201, bytes("{\"id\":\"x\"}")).outcome()).isEqualTo(Outcome.UNCERTAIN);
        assertThat(provider.classify(201, bytes("não é json")).outcome()).isEqualTo(Outcome.UNCERTAIN);
        assertThat(provider.classify(201, new byte[0]).outcome()).isEqualTo(Outcome.UNCERTAIN);
        assertThat(provider.classify(201, null).outcome()).isEqualTo(Outcome.UNCERTAIN);
        assertThat(provider.classify(400, bytes("""
                {"status":400,"error":"Bad Request","response":{"message":[{"jid":"5511987654321@s.whatsapp.net",
                 "exists":false,"number":"5511987654321"}]}}
                """))).isEqualTo(SendResult.of(Outcome.RECIPIENT_INVALID, EvolutionWhatsAppProvider.NOT_ON_WHATSAPP));
        assertThat(provider.classify(400, bytes("{\"response\":{\"message\":[{\"exists\":true}]}}")))
                .isEqualTo(SendResult.of(Outcome.REJECTED, "400"));
        assertThat(provider.classify(400, bytes("{\"response\":{\"message\":[{\"exists\":\"false\"}]}}")))
                .isEqualTo(SendResult.of(Outcome.REJECTED, "400"));
        assertThat(provider.classify(422, bytes("{\"response\":{\"message\":[{\"exists\":false}]}}")))
                .isEqualTo(SendResult.of(Outcome.REJECTED, "422"));
        assertThat(provider.classify(401, bytes("{\"status\":401,\"error\":\"Unauthorized\"}")))
                .isEqualTo(SendResult.of(Outcome.REJECTED, "401"));
        assertThat(provider.classify(404, bytes("<html>"))).isEqualTo(SendResult.of(Outcome.REJECTED, "404"));
        assertThat(provider.classify(499, bytes(""))).isEqualTo(SendResult.of(Outcome.REJECTED, "499"));
        assertThat(provider.classify(429, bytes(""))).isEqualTo(SendResult.of(Outcome.UNAVAILABLE, "429"));
        assertThat(provider.classify(500, bytes("{\"response\":{\"message\":[\"Connection Closed\"]}}")))
                .isEqualTo(SendResult.of(Outcome.UNCERTAIN, "500"));
        assertThat(provider.classify(503, bytes(""))).isEqualTo(SendResult.of(Outcome.UNCERTAIN, "503"));
        assertThat(provider.classify(302, bytes(""))).isEqualTo(SendResult.of(Outcome.UNCERTAIN, "302"));
        assertThat(provider.classify(199, bytes("{\"key\":{\"id\":\"x\"}}")).outcome()).isEqualTo(Outcome.UNCERTAIN);
    }

    /** H08.5: a throttled request carries the provider's wait; other forms of the header are ignored. */
    @Test
    void throttlingCarriesTheProviderWait() {
        var provider = new EvolutionWhatsAppProvider(props(true, "http://evolution-api:8080", "contas"));
        assertThat(provider.classify(429, bytes(""), " 120 "))
                .isEqualTo(new SendResult(Outcome.UNAVAILABLE, null, "429", Duration.ofSeconds(120)));
        assertThat(provider.classify(429, bytes(""), "3600").retryAfter()).isEqualTo(Duration.ofHours(1));
        assertThat(provider.classify(429, bytes(""), "99999").retryAfter()).isEqualTo(Duration.ofHours(1));
        assertThat(provider.classify(429, bytes(""), "Wed, 21 Oct 2026 07:28:00 GMT").retryAfter()).isNull();
        assertThat(provider.classify(429, bytes(""), "-5").retryAfter()).isNull();
        assertThat(provider.classify(400, bytes(""), "60").retryAfter()).isNull();
        assertThat(provider.classify(500, bytes(""), "60").retryAfter()).isNull();
        assertThat(EvolutionWhatsAppProvider.retryAfter(null)).isNull();
    }

    /** Nothing is sent while the instance is not connected; a refused key or unknown instance is permanent. */
    @Test
    void theConnectionStateIsClassifiedBeforeAnySend() {
        var provider = new EvolutionWhatsAppProvider(props(true, "http://evolution-api:8080", "contas"));
        assertThat(provider.classifyState(200, bytes("{\"instance\":{\"instanceName\":\"contas\",\"state\":\"open\"}}"),
                null)).isNull();
        assertThat(provider.classifyState(299, bytes("{\"instance\":{\"state\":\" OPEN \"}}"), null)).isNull();
        assertThat(provider.classifyState(200, bytes("{\"instance\":{\"state\":\"close\"}}"), null))
                .isEqualTo(SendResult.of(Outcome.UNAVAILABLE, "CONN_CLOSE"));
        assertThat(provider.classifyState(200, bytes("{\"instance\":{\"state\":\"connecting\"}}"), null))
                .isEqualTo(SendResult.of(Outcome.UNAVAILABLE, "CONN_CONNECTING"));
        assertThat(provider.classifyState(200, bytes("{\"instance\":{\"state\":\"abcdefghijkl\"}}"), null))
                .isEqualTo(SendResult.of(Outcome.UNAVAILABLE, EvolutionWhatsAppProvider.STATE_UNKNOWN));
        assertThat(provider.classifyState(200, bytes("{\"instance\":{\"state\":\"abcdefghijk\"}}"), null))
                .isEqualTo(SendResult.of(Outcome.UNAVAILABLE, "CONN_ABCDEFGHIJK"));
        assertThat(provider.classifyState(200, bytes("{\"instance\":{\"state\":1}}"), null))
                .isEqualTo(SendResult.of(Outcome.UNAVAILABLE, EvolutionWhatsAppProvider.STATE_UNKNOWN));
        assertThat(provider.classifyState(200, bytes("x"), null))
                .isEqualTo(SendResult.of(Outcome.UNAVAILABLE, EvolutionWhatsAppProvider.STATE_UNKNOWN));
        assertThat(provider.classifyState(199, bytes("{\"instance\":{\"state\":\"open\"}}"), null))
                .isEqualTo(SendResult.of(Outcome.UNAVAILABLE, "STATE_199"));
        assertThat(provider.classifyState(401, bytes(""), null)).isEqualTo(SendResult.of(Outcome.REJECTED, "STATE_401"));
        assertThat(provider.classifyState(403, bytes(""), null)).isEqualTo(SendResult.of(Outcome.REJECTED, "STATE_403"));
        assertThat(provider.classifyState(404, bytes(""), null)).isEqualTo(SendResult.of(Outcome.REJECTED, "STATE_404"));
        assertThat(provider.classifyState(400, bytes(""), null))
                .isEqualTo(SendResult.of(Outcome.UNAVAILABLE, "STATE_400"));
        assertThat(provider.classifyState(500, bytes(""), null))
                .isEqualTo(SendResult.of(Outcome.UNAVAILABLE, "STATE_500"));
        assertThat(provider.classifyState(429, bytes(""), "30"))
                .isEqualTo(new SendResult(Outcome.UNAVAILABLE, null, "STATE_429", Duration.ofSeconds(30)));
    }

    /** Against the SIMULATED Evolution API: headers, paths, body, and nothing sent while disconnected. */
    @Test
    void sendChecksTheInstanceAndPostsTheText() throws Exception {
        try (var evolution = new FakeEvolutionServer()) {
            var provider = new EvolutionWhatsAppProvider(props(true, evolution.baseUrl() + "/", "contas"));
            var result = provider.send(new Message(Kind.TEST, "+5511987654321", "Olá\nsegunda linha", "ref"));
            assertThat(result).isEqualTo(SendResult.accepted("FAKE-1"));
            assertThat(evolution.stateChecks()).singleElement().satisfies(check -> {
                assertThat(check.method()).isEqualTo("GET");
                assertThat(check.path()).isEqualTo("/instance/connectionState/contas");
                assertThat(check.apiKey()).isEqualTo(KEY);
            });
            var request = evolution.requests().getFirst();
            assertThat(request.method()).isEqualTo("POST");
            assertThat(request.path()).isEqualTo("/message/sendText/contas");
            assertThat(request.apiKey()).isEqualTo(KEY);
            var body = JsonMapper.builder().build().readTree(request.body());
            assertThat(body.path("number").asString()).isEqualTo("5511987654321");
            assertThat(body.path("text").asString()).isEqualTo("Olá\nsegunda linha");
            assertThat(body.path("linkPreview").asBoolean(true)).isFalse();
            assertThat(request.body()).doesNotContain("ref");

            evolution.state("close");
            assertThat(provider.send(new Message(Kind.SUMMARY, "+5511987654321", "x")))
                    .isEqualTo(SendResult.of(Outcome.UNAVAILABLE, "CONN_CLOSE"));
            evolution.state("open");
            evolution.enqueueState(401, "{\"status\":401}");
            assertThat(provider.send(new Message(Kind.SUMMARY, "+5511987654321", "x")))
                    .isEqualTo(SendResult.of(Outcome.REJECTED, "STATE_401"));
            evolution.enqueueDelayed(201, "{\"key\":{\"id\":\"LATE\"}}", 1500);
            assertThat(provider.send(new Message(Kind.SUMMARY, "+5511987654321", "x")))
                    .isEqualTo(SendResult.of(Outcome.UNCERTAIN, null));
            assertThat(evolution.requests()).hasSize(2);
        }
    }

    @Test
    void anUnreachableServerIsUnavailable() throws Exception {
        int closedPort;
        try (var socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        var provider = new EvolutionWhatsAppProvider(props(true, "http://127.0.0.1:" + closedPort, "contas"));
        assertThat(provider.send(new Message(Kind.SUMMARY, "+5511987654321", "x")))
                .isEqualTo(SendResult.of(Outcome.UNAVAILABLE, EvolutionWhatsAppProvider.STATE_UNKNOWN));
        assertThat(provider.connectionState())
                .isEqualTo(SendResult.of(Outcome.UNAVAILABLE, EvolutionWhatsAppProvider.STATE_UNKNOWN));
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
