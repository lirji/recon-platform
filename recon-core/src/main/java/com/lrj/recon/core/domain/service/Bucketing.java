package com.lrj.recon.core.domain.service;

import com.lrj.recon.core.domain.model.MatchGroup;

import java.util.Map;

/**
 * 分桶工具 (修补①, ADR-11): 桶键 = group_key, {@code bucket = floorMod(hash(group_key), N)}。
 *
 * <p>纯函数, 无框架依赖。M2 单线程不真正分桶, 但仍在 loadStep 落盘 bucket 列, 为 M3 分桶并行 (BucketPartitioner)
 * 预留同一套桶键定义, 保证 M2→M3 桶分配语义一致。
 *
 * <h4>桶/组对齐不变式 (修补①, M4 放宽为 refine)</h4>
 * bucket 只由 {@code group_key} 决定 (不由 match_key), 且 <b>match_key 必须是 group_key 的细分</b>
 * ({@code group_key = f(match_key)} 良定义, 即"同一 match_key 只属唯一 group_key")。
 * <ul>
 *   <li>M2/M3 两段为 <b>match_key == group_key</b> (IDENTITY refine, 是 refine 的特例);</li>
 *   <li><b>M4 放宽为一般 refine</b>: 允许 {@code match_key != group_key} (如 SEG1 营销发放ID→发放单号 1:N),
 *       只要每个 match_key 映射到唯一 group_key。桶=hash(group_key) 仍保证"同 group 同桶",
 *       同发放单的各 match_key 落同一桶, sort-merge join 与聚合都在单桶内完成, 杜绝跨桶分裂假阳性。</li>
 * </ul>
 * {@link #assertRefine(String, String)} 在装载期做<b>结构性</b> refine 校验 (放宽版, O(1) 无状态);
 * {@link #assertRefineFunction(String, String, Map)} 做<b>函数性</b> refine 校验 (match_key→唯一 group_key);
 * {@link #assertIdentityRefine(String, String)} 是 IDENTITY 特例的更严校验 (match==group), <b>但 M4 起生产装载期
 * 统一改用放宽版 {@link #assertRefine(String, String)}, 本方法在 main 代码已无调用者</b>——仅供 IDENTITY 段的
 * 更严自证与既有单测 (不是已接线的生产护栏; 生产唯一 refine 关卡是 StandardizeProcessor 里的 assertRefine)。
 * assertRefine/assertRefineFunction 违背不变式时 fail-fast, 拒绝会产生跨桶分裂假阳性的配置/数据。
 */
public final class Bucketing {

    private Bucketing() {
    }

    /**
     * 计算 group_key 所属桶。{@code bucketCount <= 0} 退化为单桶 0 (M2 单线程默认)。
     * 用 {@link Math#floorMod(int, int)} 保证结果非负 (String#hashCode 可为负)。
     */
    public static int bucketOf(String groupKey, int bucketCount) {
        if (groupKey == null) {
            throw new IllegalArgumentException("group_key must not be null for bucketing (bucket 键 = group_key)");
        }
        if (bucketCount <= 0) {
            return 0;
        }
        return Math.floorMod(groupKey.hashCode(), bucketCount);
    }

    /**
     * IDENTITY refine 不变式校验 (修补①, IDENTITY 特例): match_key (非空时) 必须 == group_key。
     *
     * <p>null match_key 属"该侧无键"记录 (无法进 join, 由上游路由为单边/桥接), 不参与 refine 断言, 直接放行;
     * 非空 match_key 与 group_key 不一致则 fail-fast (拒绝装配/装载), 防止 GROUP_SUM 聚合与 join 落入不同桶。
     *
     * <p>M4 起装载期改用放宽版 {@link #assertRefine(String, String)} (允许 match!=group); 本方法保留供
     * IDENTITY 段的更严自证与既有单测。
     */
    public static void assertIdentityRefine(String matchKeyValue, String groupKeyValue) {
        if (matchKeyValue == null) {
            return;
        }
        if (groupKeyValue == null || !matchKeyValue.equals(groupKeyValue)) {
            throw new IllegalStateException(
                    "refine invariant violated (IDENTITY requires match_key == group_key): match_key="
                            + matchKeyValue + ", group_key=" + groupKeyValue);
        }
    }

