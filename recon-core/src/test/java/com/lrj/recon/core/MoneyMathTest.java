package com.lrj.recon.core;

import com.lrj.recon.core.domain.service.MoneyMath;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyMathTest {

    @Test
    void add_and_subtract_exact() {
        assertThat(MoneyMath.addExact(100L, -30L)).isEqualTo(70L);
        assertThat(MoneyMath.subtractExact(100L, 250L)).isEqualTo(-150L);
    }

    @Test
    void sum_signed_handles_mixed_signs() {
        assertThat(MoneyMath.sumSignedMinor(List.of(100L, -30L, -70L))).isEqualTo(0L);
        assertThat(MoneyMath.sumSignedMinor(List.of(100L, -150L))).isEqualTo(-50L);
    }

    @Test
    void add_overflow_fails_fast() {
        assertThatThrownBy(() -> MoneyMath.addExact(Long.MAX_VALUE, 1L))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void sum_overflow_fails_fast() {
        assertThatThrownBy(() -> MoneyMath.sumSignedMinor(List.of(Long.MAX_VALUE, 1L)))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void sum_rejects_null_element() {
        List<Long> withNull = new java.util.ArrayList<>();
        withNull.add(1L);
        withNull.add(null);
        assertThatThrownBy(() -> MoneyMath.sumSignedMinor(withNull))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
