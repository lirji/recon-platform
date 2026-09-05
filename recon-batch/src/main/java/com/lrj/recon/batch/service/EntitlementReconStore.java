package com.lrj.recon.batch.service;

import com.lrj.recon.entitlement.model.EntitlementDiscrepancy;
import com.lrj.recon.entitlement.model.EntitlementMatchGroup;

import java.time.Instant;
import java.util.List;

/**
 * 非金额权益对账的持久化端口。
 *
 * <p>批作业只负责编排和判差，租户/窗口谓词、分页 SQL 与差异投影都封装在适配器中，
 * 避免作业层绕过端口直接访问数据库。
 */
public interface EntitlementReconStore {

    /**
     * 按 issueId 做 keyset 分页读取一个租户、一个时间窗口内的三方事实组。
     */
    List<ReconCase> nextPage(String tenantId, Instant windowFrom, Instant windowTo,
                             String afterIssueId, int limit);

    /**
     * 把分类结果幂等投影到公共差异表；金额列只为兼容旧表非空约束写零，真实口径由 QUANTITY 标识。
     */
    void insertDiscrepancies(String runId, String scenarioCode, ReconCase reconCase,
                             EntitlementDiscrepancy discrepancy, Instant occurredAt);

    /** 一组待判差事实及跨系统关联字段。 */
    record ReconCase(
            EntitlementMatchGroup group,
            String expectedSourceSystem,
            String marketingSourceRequestId,
            String benefitOrderNo,
            String leftRawRef,
            String rightRawRef) {
    }
}