    /**
     * <b>放宽版 refine 结构校验 (M4)</b>: 允许 {@code match_key != group_key} (一般 refine, 如 SEG1
     * 营销发放ID→发放单号 1:N), 但仍强制<b>可分桶前提</b>: 分桶键 = group_key, 故只要一侧带 match_key 参与 join,
     * 就必须有<b>非空 group_key</b> 以确定其桶 —— 否则该记录无法落桶、sort-merge 会漏配, fail-fast。
     * IDENTITY (match == group) 是本校验的合法特例。
     *
     * <p><b>无状态 O(1)</b>: 不校验"match_key→唯一 group_key"的<b>函数性</b> (那需跨记录状态, 见
     * {@link #assertRefineFunction}); 本方法只做逐记录结构校验, 供 loadStep 标准化 processor 在常量内存下逐条硬校验。
     *
     * @param matchKeyValue 该侧 match_key (可空: 该侧无键, 由上游 null 相位路由为单边组, 放行不校验)
     * @param groupKeyValue 该记录 group_key (分桶键)
     */
    public static void assertRefine(String matchKeyValue, String groupKeyValue) {
        if (matchKeyValue == null) {
            return; // 该侧无键: 由 null 相位路由出 join, 不参与 refine (分桶仍靠非空 group_key)
        }
        if (groupKeyValue == null) {
            throw new IllegalStateException(
                    "refine invariant violated (match_key present requires non-null group_key for bucketing): match_key="
                            + matchKeyValue);
        }
    }

    /**
     * <b>函数性 refine 校验 (M4)</b>: 强制"同一 match_key 只属唯一 group_key" ({@code group_key = f(match_key)}
     * 良定义)。逐条把 {@code (match_key -> group_key)} 记入 {@code witnessed}; 若同一 match_key 之前见过<b>不同</b>
     * group_key → fail-fast (该 match_key 会被分裂到两个桶, sort-merge 只在单桶内跑 → 漏配假阳性)。
     *
     * <p>null match_key 放行不记 (无键记录不参与函数性)。<b>需跨记录状态</b> (witnessed map), 故仅用于
     * 装配期抽样/有界校验或单测, <b>不</b>在千万级全量 loadStep 常量内存路径上调用 (那用 O(1) 的
     * {@link #assertRefine})。
     *
     * @param witnessed 累计 match_key→group_key 映射 (调用方维护, 校验期间持续传入同一实例)
     */
    public static void assertRefineFunction(String matchKeyValue, String groupKeyValue,
                                            Map<String, String> witnessed) {
        if (matchKeyValue == null) {
            return;
        }
        if (groupKeyValue == null) {
            throw new IllegalStateException(
                    "refine invariant violated (match_key present requires non-null group_key): match_key="
                            + matchKeyValue);
        }
        String prior = witnessed.putIfAbsent(matchKeyValue, groupKeyValue);
        if (prior != null && !prior.equals(groupKeyValue)) {
            throw new IllegalStateException(
                    "refine invariant violated (match_key must map to a single group_key): match_key="
                            + matchKeyValue + " maps to both group_key=" + prior + " and group_key=" + groupKeyValue);
        }
    }

    /**
     * 热点 bucket 的<b>二级 sub-bucket</b> 分片索引 (M3 数据倾斜兜底, 设计 §6): 把单个热点 bucket 的组按键
     * 再散列到 {@code 0..subFanout-1}, 供 BucketPartitioner 为热点 bucket 拆出多个并行子分区。
     *
     * <p><b>用 match_key (无则 group_key) 散列, 而非 record_id</b>: 同一勾兑键的左右两侧记录必落<b>同一</b>
     * sub-bucket (保 sort-merge join 对齐), 不同键分散到各 sub-bucket (分摊热点); 每个组恰属唯一 sub-bucket
     * (保守恒不重不漏)。record_id 散列会把同组记录拆散, 破坏组聚合, 故不采用。
     *
     * @param subFanout 分片数 (&le;1 退化为单片 0)
     */
    public static int subIndexOf(MatchGroup group, int subFanout) {
        if (subFanout <= 1) {
            return 0;
        }
        String key = group.matchKey() != null ? group.matchKey().value()
                : (group.groupKey() != null ? group.groupKey().value() : null);
        if (key == null) {
            return 0;
        }
        // 二级散列<b>必须与 bucketOf 独立</b>: 若直接 hashCode % subFanout, 当 subFanout | bucketCount 时,
        // 同 bucket (hashCode ≡ 0 mod bucketCount) 的键会全落同一 sub (mod subFanout 也 ≡ 0), 完全不分摊。
        // 故先做 avalanche 混合 (把高位灌进低位, 打散与 bucketCount 取模的相关性), 再 mod subFanout。
        int h = key.hashCode();
        h ^= (h >>> 16);
        h *= 0x7feb352d;
        h ^= (h >>> 15);
        h *= 0x846ca68b;
        h ^= (h >>> 16);
        return Math.floorMod(h, subFanout);
    }
}
