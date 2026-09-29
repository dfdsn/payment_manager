package com.malyah.accountmanager.notifications.infrastructure;

import com.malyah.accountmanager.notifications.application.port.WhatsAppProviderStatus;

/**
 * The only provider status until the Meta Cloud API adapter exists (H08.4, P03). It is not a simulation of sending:
 * it states that nothing can be sent, so no configured channel is reported as able to deliver.
 */
final class UnavailableWhatsAppProvider implements WhatsAppProviderStatus {
    static final String CODE = "PROVIDER_NOT_IMPLEMENTED";
    static final String MESSAGE = "O envio real pela Meta ainda não está disponível nesta versão (H08.4). "
            + "A configuração fica salva e nenhum resumo é enviado pelo WhatsApp.";

    @Override
    public Availability availability() {
        return new Availability(false, CODE, MESSAGE);
    }
}
