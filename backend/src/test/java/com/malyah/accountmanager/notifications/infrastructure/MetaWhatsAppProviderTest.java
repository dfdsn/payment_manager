package com.malyah.accountmanager.notifications.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.malyah.accountmanager.notifications.application.port.WhatsAppSender.Kind;
import com.malyah.accountmanager.notifications.application.port.WhatsAppSender.Message;
import com.malyah.accountmanager.notifications.application.port.WhatsAppSender.Outcome;
import com.malyah.accountmanager.notifications.application.port.WhatsAppSender.SendResult;

/** H08.4 adapter rules without network: configuration, availability and the classification of answers. */
class MetaWhatsAppProviderTest {
    static MetaWhatsAppProperties props(boolean enabled, String version, String testTemplate) {
        return new MetaWhatsAppProperties(enabled, " https://graph.facebook.com/ ", version, "123456789012345",
                "token", "secret", "verify", "resumo_contas", "pt_BR", testTemplate, "pt_BR", Duration.ofSeconds(1),
                Duration.ofSeconds(1));
    }

    @Test
    void availabilityNeedsEverySettingAndNeverPrintsSecrets() {
        assertThat(new MetaWhatsAppProvider(MetaWhatsAppProperties.disabled()).availability().code())
                .isEqualTo(MetaWhatsAppProvider.DISABLED);
        var incomplete = props(true, "", "");
        assertThat(new MetaWhatsAppProvider(incomplete).availability().code())
                .isEqualTo(MetaWhatsAppProvider.NOT_CONFIGURED);
        assertThat(incomplete.missing()).containsExactly("META_WHATSAPP_API_VERSION");
        assertThat(MetaWhatsAppProperties.disabled().missing()).contains("META_WHATSAPP_TOKEN",
                "META_WHATSAPP_APP_SECRET", "META_WHATSAPP_VERIFY_TOKEN", "META_WHATSAPP_SUMMARY_TEMPLATE",
                "META_WHATSAPP_PHONE_NUMBER_ID");
        var ready = new MetaWhatsAppProvider(props(true, "v23.0", ""));
        assertThat(ready.availability().available()).isTrue();
        assertThat(ready.testTemplateConfigured()).isFalse();
        assertThat(new MetaWhatsAppProvider(props(true, "v23.0", "hello_world")).testTemplateConfigured()).isTrue();
        assertThat(props(true, "v23.0", "").messagesUri().toString())
                .isEqualTo("https://graph.facebook.com/v23.0/123456789012345/messages");
        assertThat(props(true, "v23.0", "").toString()).doesNotContain("token", "secret", "verify");
        assertThat(ready.send(new Message(Kind.TEST, "+5511987654321", List.of())).outcome())
                .isEqualTo(Outcome.UNAVAILABLE);
        assertThat(new MetaWhatsAppProvider(incomplete).send(new Message(Kind.SUMMARY, "+5511987654321",
                List.of())).outcome()).isEqualTo(Outcome.UNAVAILABLE);
    }

    @Test
    void productionRequiresHttps() {
        var http = new MetaWhatsAppProperties(true, "http://127.0.0.1:1", "v23.0", "123456789012345", "t", "s",
                "v", "resumo_contas", "pt_BR", "", "pt_BR", Duration.ofSeconds(1), Duration.ofSeconds(1));
        assertThatThrownBy(() -> http.requireSecureIn(true)).isInstanceOf(IllegalStateException.class);
        http.requireSecureIn(false);
        props(true, "v23.0", "").requireSecureIn(true);
        MetaWhatsAppProperties.disabled().requireSecureIn(true);
    }

    @Test
    void answersAreClassifiedConservatively() {
        var provider = new MetaWhatsAppProvider(props(true, "v23.0", ""));
        assertThat(provider.classify(200, bytes("{\"messages\":[{\"id\":\"wamid.X\"}]}")))
                .isEqualTo(SendResult.accepted("wamid.X"));
        assertThat(provider.classify(201, bytes("{\"messages\":[{\"id\":\" \"}]}")).outcome())
                .isEqualTo(Outcome.UNCERTAIN);
        assertThat(provider.classify(200, bytes("{\"messages\":[{\"id\":\"" + "x".repeat(129) + "\"}]}")).outcome())
                .isEqualTo(Outcome.UNCERTAIN);
        assertThat(provider.classify(200, bytes("{\"messages\":[{\"id\":7}]}")).outcome()).isEqualTo(Outcome.UNCERTAIN);
        assertThat(provider.classify(200, bytes("não é json")).outcome()).isEqualTo(Outcome.UNCERTAIN);
        assertThat(provider.classify(200, new byte[0]).outcome()).isEqualTo(Outcome.UNCERTAIN);
        assertThat(provider.classify(200, null).outcome()).isEqualTo(Outcome.UNCERTAIN);
        assertThat(provider.classify(400, bytes("{\"error\":{\"code\":132001,\"message\":\"x\"}}")))
                .isEqualTo(SendResult.of(Outcome.REJECTED, "132001"));
        assertThat(provider.classify(401, bytes("{\"error\":{\"code\":190}}")))
                .isEqualTo(SendResult.of(Outcome.REJECTED, "190"));
        assertThat(provider.classify(400, bytes("{\"error\":{\"code\":131026}}")))
                .isEqualTo(SendResult.of(Outcome.RECIPIENT_INVALID, "131026"));
        assertThat(provider.classify(400, bytes("{\"error\":{\"code\":131030}}")).outcome())
                .isEqualTo(Outcome.RECIPIENT_INVALID);
        assertThat(provider.classify(400, bytes("{\"error\":{\"code\":131048}}")))
                .isEqualTo(SendResult.of(Outcome.UNAVAILABLE, "131048"));
        assertThat(provider.classify(429, bytes(""))).isEqualTo(SendResult.of(Outcome.UNAVAILABLE, "429"));
        assertThat(provider.classify(404, bytes("<html>"))).isEqualTo(SendResult.of(Outcome.REJECTED, "404"));
        assertThat(provider.classify(400, bytes("{\"error\":{\"code\":\"131026\"}}")))
                .isEqualTo(SendResult.of(Outcome.REJECTED, "400"));
        assertThat(provider.classify(500, bytes("{\"error\":{\"code\":1}}")))
                .isEqualTo(SendResult.of(Outcome.UNCERTAIN, "1"));
        assertThat(provider.classify(503, bytes(""))).isEqualTo(SendResult.of(Outcome.UNCERTAIN, "503"));
        assertThat(provider.classify(302, bytes(""))).isEqualTo(SendResult.of(Outcome.UNCERTAIN, "302"));
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
