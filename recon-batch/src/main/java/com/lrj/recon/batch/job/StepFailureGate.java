package com.lrj.recon.batch.job;

/**
 * 故障注入接缝 (测试用): 让集成测试在 reportStep 起点可控地抛错一次, 以验证 JobRepository 断点续跑
 * (失败后同参重启只续未完成 Step)。生产装配为 no-op (见 BatchConfig 的默认 bean), 主流程零副作用。
 */
public interface StepFailureGate {

    /** 在 reportStep 计算前调用; 默认 no-op, 测试可覆盖为一次性抛错。 */
    void beforeReport(String runId);
}
