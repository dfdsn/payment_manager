package com.malyah.accountmanager.notifications.domain;

import java.util.Arrays;
import java.util.Optional;

/**
 * H08.5 (RF-ALT-19): why the WhatsApp channel was suspended, with what the administrator has to correct. Only a
 * permanent failure of the recipient or of the channel suspends it; a general unavailability of the provider never
 * does, and it never touches the number or the consent.
 */
public enum WhatsAppSuspensionReason {
    RECIPIENT_INVALID("A Meta informou que o número cadastrado não pode receber mensagens. Confira o número; se ele "
            + "estiver certo, reative o canal. Se mudou de número, salve o novo e autorize de novo."),
    PROVIDER_REJECTED("A Meta recusou as mensagens por um problema permanente do remetente, do modelo aprovado ou "
            + "das credenciais do servidor. Depois que a configuração do servidor for corrigida, reative o canal.");

    private final String guidance;

    WhatsAppSuspensionReason(String guidance) {
        this.guidance = guidance;
    }

    public String guidance() {
        return guidance;
    }

    public static Optional<WhatsAppSuspensionReason> fromCode(String code) {
        return Arrays.stream(values()).filter(reason -> reason.name().equals(code)).findFirst();
    }
}
