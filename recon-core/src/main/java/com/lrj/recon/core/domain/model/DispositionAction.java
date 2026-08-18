package com.lrj.recon.core.domain.model;

/**
 * 人工处置动作 (设计 §3 discrepancy_disposition.action): 由 {@link DiscrepancyStateMachine} 校验合法流转,
 * 每个动作对应一个目标 {@link DispositionStatus}。
 *
 * <p>MVP REST 只暴露 {@link #RESOLVE} / {@link #CLOSE}; {@link #SUPPRESS} / {@link #REOPEN} 由状态机预留,
 * 供阶段二/运营台账使用。
 */
public enum DispositionAction {
    RESOLVE(DispositionStatus.RESOLVED),
    CLOSE(DispositionStatus.CLOSED),
    SUPPRESS(DispositionStatus.SUPPRESSED),
    REOPEN(DispositionStatus.REOPENED);

    private final DispositionStatus target;

    DispositionAction(DispositionStatus target) {
        this.target = target;
    }

    /** 该动作成功后落库的目标状态。 */
    public DispositionStatus targetStatus() {
        return target;
    }
}
