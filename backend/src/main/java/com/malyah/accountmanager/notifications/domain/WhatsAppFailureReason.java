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
    PROVIDER_REJECTED("A Meta recusou a mensagem deste resumo e o WhatsApp foi suspenso. Peça a correção do modelo "
            + "aprovado ou do número remetente na configuração do servidor e depois reative o canal em Lembretes."),
    DELIVERY_FAILED("A Meta informou que a mensagem deste resumo não foi entregue."),
    RECIPIENT_INVALID("O número configurado não pôde receber a mensagem e o WhatsApp foi suspenso. Confira o número "
            + "em Lembretes e reative o canal (ou salve outro número e autorize de novo)."),
    RESULT_UNCERTAIN("Não foi possível confirmar se a Meta recebeu a mensagem. Nada foi reenviado automaticamente."),
    NOT_SENT_IN_WINDOW("O resumo não saiu pelo WhatsApp dentro do horário e não será enviado depois. Ele continua "
            + "disponível aqui no aplicativo."),
    /** H08.5: a delivery failure reported for the recipient; the channel was suspended. */
    RECIPIENT_UNREACHABLE("A Meta informou que o número configurado não pode receber mensagens e o WhatsApp foi "
            + "suspenso. Confira o número em Lembretes e reative o canal (ou salve outro número e autorize de novo).");

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
