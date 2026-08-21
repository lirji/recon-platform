package com.lrj.recon.scenario.dsl;

import com.lrj.recon.scenario.BenefitCashThreeWayScenario;

import java.util.LinkedHashMap;

public final class BenefitCashThreeWayDefinition {
    private BenefitCashThreeWayDefinition() {}

    public static ScenarioDefinition seed() {
        ScenarioDefinition marketing = MarketingThreeWayDefinition.seed();
        var seg1 = marketing.segments().get(0);
        var seg2 = marketing.segments().get(1);
        return new ScenarioDefinition(BenefitCashThreeWayScenario.SCENARIO_CODE, java.util.List.of(
                replace(seg1, "recon_ods_cash_expected", "recon_ods_cash_accounting"),
                replace(seg2, "recon_ods_cash_accounting", "recon_ods_cash_channel")));
    }

    private static ScenarioDefinition.Segment replace(ScenarioDefinition.Segment segment,
                                                      String leftTable, String rightTable) {
        return new ScenarioDefinition.Segment(segment.id(), segment.leftRole(), segment.rightRole(),
                segment.spineRole(), segment.stageLabel(), segment.matchKeyField(), segment.groupKeyField(),
                source(segment.left(), leftTable), source(segment.right(), rightTable), segment.rule());
    }

    private static ScenarioDefinition.Source source(ScenarioDefinition.Source source, String table) {
        var params = new LinkedHashMap<>(source.params());
        params.put("table", table);
        params.put("tenantColumn", "tenant_id");
        params.put("bizTimeColumn", "biz_time");
        return new ScenarioDefinition.Source(source.sourceType(), params);
    }
}
