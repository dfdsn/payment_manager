package com.malyah.accountmanager.recurrences.domain;

import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RecurrenceDefinitionTest {
    @Test void validatesDescriptionAmountAndDates() {
        assertThatThrownBy(()->definition(" ","10",LocalDate.of(2026,1,1),null)).isInstanceOf(RecurrenceValidationException.class);
        assertThatThrownBy(()->definition("Conta","0",LocalDate.of(2026,1,1),null)).isInstanceOf(RecurrenceValidationException.class);
        assertThatThrownBy(()->definition("Conta","1.001",LocalDate.of(2026,1,1),null)).isInstanceOf(RecurrenceValidationException.class);
        assertThatThrownBy(()->definition("Conta","100000000",LocalDate.of(2026,1,1),null)).isInstanceOf(RecurrenceValidationException.class);
        assertThatThrownBy(()->definition("Conta","10",LocalDate.of(2026,2,1),LocalDate.of(2026,1,1))).isInstanceOf(RecurrenceValidationException.class);
        assertThat(definition(" Conta ","10.50",LocalDate.of(2025,1,1),null).description()).isEqualTo("Conta");
    }
    private RecurrenceDefinition definition(String description,String amount,LocalDate first,LocalDate last) {
        return new RecurrenceDefinition(UUID.randomUUID(),UUID.randomUUID(),description,new BigDecimal(amount),
                RecurrenceValueType.FIXED,RecurrenceFrequency.MONTHLY,first,last,null,null,UUID.randomUUID(),Instant.EPOCH,0);
    }
}
