package com.lrj.recon.batch.service;

import com.lrj.recon.core.application.port.out.DiscrepancyActionRepository;
import com.lrj.recon.core.application.port.out.DiscrepancyDispositionRepository;
import com.lrj.recon.core.application.port.out.DiscrepancyRepository;
import com.lrj.recon.core.application.port.out.ReconRunRepository;
import com.lrj.recon.core.domain.model.ConflictException;
import com.lrj.recon.core.domain.model.Discrepancy;
import com.lrj.recon.core.domain.model.DiscrepancyAction;
import com.lrj.recon.core.domain.model.DiscrepancyActionType;
import com.lrj.recon.core.domain.model.DispositionAction;
import com.lrj.recon.core.domain.model.DispositionStatus;
import com.lrj.recon.core.domain.model.DiscrepancyDisposition;
import com.lrj.recon.core.domain.model.ReconRun;
import com.lrj.recon.core.domain.service.DiscrepancyStateMachine;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 人工核销在线服务 (设计 §4/§7/§11 M5): <b>独立在线事务</b> (绝不在批内) —— 写 {@code discrepancy_disposition}
 * (version 乐观锁) + {@link DiscrepancyStateMachine} 状态流转 + {@code discrepancy_action} 审计, 一个事务原子提交。
 *
 * <ul>
 *   <li><b>状态机</b>: OPEN→RESOLVED/CLOSED/SUPPRESSED/REOPENED 等; 非法流转抛 {@link IllegalStateException} (REST 映射 409);</li>
 *   <li><b>乐观锁</b>: 客户端可带 {@code expectedVersion} (If-Match 语义), 与当前处置 version 不符抛 {@link ConflictException}
 *       (REST 409); DB 侧条件更新再兜一层并发冲突;</li>
 *   <li><b>幂等</b>: 若当前状态已等于动作目标态 (如对已 RESOLVED 再 RESOLVE), 幂等短路返回现状, 不 bump version、不重复审计;</li>
 *   <li><b>不删机器判定</b>: 只写人工表, 机器差异 {@code discrepancy} 概不触碰 (ADR-7 三表分离)。</li>
 * </ul>
 * scenario/period 从差异所属 {@link ReconRun} 取 (差异行不冗余存, 由 run_id 溯源)。
 */
@Service
public class ManualClearingService {

    private final DiscrepancyRepository discrepancies;
    private final ReconRunRepository runs;
    private final DiscrepancyDispositionRepository dispositions;
    private final DiscrepancyActionRepository actions;
    private final DiscrepancyStateMachine stateMachine = new DiscrepancyStateMachine();

    public ManualClearingService(DiscrepancyRepository discrepancies,
                                 ReconRunRepository runs,
                                 DiscrepancyDispositionRepository dispositions,
                                 DiscrepancyActionRepository actions) {
        this.discrepancies = discrepancies;
        this.runs = runs;
        this.dispositions = dispositions;
        this.actions = actions;
    }

    /** REST 便捷入口: 核销 (RESOLVE)。 */
    public DiscrepancyDisposition resolve(String discrepancyId, String operator, String note, Integer expectedVersion) {
        return apply(discrepancyId, DispositionAction.RESOLVE, operator, note, expectedVersion);
    }

    /** REST 便捷入口: 关闭 (CLOSE)。 */
    public DiscrepancyDisposition close(String discrepancyId, String operator, String note, Integer expectedVersion) {
        return apply(discrepancyId, DispositionAction.CLOSE, operator, note, expectedVersion);
    }

