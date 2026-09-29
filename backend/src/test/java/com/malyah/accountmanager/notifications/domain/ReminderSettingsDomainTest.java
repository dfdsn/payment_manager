package com.malyah.accountmanager.notifications.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalTime;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

/** H08.1 matrix C3, C4, C10 and the channel states of C1, C5–C9. */
class ReminderSettingsDomainTest {

    @ParameterizedTest
    @CsvSource(delimiter = ';', value = {
            "(11) 98765-4321;+5511987654321",
            "11987654321;+5511987654321",
            "+55 21 99876-5432;+5521998765432",
            "5521998765432;+5521998765432",
            " +55 (99) 9.1234-5678 ;+5599912345678",
            "(47) 91234-0000;+5547912340000"})
    void normalizesBrazilianMobileNumbers(String raw, String e164) {
        var recipient = WhatsAppRecipient.parse(raw);
        assertThat(recipient.e164()).isEqualTo(e164);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"1234", "+1 555 123 4567", "(20) 98765-4321", "(11) 3876-5432", "(11) 88765-4321",
            "abc", "   ", "+55 11 98765-43210", "055 11 98765-4321", "(10) 98765-4321", "551198765432",
            "+44 11 98765-4321", "11 98765 4321 ramal", "+55 11 98765-4321 +55 11 98765-4321 +55"})
    void rejectsNumbersOutsideTheContract(String raw) {
        assertThatThrownBy(() -> WhatsAppRecipient.parse(raw))
                .isInstanceOfSatisfying(NotificationValidationException.class, error -> {
                    assertThat(error.code()).isEqualTo("WHATSAPP_RECIPIENT_INVALID");
                    assertThat(error.field()).isEqualTo("phone");
                });
    }

    @Test
    void storedFormIsValidatedAndDisplayedWithoutDisclosingTheNumber() {
        var recipient = new WhatsAppRecipient("+5511987654321");
        assertThat(recipient.masked()).isEqualTo("+55 ** *****-4321").doesNotContain("98765");
        assertThat(recipient.lastDigits()).isEqualTo("4321");
        assertThat(recipient.formatted()).isEqualTo("+55 11 98765-4321");
        assertThatThrownBy(() -> new WhatsAppRecipient("+5511887654321"))
                .isInstanceOf(NotificationValidationException.class);
        assertThatThrownBy(() -> new WhatsAppRecipient(null)).isInstanceOf(NotificationValidationException.class);
        assertThatThrownBy(() -> new WhatsAppRecipient("+551198765432")).isInstanceOf(NotificationValidationException.class);
    }

    @Test
    void scheduleDefaultsAndOrder() {
        assertThat(ReminderSchedule.DEFAULT.text()).isEqualTo("09:00/18:00");
        assertThat(ReminderSchedule.DEFAULT.at(ReminderSlot.FIRST)).isEqualTo(LocalTime.of(9, 0));
        assertThat(ReminderSchedule.DEFAULT.at(ReminderSlot.SECOND)).isEqualTo(LocalTime.of(18, 0));
        var custom = ReminderSchedule.parse("08:30", "20:00");
        assertThat(custom).isEqualTo(new ReminderSchedule(LocalTime.of(8, 30), LocalTime.of(20, 0)));
        assertThat(ReminderSchedule.parse("00:00", "00:01").text()).isEqualTo("00:00/00:01");
        assertThat(ReminderSchedule.parse("23:58", "23:59").text()).isEqualTo("23:58/23:59");
    }

    @ParameterizedTest
    @CsvSource({"18:00,09:00", "09:00,09:00", "12:01,12:00"})
    void secondTimeMustBeLater(String first, String second) {
        assertThatThrownBy(() -> ReminderSchedule.parse(first, second))
                .isInstanceOfSatisfying(NotificationValidationException.class, error -> {
                    assertThat(error.code()).isEqualTo("REMINDER_SCHEDULE_INVALID");
                    assertThat(error.field()).isEqualTo("secondTime");
                });
    }

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {"25:00,18:00,firstTime", "9h,18:00,firstTime", "null,18:00,firstTime",
            "09:00,,secondTime", "09:00,18:60,secondTime", "9:00,18:00,firstTime", "09:00,24:00,secondTime"})
    void timesMustUseTheHourMinuteFormat(String first, String second, String field) {
        assertThatThrownBy(() -> ReminderSchedule.parse(first, second))
                .isInstanceOfSatisfying(NotificationValidationException.class, error -> {
                    assertThat(error.code()).isEqualTo("REMINDER_SCHEDULE_INVALID_FORMAT");
                    assertThat(error.field()).isEqualTo(field);
                });
    }

    @Test
    void scheduleRejectsMissingOrSecondsPrecision() {
        assertThatThrownBy(() -> new ReminderSchedule(null, LocalTime.NOON))
                .isInstanceOf(NotificationValidationException.class);
        assertThatThrownBy(() -> new ReminderSchedule(LocalTime.NOON, null))
                .isInstanceOf(NotificationValidationException.class);
        assertThatThrownBy(() -> new ReminderSchedule(LocalTime.of(9, 0, 1), LocalTime.NOON))
                .hasMessageContaining("minutos");
        assertThatThrownBy(() -> new ReminderSchedule(LocalTime.of(9, 0), LocalTime.of(12, 0, 0, 5)))
                .hasMessageContaining("minutos");
    }

    @ParameterizedTest
    @CsvSource({
            "false,false,false,false,RECIPIENT_REQUIRED",
            "false,true,true,true,RECIPIENT_REQUIRED",
            "true,false,true,true,CONSENT_REQUIRED",
            "true,true,false,true,DISABLED",
            "true,true,true,false,PROVIDER_UNAVAILABLE",
            "true,true,true,true,READY"})
    void channelStateKeepsConfigurationApartFromProvider(boolean recipient, boolean consent, boolean enabled,
            boolean provider, WhatsAppChannelState expected) {
        assertThat(WhatsAppChannelState.of(recipient, consent, enabled, provider)).isEqualTo(expected);
    }
}
