package com.lrj.recon.core.spi;

import com.lrj.recon.core.domain.model.ReversalSuggestion;

/**
 * B3 · 冲正执行器 SPI:对<b>已审批通过(CONFIRMED)</b>的冲正建议执行<b>真实资金动作</b>(把实际额纠回应对额)。
 *
 * <p><b>资金红线</b>:这是全系统唯一真正动钱的插件。MVP/默认实现<b>不动真钱</b>(仅记账/日志),生产以
 * {@code @Primary} 注入接真实清结算/支付网关的适配器(参 {@code AlertDispatcher} 的替换范式)。
 * 必须<b>幂等</b>(按 {@code idempotencyKey} + 外部单号,重复执行不重复动钱)。抛异常表示执行失败(可重试),
 * 编排层据此置 {@code EXECUTION_FAILED},绝不静默吞。
 */
public interface ReversalExecutor {

    String executorId();

    /**
     * 执行一条冲正。返回外部执行凭证/单号(供审计与对账),失败抛异常。
     * 实现须对 {@link ReversalSuggestion#idempotencyKey()} 幂等。
     */
    String execute(ReversalSuggestion reversal);
}
