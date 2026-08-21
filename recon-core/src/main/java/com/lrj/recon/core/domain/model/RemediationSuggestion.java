package com.lrj.recon.core.domain.model;

import java.util.Objects;

public final class RemediationSuggestion {
    private final String tenantId;
    private final String suggestionId;
    private final String scenarioCode;
    private final String discrepancyRef;
    private final String awardItemNo;
    private final String originalOperationNo;
    private final RemediationAction action;
    private final String reason;
    private RemediationStatus status;
    private long version;

    public RemediationSuggestion(String tenantId, String suggestionId, String scenarioCode,
                                 String discrepancyRef, String awardItemNo, String originalOperationNo,
                                 RemediationAction action, String reason, RemediationStatus status, long version) {
        this.tenantId = Objects.requireNonNull(tenantId); this.suggestionId = Objects.requireNonNull(suggestionId);
        this.scenarioCode = Objects.requireNonNull(scenarioCode); this.discrepancyRef = Objects.requireNonNull(discrepancyRef);
        this.awardItemNo = Objects.requireNonNull(awardItemNo); this.originalOperationNo = originalOperationNo;
        this.action = Objects.requireNonNull(action); this.reason = Objects.requireNonNull(reason);
        this.status = Objects.requireNonNull(status); this.version = version;
    }

    public void approve() { transition(RemediationStatus.PROPOSED, RemediationStatus.APPROVED); }
    public void reject() { transition(RemediationStatus.PROPOSED, RemediationStatus.REJECTED); }
    public void dispatch() { transition(RemediationStatus.APPROVED, RemediationStatus.DISPATCHING); }
    public void succeed() { settle(RemediationStatus.SUCCEEDED); }
    public void fail() { settle(RemediationStatus.FAILED); }
    public void unknown() { settle(RemediationStatus.UNKNOWN); }
    private void settle(RemediationStatus target) {
        if (status != RemediationStatus.DISPATCHING && status != RemediationStatus.UNKNOWN)
            throw new IllegalStateException("cannot settle remediation from " + status);
        status = target; version++;
    }
    private void transition(RemediationStatus expected, RemediationStatus target) {
        if (status != expected) throw new IllegalStateException("invalid remediation transition");
        status = target; version++;
    }
    public String tenantId() { return tenantId; } public String suggestionId() { return suggestionId; }
    public String scenarioCode() { return scenarioCode; } public String discrepancyRef() { return discrepancyRef; }
    public String awardItemNo() { return awardItemNo; } public String originalOperationNo() { return originalOperationNo; }
    public RemediationAction action() { return action; } public String reason() { return reason; }
    public RemediationStatus status() { return status; } public long version() { return version; }
}
