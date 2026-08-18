package com.lrj.recon.handler;

/**
 * 处理器 id 常量 + 幂等键口径 (设计 §4): 幂等键 = {@code handlerId + ":" + fingerprint} (run 无关, 与 fingerprint
 * 身份对齐)。同一差异身份 (fingerprint) 在同一 handler 只处理一次 —— chunk 重试 / 跨重跑均幂等, 不重复生成
 * 冲正建议 / 告警 outbox / 审计。
 */
public final class HandlerIds {

    public static final String LEDGER = "ledger";
    public static final String REVERSAL_SUGGESTION = "reversal-suggestion";
    public static final String ALERT = "alert";
    public static final String FLOWABLE_TICKET = "flowable-ticket";

    private HandlerIds() {
    }

    /** 幂等键: {@code handlerId + ":" + fingerprint}。fingerprint 跨重跑稳定, 故幂等键亦稳定。 */
    public static String idempotencyKey(String handlerId, String fingerprint) {
        return handlerId + ":" + fingerprint;
    }
}