    /**
     * 施加一个处置动作 (独立在线事务)。
     *
     * @param expectedVersion 乐观锁期望版本 (null = 不校验; 非 null 且与当前处置版本不符 → {@link ConflictException})。
     * @throws NotFoundException     差异 / 所属 Run 不存在。
     * @throws IllegalStateException 非法状态流转 (REST 映射 409)。
     * @throws ConflictException     乐观锁版本冲突 (REST 映射 409)。
     */
    @Transactional
    public DiscrepancyDisposition apply(String discrepancyId, DispositionAction action,
                                        String operator, String note, Integer expectedVersion) {
        if (action == null) {
            throw new IllegalArgumentException("action must not be null");
        }
        String normalizedOperator = requireOperator(operator);
        if (note != null && note.length() > 512) {
            throw new IllegalArgumentException("note must not exceed 512 characters");
        }
        if (expectedVersion != null && expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must not be negative");
        }
        Discrepancy d = discrepancies.findById(discrepancyId)
                .orElseThrow(() -> new NotFoundException("discrepancy not found: " + discrepancyId));
        ReconRun run = runs.find(d.runId())
                .orElseThrow(() -> new NotFoundException("run not found for discrepancy: " + d.runId()));

        Optional<DiscrepancyDisposition> existing = dispositions.findByFingerprint(d.fingerprint());
        Integer currentVersion = existing.map(DiscrepancyDisposition::version).orElse(null);
        DispositionStatus current = existing.map(DiscrepancyDisposition::status).orElse(null);

        // 乐观锁前置校验 (快速 409): 客户端声明的版本必须与当前一致 (无处置时须声明 null)。
        if (expectedVersion != null && !expectedVersion.equals(currentVersion)) {
            throw new ConflictException("disposition version conflict for fingerprint " + d.fingerprint()
                    + ": expected " + expectedVersion + " but current is " + currentVersion);
        }

        // 幂等短路: 当前态已是目标态 → 不改库, 直接返回现状。
        if (stateMachine.isNoop(current, action)) {
            return existing.orElseThrow();
        }

        DispositionStatus target = stateMachine.next(current, action); // 非法流转抛 IllegalStateException

        int expectedForStore = existing.map(DiscrepancyDisposition::version).orElse(0);
        String id = existing.map(DiscrepancyDisposition::id).orElseGet(() -> UUID.randomUUID().toString());
        DiscrepancyDisposition next = DiscrepancyDisposition.builder()
                .id(id)
                .fingerprint(d.fingerprint())
                .scenarioCode(run.scenarioCode())
                .accountingPeriod(run.accountingPeriod())
                .segmentId(d.segmentId())
                .status(target)
                .operator(normalizedOperator)
                .note(note)
                .lastSeenRunId(d.runId())
                .version(expectedForStore)   // 期望版本: insert 用 0, update 条件 WHERE version=expected
                .build();
        dispositions.upsert(next); // 乐观锁: 版本被他人推进 → ConflictException

        // 审计 (幂等键 = manual:actionType:fingerprint:newVersion; 每次成功流转唯一)。
        int newVersion = existing.map(v -> v.version() + 1).orElse(0);
        String idem = "manual:" + action.name() + ":" + d.fingerprint() + ":" + newVersion;
        actions.insertIfAbsent(DiscrepancyAction.builder()
                .id(UUID.randomUUID().toString())
                .fingerprint(d.fingerprint())
                .actionType(auditType(action))
                .idempotencyKey(idem)
                .payload("status=" + target + ",operator=" + normalizedOperator
                        + (note == null ? "" : ",note=" + note))
                .operator(normalizedOperator)
                .createdAt(Instant.now())
                .build());

        return dispositions.findByFingerprint(d.fingerprint()).orElse(next);
    }

    private static DiscrepancyActionType auditType(DispositionAction action) {
        return switch (action) {
            case RESOLVE -> DiscrepancyActionType.MANUAL_RESOLVE;
            case CLOSE -> DiscrepancyActionType.MANUAL_CLOSE;
            case SUPPRESS -> DiscrepancyActionType.MANUAL_SUPPRESS;
            case REOPEN -> DiscrepancyActionType.MANUAL_REOPEN;
        };
    }

    private static String requireOperator(String operator) {
        if (operator == null || operator.isBlank()) {
            throw new IllegalArgumentException("operator must not be blank");
        }
        String normalized = operator.trim();
        if (normalized.length() > 64) {
            throw new IllegalArgumentException("operator must not exceed 64 characters");
        }
        return normalized;
    }
}
