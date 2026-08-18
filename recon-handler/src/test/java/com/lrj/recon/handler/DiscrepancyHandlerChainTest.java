package com.lrj.recon.handler;

import com.lrj.recon.core.application.port.out.AlertOutboxRepository;
import com.lrj.recon.core.application.port.out.DiscrepancyActionRepository;
import com.lrj.recon.core.application.port.out.ReversalSuggestionRepository;
import com.lrj.recon.core.domain.model.AlertOutbox;
import com.lrj.recon.core.domain.model.DiscrepancyAction;
import com.lrj.recon.core.domain.model.Discrepancy;
import com.lrj.recon.core.domain.model.DiscrepancyType;
import com.lrj.recon.core.domain.model.ReversalSuggestion;
import com.lrj.recon.core.spi.HandlerContext;
import com.lrj.recon.core.spi.HandlerKind;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 处理链纯 Java 单测 (免 Spring/DB, 用内存 fake 端口): 验证
 * <ul>
 *   <li>TRANSACTIONAL (Ledger/Reversal) 对金额型差异生成冲正建议 + 审计; AlertHandler (EXTERNAL) 只入 outbox;</li>
 *   <li>幂等: 同一 fingerprint 重复驱动处理链, 冲正/告警/审计各只 1 条 (chunk 重试 / 重跑不重复);</li>
 *   <li>ReversalSuggestionHandler 只对金额型差异 (AMOUNT_MISMATCH/GROUP_SUM_MISMATCH) supports, MISSING 不建议冲正;</li>
 *   <li>handler kind 归类正确 (Alert = EXTERNAL_SIDE_EFFECT, 其余 TRANSACTIONAL)。</li>
 * </ul>
 */
class DiscrepancyHandlerChainTest {

    private final FakeReversals reversals = new FakeReversals();
    private final FakeActions actions = new FakeActions();
    private final FakeOutbox outbox = new FakeOutbox();

    private DiscrepancyHandlerChain newChain() {
        return new DiscrepancyHandlerChain(List.of(
                new LedgerHandler(actions),
                new ReversalSuggestionHandler(reversals, actions),
                new AlertHandler(outbox),
                new FlowableTicketHandler()));
    }

    private static Discrepancy amountMismatch(String fp) {
        return Discrepancy.builder()
                .discrepancyId(fp).runId("run-1").segmentId("SEG1_MKT_ACCT")
                .type(DiscrepancyType.AMOUNT_MISMATCH).fingerprint(fp)
                .groupKey("O1").matchKey("I1").currency("USD")
                .expectedAmountMinor(1000).actualAmountMinor(900).deltaAmountMinor(100)
                .build();
    }

    @Test
    void amountMismatchGeneratesReversalActionAndOutboxOnce() {
        DiscrepancyHandlerChain chain = newChain();
        HandlerContext ctx = new HandlerContext("run-1", "system");
        Discrepancy d = amountMismatch("fp-amt");

        chain.handle(d, ctx);
        // 再驱动一次 (模拟 chunk 重试 / 重跑): 全部幂等命中, 不重复。
        chain.handle(d, ctx);

        assertThat(reversals.byKey).hasSize(1);
        assertThat(reversals.byKey.get("reversal-suggestion:fp-amt").suggestedAmountMinor()).isEqualTo(100L);
        assertThat(outbox.byKey).hasSize(1);
        assertThat(outbox.byKey.get("alert:fp-amt").payload()).contains("AMOUNT_MISMATCH");
        // 审计: ledger + reversal-suggestion 两条 (同 fingerprint 不同 handler 幂等键)
        assertThat(actions.byKey.keySet()).containsExactlyInAnyOrder("ledger:fp-amt", "reversal-suggestion:fp-amt");
    }

    @Test
    void missingDiscrepancyRaisesLedgerAndAlertButNoReversal() {
        DiscrepancyHandlerChain chain = newChain();
        Discrepancy missing = Discrepancy.builder()
                .discrepancyId("fp-miss").runId("run-1").segmentId("SEG2_ACCT_CHANNEL")
                .type(DiscrepancyType.MISSING).fingerprint("fp-miss")
                .groupKey("C8").matchKey("C8").currency("USD")
                .expectedAmountMinor(300).actualAmountMinor(0).deltaAmountMinor(300)
                .build();

        chain.handle(missing, new HandlerContext("run-1", "system"));

        assertThat(reversals.byKey).isEmpty();                 // MISSING 不建议冲正 (非金额纠偏)
        assertThat(outbox.byKey).containsOnlyKeys("alert:fp-miss");
        assertThat(actions.byKey).containsOnlyKeys("ledger:fp-miss"); // 只 LEDGER 审计
    }

    @Test
    void handlerKindsAreClassifiedCorrectly() {
        assertThat(new LedgerHandler(actions).kind()).isEqualTo(HandlerKind.TRANSACTIONAL);
        assertThat(new ReversalSuggestionHandler(reversals, actions).kind()).isEqualTo(HandlerKind.TRANSACTIONAL);
        assertThat(new AlertHandler(outbox).kind()).isEqualTo(HandlerKind.EXTERNAL_SIDE_EFFECT);
        assertThat(new FlowableTicketHandler().supports(amountMismatch("x"))).isFalse();
    }

    // ---------- 内存 fake 端口 (幂等 = 键存在则不覆盖) ----------

    private static final class FakeReversals implements ReversalSuggestionRepository {
        final Map<String, ReversalSuggestion> byKey = new HashMap<>();
        @Override public boolean insertIfAbsent(ReversalSuggestion s) {
            return byKey.putIfAbsent(s.idempotencyKey(), s) == null;
        }
    }

    private static final class FakeActions implements DiscrepancyActionRepository {
        final Map<String, DiscrepancyAction> byKey = new HashMap<>();
        @Override public boolean insertIfAbsent(DiscrepancyAction a) {
            return byKey.putIfAbsent(a.idempotencyKey(), a) == null;
        }
    }

    private static final class FakeOutbox implements AlertOutboxRepository {
        final Map<String, AlertOutbox> byKey = new ConcurrentHashMap<>();
        @Override public boolean insertIfAbsent(AlertOutbox a) {
            return byKey.putIfAbsent(a.idempotencyKey(), a) == null;
        }
        @Override public List<AlertOutbox> listPending() { return List.copyOf(byKey.values()); }
        @Override public List<AlertOutbox> listRetryable(int maxAttempt) { return List.copyOf(byKey.values()); }
        @Override public void markSent(String id, Instant sentAt) { }
        @Override public void markFailed(String id) { }
    }
}
