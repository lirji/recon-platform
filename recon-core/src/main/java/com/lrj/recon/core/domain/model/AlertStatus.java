package com.lrj.recon.core.domain.model;

/**
 * 告警发件箱状态 (设计 §5 alert_outbox.status, ADR-10)。
 * 外部副作用出 chunk 事务, 批后由中继 at-least-once 投递。
 */
public enum AlertStatus {
    PENDING,
    SENT,
    FAILED
}
