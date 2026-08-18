package com.lrj.recon.core;

import com.lrj.recon.core.domain.model.DispositionAction;
import com.lrj.recon.core.domain.model.DispositionStatus;
import com.lrj.recon.core.domain.service.DiscrepancyStateMachine;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 差异处置状态机单测 (设计 §3/§11 M5): 合法/非法流转 + 幂等短路。OPEN 以 {@code null} 当前状态表示。
 */
class DiscrepancyStateMachineTest {

    private final DiscrepancyStateMachine sm = new DiscrepancyStateMachine();

    @Test
    void openTransitionsToResolvedCloseSuppress() {
        assertThat(sm.next(null, DispositionAction.RESOLVE)).isEqualTo(DispositionStatus.RESOLVED);
        assertThat(sm.next(null, DispositionAction.CLOSE)).isEqualTo(DispositionStatus.CLOSED);
        assertThat(sm.next(null, DispositionAction.SUPPRESS)).isEqualTo(DispositionStatus.SUPPRESSED);
    }

    @Test
    void reopenFromOpenIsIllegal() {
        assertThatThrownBy(() -> sm.next(null, DispositionAction.REOPEN))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OPEN");
    }

    @Test
    void resolvedCanCloseOrReopenButNotSuppress() {
        assertThat(sm.next(DispositionStatus.RESOLVED, DispositionAction.CLOSE)).isEqualTo(DispositionStatus.CLOSED);
        assertThat(sm.next(DispositionStatus.RESOLVED, DispositionAction.REOPEN)).isEqualTo(DispositionStatus.REOPENED);
        assertThatThrownBy(() -> sm.next(DispositionStatus.RESOLVED, DispositionAction.SUPPRESS))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void reopenedBehavesLikeOpen() {
        assertThat(sm.next(DispositionStatus.REOPENED, DispositionAction.RESOLVE)).isEqualTo(DispositionStatus.RESOLVED);
        assertThat(sm.next(DispositionStatus.REOPENED, DispositionAction.CLOSE)).isEqualTo(DispositionStatus.CLOSED);
    }

    @Test
    void closedOnlyReopens() {
        assertThat(sm.next(DispositionStatus.CLOSED, DispositionAction.REOPEN)).isEqualTo(DispositionStatus.REOPENED);
        assertThatThrownBy(() -> sm.next(DispositionStatus.CLOSED, DispositionAction.RESOLVE))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void staleCanBeReDispositioned() {
        // 重跑收敛自动关闭后, 差异再现可重新处置 (视同 OPEN)。
        assertThat(sm.next(DispositionStatus.STALE, DispositionAction.RESOLVE)).isEqualTo(DispositionStatus.RESOLVED);
        assertThat(sm.next(DispositionStatus.STALE, DispositionAction.CLOSE)).isEqualTo(DispositionStatus.CLOSED);
    }

    @Test
    void sameStateActionIsIdempotentNoop() {
        assertThat(sm.isNoop(DispositionStatus.RESOLVED, DispositionAction.RESOLVE)).isTrue();
        assertThat(sm.isNoop(DispositionStatus.CLOSED, DispositionAction.CLOSE)).isTrue();
        // 幂等短路: 即便非"合法流转集"内, isNoop 也允许返回目标态而非抛异常。
        assertThat(sm.next(DispositionStatus.RESOLVED, DispositionAction.RESOLVE)).isEqualTo(DispositionStatus.RESOLVED);
        assertThat(sm.isNoop(null, DispositionAction.RESOLVE)).isFalse();
    }
}
