package com.lrj.recon.batch.config;

import com.lrj.recon.core.application.port.out.AlertOutboxRepository;
import com.lrj.recon.core.application.port.out.DiscrepancyActionRepository;
import com.lrj.recon.core.application.port.out.ReversalSuggestionRepository;
import com.lrj.recon.handler.AlertHandler;
import com.lrj.recon.handler.DiscrepancyHandlerChain;
import com.lrj.recon.handler.FlowableTicketHandler;
import com.lrj.recon.handler.LedgerHandler;
import com.lrj.recon.handler.ReversalSuggestionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 组合根装配 M5 处理链: 把 recon-handler 的纯 Java handler (零 Spring 注解) 用端口 (Jdbc*Store) 组装成
 * {@link DiscrepancyHandlerChain}, 注入 matchEvaluate 的 writer 在 chunk 事务内驱动。
 *
 * <p>顺序 = LedgerHandler(审计) → ReversalSuggestionHandler(冲正建议, 同事务) → AlertHandler(只写 outbox 不发) →
 * FlowableTicketHandler(no-op 占位)。用<b>单一 chain bean</b> (而非散个 handler bean) 注入, 避免 {@code List<T>}
 * 自动收集与显式 List bean 的歧义。
 */
@Configuration
public class HandlerConfig {

    @Bean
    public DiscrepancyHandlerChain discrepancyHandlerChain(ReversalSuggestionRepository reversals,
                                                           AlertOutboxRepository outbox,
                                                           DiscrepancyActionRepository actions) {
        return new DiscrepancyHandlerChain(List.of(
                new LedgerHandler(actions),
                new ReversalSuggestionHandler(reversals, actions),
                new AlertHandler(outbox),
                new FlowableTicketHandler()));
    }
}
