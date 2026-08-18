package com.lrj.recon.batch.job;

/**
 * 故障注入接缝 (测试用): 让集成测试在某个 partition (bucket) 的写入起点可控地抛错一次, 以验证 M3 分桶并行
 * 断点续跑 (某 partition 失败后重启<b>只续未完成 partition</b>, 已完成的不重跑)。
 *
 * <p>生产装配为 no-op (见 BatchConfig 默认 bean), 主流程零副作用。测试覆盖为对目标 bucket 一次性抛错。
 */
public interface PartitionFailureGate {

    /** 在某 bucket 的 chunk 写入前调用; 默认 no-op, 测试可覆盖为对目标 bucket 一次性抛错。 */
    void beforeBucketWrite(int bucket);
}
