package com.lrj.recon.core;

import com.lrj.recon.core.domain.model.ReconRun;
import com.lrj.recon.core.domain.model.ReconRunStatus;
import com.lrj.recon.core.domain.model.RunKey;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ReconRun 聚合根状态机 (纯领域, M1 新增)。流转返回新实例, 非法流转 fail-fast。
 */
class ReconRunTest {

    private ReconRun created() {
        return ReconRun.builder()
                .runId("run-1")
                .key(RunKey.of("marketing-3way", "2026-08-17", 1))
                .cutoffTime(Instant.parse("2026-08-17T23:59:59Z"))
                .matchWindowFrom(Instant.parse("2026-08-17T00:00:00Z"))
                .matchWindowTo(Instant.parse("2026-08-18T23:59:59Z"))
                .bucketCount(64)
                .status(ReconRunStatus.CREATED)
                .revision(0)
                .build();
    }

    @Test
    void happyPathTransitions() {
        ReconRun run = created();
        assertThat(run.status()).isEqualTo(ReconRunStatus.CREATED);
        run = run.start();
        assertThat(run.status()).isEqualTo(ReconRunStatus.LOADING);
        run = run.toMatching();
        assertThat(run.status()).isEqualTo(ReconRunStatus.MATCHING);
        ReconRun completed = run.complete();
        assertThat(completed.status()).isEqualTo(ReconRunStatus.COMPLETED);
        assertThat(completed.status().isTerminal()).isTrue();
        // 原对象不被修改 (不可变)
        assertThat(run.status()).isEqualTo(ReconRunStatus.MATCHING);
    }

    @Test
    void imbalancePath() {
        ReconRun matching = created().start().toMatching();
        assertThat(matching.markImbalance().status()).isEqualTo(ReconRunStatus.REPORT_IMBALANCE);
    }

    @Test
    void illegalTransitionFailsFast() {
        assertThatThrownBy(() -> created().toMatching())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> created().start().toMatching().complete().fail())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void failFromNonTerminal() {
        assertThat(created().start().fail().status()).isEqualTo(ReconRunStatus.FAILED);
    }
}
