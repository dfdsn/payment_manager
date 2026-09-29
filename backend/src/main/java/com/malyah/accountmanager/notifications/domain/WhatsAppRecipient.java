package com.malyah.accountmanager.notifications.domain;

import java.util.Set;

/**
 * H08.1: the administrator's personal WhatsApp number, a Brazilian mobile number normalized to E.164
 * ({@code +55}, a DDD in use and nine digits starting with 9). Punctuation and an optional {@code +55}/{@code 55}
 * prefix are accepted on input. {@link #masked()} is the only form written to audit events.
 */
public record WhatsAppRecipient(String e164) {
    public static final String INVALID = "WHATSAPP_RECIPIENT_INVALID";
    private static final String MESSAGE =
            "Informe um celular brasileiro com DDD, por exemplo (11) 98765-4321.";
    /** DDDs in use in Brazil (Anatel). */
    private static final Set<Integer> AREA_CODES = Set.of(
            11, 12, 13, 14, 15, 16, 17, 18, 19, 21, 22, 24, 27, 28,
            31, 32, 33, 34, 35, 37, 38, 41, 42, 43, 44, 45, 46, 47, 48, 49,
            51, 53, 54, 55, 61, 62, 63, 64, 65, 66, 67, 68, 69,
            71, 73, 74, 75, 77, 79, 81, 82, 83, 84, 85, 86, 87, 88, 89,
            91, 92, 93, 94, 95, 96, 97, 98, 99);

    public WhatsAppRecipient {
        if (e164 == null || !e164.matches("\\+55[0-9]{11}") || !valid(e164.substring(3)))
            throw new NotificationValidationException(INVALID, "phone", MESSAGE);
    }

    public static WhatsAppRecipient parse(String raw) {
        if (raw == null || raw.isBlank() || raw.length() > 32 || !raw.matches("[+0-9 ().-]+"))
            throw new NotificationValidationException(INVALID, "phone", MESSAGE);
        var trimmed = raw.strip();
        var digits = trimmed.replaceAll("[^0-9]", "");
        if (trimmed.startsWith("+")) {
            if (!digits.startsWith("55")) throw new NotificationValidationException(INVALID, "phone", MESSAGE);
            digits = digits.substring(2);
        } else if (digits.length() == 13 && digits.startsWith("55")) {
            digits = digits.substring(2);
        }
        if (digits.length() != 11) throw new NotificationValidationException(INVALID, "phone", MESSAGE);
        return new WhatsAppRecipient("+55" + digits);
    }

    private static boolean valid(String national) {
        return AREA_CODES.contains(Integer.parseInt(national.substring(0, 2))) && national.charAt(2) == '9';
    }

    /** {@code +55 ** *****-4321}: enough to recognize the number without disclosing it. */
    public String masked() {
        return "+55 ** *****-" + lastDigits();
    }

    public String lastDigits() {
        return e164.substring(e164.length() - 4);
    }

    /** {@code +55 11 98765-4321}, for the administrator who owns the number. */
    public String formatted() {
        return "+55 " + e164.substring(3, 5) + " " + e164.substring(5, 10) + "-" + e164.substring(10);
    }
}
