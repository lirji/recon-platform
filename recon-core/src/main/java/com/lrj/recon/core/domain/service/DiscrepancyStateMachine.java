package com.lrj.recon.core.domain.service;

import com.lrj.recon.core.domain.model.DispositionAction;
import com.lrj.recon.core.domain.model.DispositionStatus;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 差异人工处置状态机 (设计 §3/§11 M5): 校验从当前处置状态出发的合法流转, 非法流转抛 {@link IllegalStateException}。
 *
 * <p><b>OPEN</b> = 无处置记录 (以 {@code null} 当前状态表示)。合法流转 (纯 Java, 零框架):
 * <pre>
 *   OPEN(null)  --RESOLVE-->  RESOLVED
 *               --CLOSE---->  CLOSED
 *               --SUPPRESS->  SUPPRESSED
 *   RESOLVED    --CLOSE---->  CLOSED
 *               --REOPEN--->  REOPENED
 *   SUPPRESSED  --REOPEN--->  REOPENED     (亦可 CLOSE)
 *   CLOSED      --REOPEN--->  REOPENED
 *   REOPENED    --RESOLVE-->  RESOLVED     (视同 OPEN)
 *               --CLOSE---->  CLOSED
 *               --SUPPRESS->  SUPPRESSED
 *   STALE       --RESOLVE/CLOSE/SUPPRESS  (重跑收敛自动关闭后, 差异再现可重新处置; 视同 OPEN)
 * </pre>
 * 幂等: 若当前状态已等于动作的目标状态, {@link #isNoop} 为 {@code true}, 服务层据此走幂等短路 (不 bump version、
 * 不产生新审计), 而非当成非法流转。
 */
public final class DiscrepancyStateMachine {

    /** 由各状态 (null 键单列, 见 openTransitions) 出发允许的动作集合。 */
    private static final Map<DispositionStatus, Set<DispositionAction>> ALLOWED = new EnumMap<>(DispositionStatus.class);

    /** OPEN (无处置记录, current==null) 允许的动作。 */
    private static final Set<DispositionAction> OPEN_ALLOWED =
            EnumSet.of(DispositionAction.RESOLVE, DispositionAction.CLOSE, DispositionAction.SUPPRESS);

    static {
        ALLOWED.put(DispositionStatus.RESOLVED, EnumSet.of(DispositionAction.CLOSE, DispositionAction.REOPEN));
        ALLOWED.put(DispositionStatus.CLOSED, EnumSet.of(DispositionAction.REOPEN));
        ALLOWED.put(DispositionStatus.SUPPRESSED, EnumSet.of(DispositionAction.CLOSE, DispositionAction.REOPEN));
        ALLOWED.put(DispositionStatus.REOPENED,
                EnumSet.of(DispositionAction.RESOLVE, DispositionAction.CLOSE, DispositionAction.SUPPRESS));
        // STALE (重跑收敛自动关闭) 差异再现后可重新处置, 视同 OPEN。
        ALLOWED.put(DispositionStatus.STALE, EnumSet.copyOf(OPEN_ALLOWED));
    }

    /**
     * 校验并返回目标状态。
     *
     * @param current 当前处置状态; {@code null} 表示 OPEN (无处置记录)。
     * @param action  请求的处置动作。
     * @return 动作合法时的目标状态。
     * @throws IllegalStateException 非法流转 (且非幂等重复)。
     */
    public DispositionStatus next(DispositionStatus current, DispositionAction action) {
        if (action == null) {
            throw new IllegalArgumentException("action must not be null");
        }
        if (isNoop(current, action)) {
            return action.targetStatus();
        }
        Set<DispositionAction> allowed = current == null ? OPEN_ALLOWED : ALLOWED.get(current);
        if (allowed == null || !allowed.contains(action)) {
            throw new IllegalStateException("illegal disposition transition: " + describe(current)
                    + " --" + action + "-->; allowed = " + (allowed == null ? Set.of() : allowed));
        }
        return action.targetStatus();
    }

    /** 幂等短路判定: 当前状态已等于动作目标状态 (如对已 RESOLVED 的差异再次 RESOLVE)。 */
    public boolean isNoop(DispositionStatus current, DispositionAction action) {
        return current != null && current == action.targetStatus();
    }

    private static String describe(DispositionStatus current) {
        return current == null ? "OPEN" : current.name();
    }
}
