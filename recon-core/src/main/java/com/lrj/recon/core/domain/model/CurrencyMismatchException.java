package com.lrj.recon.core.domain.model;

/**
 * 跨币种直接算术/比较被拒绝时抛出。
 * 设计红线: 跨币种不换算、不可直接比 —— 币种不一致应短路为 CURRENCY_MISMATCH 差异, 而非静默相加。
 */
public class CurrencyMismatchException extends RuntimeException {

    private final String leftCurrency;
    private final String rightCurrency;

    public CurrencyMismatchException(String leftCurrency, String rightCurrency) {
        super("cannot combine or compare across currencies: " + leftCurrency + " vs " + rightCurrency);
        this.leftCurrency = leftCurrency;
        this.rightCurrency = rightCurrency;
    }

    public String leftCurrency() {
        return leftCurrency;
    }

    public String rightCurrency() {
        return rightCurrency;
    }
}
