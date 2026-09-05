package com.lrj.recon.batch.service;

import java.time.Instant;
import java.util.List;

/**
 * 权益补救建议的管理台列表投影。不属于 recon-core 领域不变量,JDBC 实现留在 persistence。
 */
public interface RemediationSuggestionQuery {

    List<RemediationView> list(String tenantId, String status, int limit, int offset);

    long count(String tenantId, String status);

    record RemediationView(String tenantId, String suggestionId, String scenarioCode, String discrepancyRef,
                           String awardItemNo, String originalOperationNo, String action, String reason,
                           String status, String approvalRef, long version, Instant createdAt, Instant updatedAt) {
    }
}
