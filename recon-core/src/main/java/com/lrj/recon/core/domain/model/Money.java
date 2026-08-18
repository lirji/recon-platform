package com.lrj.recon.core.domain.model;

import com.lrj.recon.core.domain.service.MoneyMath;

/**
 * 金额值对象: 币种 + 最小货币单位 (分) 的带符号 long。
 *
 * <p>设计红线 (ADR-5):
 * <ul>
 *   <li>金额全链路 {@code long}(分, signed), <b>禁 double/Double</b> —— 本类无任何 double 构造/字段;</li>
 *   <li>同币种相加走 {@link MoneyMath#addExact(long, long)}, 溢出 fail-fast;</li>
 *   <li>跨币种不换算、不可直接比 —— {@link #add(Money)} / {@link #compareSameCurrency(Money)}
 *       遇币种不一致抛 {@link CurrencyMismatchException}。</li>
 * </ul>
 * 币种以 ISO-4217 三字母码表示 (校验长度=3)。
 */
public record Money(String currency, long amountMinor) {

    public Money {
        if (currency == null || currency.length() != 3) {
            throw new IllegalArgumentException("currency must be a 3-letter ISO-4217 code, got: " + currency);
        }
    }

    public static Money of(String currency, long amountMinor) {
        return new Money(currency, amountMinor);
    }

    public static Money zero(String currency) {
        return new Money(currency, 0L);
    }

    /** 同币种相加 (溢出 fail-fast); 跨币种抛 {@link CurrencyMismatchException}。 */
    public Money add(Money other) {
        requireSameCurrency(other);
        return new Money(currency, MoneyMath.addExact(amountMinor, other.amountMinor));
    }

    /** 取负 (溢出 fail-fast, Long.MIN_VALUE 取负会溢出)。 */
    public Money negate() {
        return new Money(currency, Math.negateExact(amountMinor));
    }

    /** 同币种比较; 跨币种抛 {@link CurrencyMismatchException} (禁跨币直接比)。 */
    public int compareSameCurrency(Money other) {
        requireSameCurrency(other);
        return Long.compare(amountMinor, other.amountMinor);
    }

    public boolean isSameCurrency(Money other) {
        return other != null && currency.equals(other.currency);
    }

    private void requireSameCurrency(Money other) {
        if (other == null || !currency.equals(other.currency)) {
            throw new CurrencyMismatchException(currency, other == null ? null : other.currency);
        }
    }
}
