package com.lrj.recon.core.domain.service;

import com.lrj.recon.core.domain.model.MatchGroup;

/**
 * 分桶工具 (修补①, ADR-11): 桶键 = group_key, {@code bucket = floorMod(hash(group_key), N)}。
 *
 * <p>纯函数, 无框架依赖。M2 单线程不真正分桶, 但仍在 loadStep 落盘 bucket 列, 为 M3 分桶并行 (BucketPartitioner)
 * 预留同一套桶键定义, 保证 M2→M3 桶分配语义一致。
 *
 * <h4>桶/组对齐不变式 (修补①)</h4>
 * bucket 只由 {@code group_key} 决定 (不由 match_key), 且 <b>match_key 必须是 group_key 的细分</b>
 * ({@code group_key = f(match_key)} 良定义)。MVP 两段均为 <b>match_key == group_key</b> (IDENTITY refine):
 * 同发放单/同流水号必落同桶, GROUP_SUM 聚合与 sort-merge join 都在单桶内完成, 杜绝跨桶分裂假阳性。
 * {@link #assertIdentityRefine(String, String)} 在装配/装载期 fail-fast 拒绝违背不变式的配置/数据。
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
     * IDENTITY refine 不变式校验 (修补①, MVP 两段): match_key (非空时) 必须 == group_key。
     *
     * <p>null match_key 属"该侧无键"记录 (无法进 join, 由上游路由为单边/桥接), 不参与 refine 断言, 直接放行;
     * 非空 match_key 与 group_key 不一致则 fail-fast (拒绝装配/装载), 防止 GROUP_SUM 聚合与 join 落入不同桶。
     */
    public static void assertIdentityRefine(String matchKeyValue, String groupKeyValue) {
        if (matchKeyValue == null) {
            return;
        }
        if (groupKeyValue == null || !matchKeyValue.equals(groupKeyValue)) {
            throw new IllegalStateException(
                    "refine invariant violated (MVP requires match_key == group_key): match_key="
                            + matchKeyValue + ", group_key=" + groupKeyValue);
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
