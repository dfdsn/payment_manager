package com.malyah.accountmanager.notifications.domain;

import java.util.Arrays;
import java.util.Optional;

/**
 * H08.3 (RF-ALT-18/19/20): the fixed catalog of WhatsApp failures shown to the administrator in the application.
 * Only the code is stored; the message comes from here, so no provider response, token or phone number ever
 * reaches a notification. {@link #PROVIDER_UNAVAILABLE} is the one real reason before H08.4; the others are the
 * contract the Meta integration reports through.
 */
public enum WhatsAppFailureReason {
    PROVIDER_UNAVAILABLE("O envio pelo WhatsApp não está disponível no momento. O resumo continua disponível aqui no "
            + "aplicativo."),
    PROVIDER_REJECTED("A Meta recusou a mensagem deste resumo. Confira o modelo aprovado e o número remetente."),
    DELIVERY_FAILED("A Meta informou que a mensagem deste resumo não foi entregue."),
    RECIPIENT_INVALID("O número configurado não pôde receber a mensagem. Confira o número em Lembretes."),
    RESULT_UNCERTAIN("Não foi possível confirmar se a Meta recebeu a mensagem. Nada foi reenviado automaticamente.");

    private final String message;

    WhatsAppFailureReason(String message) {
        this.message = message;
    }

    public String message() {
        return message;
    }

    /** A stored code back to the catalog; unknown codes are not shown as text. */
    public static Optional<WhatsAppFailureReason> fromCode(String code) {
        return Arrays.stream(values()).filter(reason -> reason.name().equals(code)).findFirst();
    }
}
