package com.lrj.recon.core.spi;

/**
 * 处理结果: 是否成功 + 幂等键 + 说明。重复触发 (同幂等键) 应返回幂等的 {@code applied=false}。
 */
public record HandlerResult(boolean applied, String idempotencyKey, String message) {

    public static HandlerResult applied(String idempotencyKey) {
        return new HandlerResult(true, idempotencyKey, null);
    }

    public static HandlerResult skippedDuplicate(String idempotencyKey) {
        return new HandlerResult(false, idempotencyKey, "duplicate, already applied");
    }
}
