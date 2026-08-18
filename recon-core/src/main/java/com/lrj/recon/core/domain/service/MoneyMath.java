package com.lrj.recon.core.domain.service;

/**
 * 金额整数分运算 (signed long)。全链路溢出 <b>fail-fast</b> —— 用 {@link Math#addExact(long, long)},
 * 溢出抛 {@link ArithmeticException} 而非静默回绕 (ADR-5; bigint ≈ 92 万亿元封顶, 千万级安全)。
 */
public final class MoneyMath {

    private MoneyMath() {
    }

    /** 相加, 溢出 fail-fast。 */
    public static long addExact(long a, long b) {
        return Math.addExact(a, b);
    }

    /** 相减, 溢出 fail-fast。 */
    public static long subtractExact(long a, long b) {
        return Math.subtractExact(a, b);
    }

    /** 带符号最小货币单位求和; 累加溢出即 fail-fast。 */
    public static long sumSignedMinor(Iterable<Long> values) {
        long acc = 0L;
        for (Long v : values) {
            if (v == null) {
                throw new IllegalArgumentException("signed amount must not be null");
            }
            acc = Math.addExact(acc, v);
        }
        return acc;
    }
}
