package com.lrj.recon.batch.service;

import com.lrj.recon.core.domain.model.ReversalStatus;
import com.lrj.recon.core.spi.ReversalExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B3 · 冲正执行编排:CONFIRMED→EXECUTED、非 CONFIRMED 拒绝、已执行幂等跳过、执行器失败→EXECUTION_FAILED。
 */
@SpringBootTest
class ReversalExecutionServiceTest {

    @Autowired
    ReversalExecutionService service;

    @Autowired
    JdbcTemplate jdbc;

    @MockBean
    ReversalExecutor executor;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM discrepancy_action");
        jdbc.update("DELETE FROM reversal_suggestion");
        when(executor.execute(any())).thenReturn("REF-1");
    }

    private void seed(String id, String status) {
        jdbc.update("""
                INSERT INTO reversal_suggestion(id, fingerprint, run_id, group_key, suggested_amount_minor,
                    currency, status, idempotency_key, operator, created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """,
                id, "F".repeat(64), "run-1", "G1", 500L, "USD", status, "idem-" + id, null,
                Timestamp.from(Instant.parse("2026-08-19T10:00:00Z")));
    }

    private String statusOf(String id) {
        return jdbc.queryForObject("SELECT status FROM reversal_suggestion WHERE id=?", String.class, id);
    }

    @Test
    void executes_a_confirmed_reversal() {
        seed("r1", "CONFIRMED");
        ReversalExecutionService.Result res = service.execute("r1", "alice");

        assertThat(res.executed()).isTrue();
        assertThat(res.status()).isEqualTo(ReversalStatus.EXECUTED);
        assertThat(res.reference()).isEqualTo("REF-1");
        assertThat(statusOf("r1")).isEqualTo("EXECUTED");
        verify(executor, times(1)).execute(any());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM discrepancy_action WHERE action_type='REVERSAL_EXECUTED'", Long.class))
                .isEqualTo(1);
    }

    @Test
    void refuses_to_execute_a_non_confirmed_reversal() {
        seed("r2", "SUGGESTED");
        assertThatThrownBy(() -> service.execute("r2", "x")).isInstanceOf(IllegalStateException.class);
        assertThat(statusOf("r2")).isEqualTo("SUGGESTED");
        verify(executor, never()).execute(any());
    }

    @Test
    void is_idempotent_once_executed() {
        seed("r3", "CONFIRMED");
        service.execute("r3", "alice");
        ReversalExecutionService.Result second = service.execute("r3", "alice");

        assertThat(second.executed()).isTrue();
        assertThat(second.reference()).contains("idempotent");
        verify(executor, times(1)).execute(any()); // 第二次不再动钱
    }

    @Test
    void missing_reversal_is_not_found() {
        assertThatThrownBy(() -> service.execute("nope", "x")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void executor_failure_marks_execution_failed_and_does_not_swallow() {
        seed("r4", "CONFIRMED");
        when(executor.execute(any())).thenThrow(new RuntimeException("gateway down"));

        assertThatThrownBy(() -> service.execute("r4", "bob")).isInstanceOf(IllegalStateException.class);
        assertThat(statusOf("r4")).isEqualTo("EXECUTION_FAILED");
    }
}
