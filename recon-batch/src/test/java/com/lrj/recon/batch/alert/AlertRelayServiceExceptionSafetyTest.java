package com.lrj.recon.batch.alert;

import com.lrj.recon.core.application.port.out.AlertOutboxRepository;
import com.lrj.recon.core.domain.model.AlertOutbox;
import com.lrj.recon.core.domain.model.AlertStatus;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 回归 (M5 review 确认的 medium bug): 状态更新短事务 (markSent/markFailed) 的<b>瞬时 DB 故障</b>必须被吞,
 * 不冒泡出 {@link AlertRelayService#relayOnce()} —— 否则 {@code AlertRelayTasklet} 会把整个 Job 误标 FAILED,
 * 违背 relayOnce / tasklet javadoc 的"投递失败不使 Step/Job 失败、不中断本轮其它条目、恒返回 FINISHED"契约。
 *
 * <p>纯单元测试: mock outbox 让第一条 {@code markSent} 抛 {@link DeadlockLoserDataAccessException},
 * 断言 relayOnce 不抛、且循环继续处理第二条 (证明单条状态更新失败被隔离)。
 */
class AlertRelayServiceExceptionSafetyTest {

    private static AlertOutbox entry(String id, String idem) {
        return AlertOutbox.builder()
                .id(id).runId("r").fingerprint("F".repeat(64)).payload("{}")
                .status(AlertStatus.PENDING).attempt(0).idempotencyKey(idem)
                .createdAt(Instant.now()).build();
    }

    /** no-op 事务管理器: getTransaction 返回哑 status, commit/rollback 皆 no-op; 用于让 TransactionTemplate 直接跑 lambda。 */
    private static PlatformTransactionManager noopTxManager() {
        PlatformTransactionManager txm = mock(PlatformTransactionManager.class);
        when(txm.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        return txm;
    }

    @Test
    void stateUpdateFailureIsSwallowedAndLoopContinues() {
        AlertOutboxRepository outbox = mock(AlertOutboxRepository.class);
        AlertDispatcher dispatcher = mock(AlertDispatcher.class);

        AlertOutbox e1 = entry("a1", "alert:1");
        AlertOutbox e2 = entry("a2", "alert:2");
        when(outbox.listRetryable(anyInt())).thenReturn(List.of(e1, e2));
        when(dispatcher.dispatch(any(AlertOutbox.class))).thenReturn(true);
        // 第一条 markSent 遇瞬时 DB 故障(连接回收/死锁/锁超时的一种)抛异常; 第二条正常。
        doThrow(new DeadlockLoserDataAccessException("boom", null))
                .when(outbox).markSent(eq("a1"), any(Instant.class));

        AlertRelayService svc = new AlertRelayService(outbox, dispatcher, noopTxManager(), 5);

        // 修复前: 第一条 markSent 抛异常冒泡出 relayOnce → tasklet → Job FAILED。
        // 修复后: 吞掉该条状态更新异常, 保持原态待下轮补投, 且循环继续。
        assertThatCode(svc::relayOnce).doesNotThrowAnyException();
        // 第二条仍被处理 → 证明单条失败未中断本轮其它条目。
        verify(outbox).markSent(eq("a2"), any(Instant.class));
    }
}
