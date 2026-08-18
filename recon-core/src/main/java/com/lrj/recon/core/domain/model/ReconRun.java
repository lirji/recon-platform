package com.lrj.recon.core.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * 执行聚合根 (设计 §3): 一次对账运行的生命周期与并发控制单元。
 *
 * <p>不可变风格 (builder + 函数式流转): 状态流转方法返回<b>新实例</b>, 不原地修改, 便于测试与并发推理。
 * 并发/幂等由两个机制保证:
 * <ul>
 *   <li><b>uk_run</b> {@link RunKey}: {@code claim} 时 INSERT 命中唯一键即冲突, 挡并发重复 Run;</li>
 *   <li><b>revision 乐观锁</b>: {@code save(run, expectedRevision)} 条件更新 (WHERE revision=expected), 失败抛
 *       {@link ConflictException}。{@link #revision()} 是本对象<b>已知</b>的版本, 持久化层落库时置为 expected+1。</li>
 * </ul>
 * 无任何金额字段 (故不涉及 double 红线)。所有流转做前置状态校验, 非法流转抛 {@link IllegalStateException}。
 */
public final class ReconRun {

    private final String runId;
    private final RunKey key;
    private final Instant cutoffTime;
    private final Instant matchWindowFrom;   // T
    private final Instant matchWindowTo;     // T+1
    private final int bucketCount;
    private final ReconRunStatus status;
    private final long revision;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final Instant startedAt;         // nullable
    private final Instant finishedAt;        // nullable

    private ReconRun(Builder b) {
        this.runId = Objects.requireNonNull(b.runId, "runId");
        this.key = Objects.requireNonNull(b.key, "key");
        this.cutoffTime = b.cutoffTime;
        this.matchWindowFrom = b.matchWindowFrom;
        this.matchWindowTo = b.matchWindowTo;
        this.bucketCount = b.bucketCount;
        this.status = b.status == null ? ReconRunStatus.CREATED : b.status;
        this.revision = b.revision;
        this.createdAt = b.createdAt;
        this.updatedAt = b.updatedAt;
        this.startedAt = b.startedAt;
        this.finishedAt = b.finishedAt;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder()
                .runId(runId).key(key).cutoffTime(cutoffTime)
                .matchWindowFrom(matchWindowFrom).matchWindowTo(matchWindowTo)
                .bucketCount(bucketCount).status(status).revision(revision)
                .createdAt(createdAt).updatedAt(updatedAt).startedAt(startedAt).finishedAt(finishedAt);
    }

    // ---- 状态流转 (返回新实例, 前置校验) ----

    /** CREATED → LOADING (Step0 claim 后置装载态)。 */
    public ReconRun start() {
        requireStatus(ReconRunStatus.CREATED);
        return toBuilder().status(ReconRunStatus.LOADING).build();
    }

    /** LOADING → MATCHING (Step1 完成, 进入匹配判差)。 */
    public ReconRun toMatching() {
        requireStatus(ReconRunStatus.LOADING);
        return toBuilder().status(ReconRunStatus.MATCHING).build();
    }

    /** MATCHING → COMPLETED (Step3 守恒闭合)。 */
    public ReconRun complete() {
        requireStatus(ReconRunStatus.MATCHING);
        return toBuilder().status(ReconRunStatus.COMPLETED).build();
    }

    /** MATCHING → REPORT_IMBALANCE (Step3 双向守恒不闭合)。 */
    public ReconRun markImbalance() {
        requireStatus(ReconRunStatus.MATCHING);
        return toBuilder().status(ReconRunStatus.REPORT_IMBALANCE).build();
    }

    /** 任意非终态 → FAILED。 */
    public ReconRun fail() {
        if (status.isTerminal()) {
            throw new IllegalStateException("cannot fail a terminal run: " + status);
        }
        return toBuilder().status(ReconRunStatus.FAILED).build();
    }

    private void requireStatus(ReconRunStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("illegal transition from " + status + ", expected " + expected);
        }
    }

    public String runId() { return runId; }
    public RunKey key() { return key; }
    public String scenarioCode() { return key.scenarioCode(); }
    public String accountingPeriod() { return key.accountingPeriod(); }
    public int sequenceNo() { return key.sequenceNo(); }
    public Instant cutoffTime() { return cutoffTime; }
    public Instant matchWindowFrom() { return matchWindowFrom; }
    public Instant matchWindowTo() { return matchWindowTo; }
    public int bucketCount() { return bucketCount; }
    public ReconRunStatus status() { return status; }
    public long revision() { return revision; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public Instant startedAt() { return startedAt; }
    public Instant finishedAt() { return finishedAt; }

    @Override
    public String toString() {
        return "ReconRun{" + runId + ", " + key + ", " + status + ", rev=" + revision + '}';
    }

    public static final class Builder {
        private String runId;
        private RunKey key;
        private Instant cutoffTime;
        private Instant matchWindowFrom;
        private Instant matchWindowTo;
        private int bucketCount;
        private ReconRunStatus status;
        private long revision;
        private Instant createdAt;
        private Instant updatedAt;
        private Instant startedAt;
        private Instant finishedAt;

        public Builder runId(String v) { this.runId = v; return this; }
        public Builder key(RunKey v) { this.key = v; return this; }
        public Builder cutoffTime(Instant v) { this.cutoffTime = v; return this; }
        public Builder matchWindowFrom(Instant v) { this.matchWindowFrom = v; return this; }
        public Builder matchWindowTo(Instant v) { this.matchWindowTo = v; return this; }
        public Builder bucketCount(int v) { this.bucketCount = v; return this; }
        public Builder status(ReconRunStatus v) { this.status = v; return this; }
        public Builder revision(long v) { this.revision = v; return this; }
        public Builder createdAt(Instant v) { this.createdAt = v; return this; }
        public Builder updatedAt(Instant v) { this.updatedAt = v; return this; }
        public Builder startedAt(Instant v) { this.startedAt = v; return this; }
        public Builder finishedAt(Instant v) { this.finishedAt = v; return this; }

        public ReconRun build() {
            return new ReconRun(this);
        }
    }
}
