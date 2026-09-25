package com.malyah.accountmanager.identity.infrastructure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import com.malyah.accountmanager.identity.application.PendingInvitationEmail;
import com.malyah.accountmanager.identity.application.port.InvitationEmailSender;

final class SmtpInvitationEmailSender implements InvitationEmailSender {

    private final JavaMailSender mailSender;
    private final String publicBaseUrl;
    private final String from;

    SmtpInvitationEmailSender(
            JavaMailSender mailSender,
            @Value("${app.public-base-url}") String publicBaseUrl,
            @Value("${app.mail.from}") String from) {
        this.mailSender = mailSender;
        this.publicBaseUrl = publicBaseUrl.replaceAll("/+$", "");
        this.from = from;
    }

    @Override
    public void send(PendingInvitationEmail email) {
        var message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(email.recipient());
        message.setSubject("Convite para compartilhar despesas no account_Manager");
        message.setText("""
                Você foi convidado para participar do espaço "%s" no account_Manager.

                Ao aceitar, você terá acesso a todo o histórico de despesas desse espaço. O convite vale por sete dias e pode ser usado uma única vez.

                %s/aceitar-convite?token=%s

                Se você não esperava este convite, ignore esta mensagem.
                """.formatted(email.spaceName(), publicBaseUrl, email.rawToken()));
        mailSender.send(message);
    }
}
