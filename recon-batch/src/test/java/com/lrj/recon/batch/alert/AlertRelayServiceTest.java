package com.lrj.recon.batch.alert;

import com.lrj.recon.core.domain.model.AlertOutbox;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * M5 告警中继 (设计 §6 Step4 / §7 / ADR-10): outbox PENDING→投递→SENT; 失败→FAILED+attempt→补投→SENT;
 * 死信 (超投递上限) 不再补投。{@link AlertDispatcher} 以 mock 控制成功/失败, 证明外部投递可控且脱离 chunk 事务。
 * {@code relayOnce} 正是 {@code @Scheduled} 补投调用的同一逻辑。
 */
@SpringBootTest
class AlertRelayServiceTest {

    @Autowired AlertRelayService relayService;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @MockBean AlertDispatcher dispatcher;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM alert_outbox");
    }

    private void seed(String id, String idem, String status, int attempt) {
        jdbc.update("""
                INSERT INTO alert_outbox(id, run_id, fingerprint, payload, status, attempt, idempotency_key, created_at)
                VALUES (?,?,?,?,?,?,?,?)
                """, id, "run-x", "F".repeat(64), "{}", status, attempt, idem,
                Timestamp.from(Instant.now()));
    }

    @Test
    void pendingDispatchedToSent() {
        when(dispatcher.dispatch(any(AlertOutbox.class))).thenAnswer(invocation -> {
            // 即使 relayOnce 从 Batch tasklet 的事务中进入，NOT_SUPPORTED 也保证网络 I/O 不占数据库事务。
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return true;
        });
        seed("a1", "alert:1", "PENDING", 0);

        Integer sent = new TransactionTemplate(transactionManager).execute(status -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return relayService.relayOnce();
        });

        assertThat(sent).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM alert_outbox WHERE id='a1'", String.class))
                .isEqualTo("SENT");
    }

    @Test
    void failureMarksFailedThenScheduledRelayResends() {
        seed("a2", "alert:2", "PENDING", 0);

        // 第一轮: 投递失败 → FAILED + attempt=1
        when(dispatcher.dispatch(any(AlertOutbox.class))).thenReturn(false);
        assertThat(relayService.relayOnce()).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM alert_outbox WHERE id='a2'", String.class))
                .isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT attempt FROM alert_outbox WHERE id='a2'", Integer.class))
                .isEqualTo(1);

        // 第二轮 (@Scheduled 补投): FAILED 被 listRetryable 拾起, 投递成功 → SENT
        when(dispatcher.dispatch(any(AlertOutbox.class))).thenReturn(true);
        assertThat(relayService.relayOnce()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM alert_outbox WHERE id='a2'", String.class))
                .isEqualTo("SENT");
    }

    @Test
    void dispatcherExceptionIsTreatedAsFailure() {
        seed("a3", "alert:3", "PENDING", 0);
        when(dispatcher.dispatch(any(AlertOutbox.class))).thenThrow(new RuntimeException("channel down"));

        assertThat(relayService.relayOnce()).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM alert_outbox WHERE id='a3'", String.class))
                .isEqualTo("FAILED");
    }

    @Test
    void deadLetterBeyondMaxAttemptIsNotRetried() {
        // attempt=5 >= 默认 max-attempt(5) → 不在可中继集内, dispatcher 不被调用, 状态不变
        when(dispatcher.dispatch(any(AlertOutbox.class))).thenReturn(true);
        seed("a4", "alert:4", "FAILED", 5);

        assertThat(relayService.relayOnce()).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM alert_outbox WHERE id='a4'", String.class))
                .isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT attempt FROM alert_outbox WHERE id='a4'", Integer.class))
                .isEqualTo(5);
    }
}
