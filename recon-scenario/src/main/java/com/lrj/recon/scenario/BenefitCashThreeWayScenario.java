package com.lrj.recon.scenario;

/** Cash benefit reconciliation reuses the proven monetary two-segment spine with isolated ODS tables. */
public final class BenefitCashThreeWayScenario {
    public static final String SCENARIO_CODE = "BENEFIT_CASH_3WAY";
    private BenefitCashThreeWayScenario() {}

    public static MarketingThreeWayScenario create() {
        return MarketingThreeWayScenario.of(new MarketingThreeWayScenario.Config(
                "recon_ods_cash_expected", "recon_ods_cash_accounting", "recon_ods_cash_channel",
                null, null));
    }
}
