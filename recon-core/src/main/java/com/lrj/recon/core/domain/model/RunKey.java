package com.lrj.recon.core.domain.model;

import java.util.Objects;

/**
 * Run 业务唯一键 (设计 §5 {@code uk_run}): 场景 + 账期 + 序号。
 *
 * <p>并发重复 Run 由 {@code uk_run UNIQUE(scenario_code, accounting_period, sequence_no)} 在库侧挡下;
 * 本 VO 只承载该三元组的领域身份。账期为日账期 {@code YYYY-MM-DD} (A5)。
 */
public record RunKey(String scenarioCode, String accountingPeriod, int sequenceNo) {

    public RunKey {
        Objects.requireNonNull(scenarioCode, "scenarioCode");
        Objects.requireNonNull(accountingPeriod, "accountingPeriod");
    }

    public static RunKey of(String scenarioCode, String accountingPeriod, int sequenceNo) {
        return new RunKey(scenarioCode, accountingPeriod, sequenceNo);
    }
}
