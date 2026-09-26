package com.malyah.accountmanager.expenses.domain;
import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
class CategoryNameTest {
 @Test void trimsNormalizesAndValidates(){assertThat(new CategoryName("  Casa ").value()).isEqualTo("Casa");assertThat(new CategoryName("SAÚDE").normalized()).isEqualTo("saúde");assertThatThrownBy(()->new CategoryName(" ")).isInstanceOf(ExpenseValidationException.class);assertThatThrownBy(()->new CategoryName("x".repeat(61))).isInstanceOf(ExpenseValidationException.class);}
}
