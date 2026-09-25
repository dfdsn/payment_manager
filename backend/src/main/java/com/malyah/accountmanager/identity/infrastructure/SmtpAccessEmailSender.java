package com.malyah.accountmanager.identity.infrastructure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import com.malyah.accountmanager.identity.application.PendingAccessEmail;
import com.malyah.accountmanager.identity.application.port.AccessEmailSender;
import com.malyah.accountmanager.identity.domain.AccessTokenPurpose;

final class SmtpAccessEmailSender implements AccessEmailSender {

    private final JavaMailSender mailSender;
    private final String publicBaseUrl;
    private final String from;

    SmtpAccessEmailSender(
            JavaMailSender mailSender,
            @Value("${app.public-base-url}") String publicBaseUrl,
            @Value("${app.mail.from}") String from) {
        this.mailSender = mailSender;
        this.publicBaseUrl = publicBaseUrl.replaceAll("/+$", "");
        this.from = from;
    }

    @Override
    public void send(PendingAccessEmail email) {
        var confirmation = email.purpose() == AccessTokenPurpose.CONFIRM_EMAIL;
        var path = confirmation ? "/confirmar-email" : "/redefinir-senha";
        var validity = confirmation ? "24 horas" : "30 minutos";
        var message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(email.recipient());
        message.setSubject(confirmation
                ? "Confirme seu email no account_Manager"
                : "Redefina sua senha no account_Manager");
        message.setText("""
                Use o link abaixo para continuar. Ele pode ser usado uma única vez e vale por %s.

                %s%s?token=%s

                Se você não solicitou esta ação, ignore esta mensagem.
                """.formatted(validity, publicBaseUrl, path, email.rawToken()));
        mailSender.send(message);
    }
}
