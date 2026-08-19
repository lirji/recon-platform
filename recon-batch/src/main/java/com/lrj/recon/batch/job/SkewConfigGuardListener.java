package com.lrj.recon.batch.job;

import com.lrj.recon.batch.config.SubBucketPolicy;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.stereotype.Component;

/**
 * A5 / KI-1 守卫: sub-bucket 二级分桶形状/fanout 在 <b>restart 前被改</b> 会让上次拆分残留的局部结果
 * ({@code recon_report_partial}) 与新形状混存, 汇总步双算/漏算金额, 而左右口径同比例膨胀使 residual 仍构造性 ≡0,
 * <b>骗过守恒门禁</b> 把 Run 静默标 COMPLETED/balanced。
 *
 * <p>做法: 把当前 skew 形状指纹 {@code enabled|fanout} 写入 <b>Job 级 ExecutionContext</b>(跨 restart 由 Spring Batch
 * 复制持久化)。每次(重)执行 {@code beforeJob} 与上次形状比对, 对 KI-1 明列的<b>两个残留</b>子情形 fail-fast:
 * <ul>
 *   <li><b>fanout 数值变</b>(两次均 enabled 但 fanout 不同, 如 8→4): 子分片数变, worker 级 stale-partial 清理不覆盖;</li>
 *   <li><b>多次连续翻转</b>(累计形状翻转 ≥ 2, 如 sub→whole→sub): 层叠残留。</li>
 * </ul>
 * <b>单次</b>整桶↔sub 翻转(fanout 不变)<b>放行</b> —— 已由 {@code MatchEvaluateWriter} 的 worker 级
 * {@code deleteStaleBucketPartials} 修复(保留 partition-resume 语义, 见 ReconJobShapeFlipRestartTest)。
 *
 * <p>sub-bucket 默认关(生产 {@link SubBucketPolicy.ConfiguredSubBucketPolicy} 进程内不可变), 绝大多数部署形状恒定、
 * 本守卫恒 no-op; 仅显式开启并在 restart 前改配置才触发。fail-fast 优于静默错算: 运维改回原值(或换新 runId 重跑)即可。
 */
@Component
public class SkewConfigGuardListener implements JobExecutionListener {

    /** 上次执行的形状指纹 {@code enabled|fanout}。 */
    static final String KEY_SHAPE = "recon.skew.shape";
    /** 形状翻转累计次数(单次已缓解, ≥2 视为危险)。 */
    static final String KEY_TRANSITIONS = "recon.skew.transitions";

    private final SubBucketPolicy policy;

    public SkewConfigGuardListener(SubBucketPolicy policy) {
        this.policy = policy;
    }

    @Override
    public void beforeJob(JobExecution jobExecution) {
        String current = policy.enabled() + "|" + policy.fanout();
        ExecutionContext ctx = jobExecution.getExecutionContext();

        if (!ctx.containsKey(KEY_SHAPE)) {
            ctx.putString(KEY_SHAPE, current); // 首次执行, 记录基线
            ctx.putInt(KEY_TRANSITIONS, 0);
            return;
        }

        String previous = ctx.getString(KEY_SHAPE);
        if (previous.equals(current)) {
            return; // 形状未变, 正常 restart
        }

        int transitions = ctx.getInt(KEY_TRANSITIONS, 0) + 1;
        boolean fanoutChangedWhileEnabled =
                enabledOf(previous) && policy.enabled() && fanoutOf(previous) != policy.fanout();
        if (fanoutChangedWhileEnabled || transitions >= 2) {
            throw new IllegalStateException(String.format(
                    "KI-1: sub-bucket skew 配置在 restart 前被修改 (prev=%s, current=%s, 累计翻转=%d)。拒绝 restart"
                    + " 以免二级分桶局部结果 (recon_report_partial) 静默错算 —— 左右同比例膨胀会使 residual≡0 骗过守恒门禁。"
                    + " 请把 recon.skew.* 改回原值后再 restart, 或以新 runId 重跑。",
                    previous, current, transitions));
        }

        // 允许: 单次整桶↔sub 翻转 (fanout 不变), 已由 worker 级 stale-partial 清理覆盖。
        ctx.putString(KEY_SHAPE, current);
        ctx.putInt(KEY_TRANSITIONS, transitions);
    }

    private static boolean enabledOf(String shape) {
        return Boolean.parseBoolean(shape.substring(0, shape.indexOf('|')));
    }

    private static int fanoutOf(String shape) {
        return Integer.parseInt(shape.substring(shape.indexOf('|') + 1));
    }
}
