package com.lrj.recon.core;

import com.lrj.recon.core.domain.model.CurrencyMismatchException;
import com.lrj.recon.core.domain.model.Money;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @Test
    void rejects_non_iso_currency() {
        assertThatThrownBy(() -> Money.of("US", 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.of(null, 100))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void adds_same_currency() {
        assertThat(Money.of("USD", 100).add(Money.of("USD", 250)).amountMinor())
                .isEqualTo(350L);
    }

    @Test
    void add_across_currencies_throws() {
        assertThatThrownBy(() -> Money.of("USD", 100).add(Money.of("EUR", 100)))
                .isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void compare_across_currencies_throws() {
        assertThatThrownBy(() -> Money.of("USD", 100).compareSameCurrency(Money.of("EUR", 100)))
                .isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void compare_same_currency_orders_by_amount() {
        assertThat(Money.of("USD", 100).compareSameCurrency(Money.of("USD", 200))).isNegative();
        assertThat(Money.of("USD", 200).compareSameCurrency(Money.of("USD", 200))).isZero();
    }

    @Test
    void add_overflow_fails_fast() {
        assertThatThrownBy(() -> Money.of("USD", Long.MAX_VALUE).add(Money.of("USD", 1)))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void money_has_no_double_field() {
        for (Field f : Money.class.getDeclaredFields()) {
            assertThat(f.getType()).isNotIn(double.class, Double.class);
        }
    }
}
