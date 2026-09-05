package com.lrj.recon.core.domain.model;

import java.util.Objects;

/**
 * Run 业务唯一键: 租户 + 场景 + 账期 + 序号。
 *
 * <p>本 VO 承载四元组领域身份。现有序号仍按场景+账期全局分配，数据库的 legacy 唯一键
 * {@code uk_run(scenario_code, accounting_period, sequence_no)} 比四元组约束更强，同样能挡住并发重复 Run。
 * 账期为日账期 {@code YYYY-MM-DD} (A5)。
 */
public record RunKey(String tenantId, String scenarioCode, String accountingPeriod, int sequenceNo) {

    public static final String LEGACY_TENANT = "legacy";

    public RunKey {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(scenarioCode, "scenarioCode");
        Objects.requireNonNull(accountingPeriod, "accountingPeriod");
    }

    public static RunKey of(String tenantId, String scenarioCode, String accountingPeriod, int sequenceNo) {
        return new RunKey(tenantId, scenarioCode, accountingPeriod, sequenceNo);
    }

    /** 仅供历史调用方平滑迁移；新权益对账必须显式传入 tenantId。 */
    public static RunKey of(String scenarioCode, String accountingPeriod, int sequenceNo) {
        return of(LEGACY_TENANT, scenarioCode, accountingPeriod, sequenceNo);
    }
}
