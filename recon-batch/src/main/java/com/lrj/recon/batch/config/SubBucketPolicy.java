package com.lrj.recon.batch.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 二级 sub-bucket 倾斜兜底的<b>开关策略</b> (设计 §6): 是否对热点 bucket 拆分, 及拆分 fanout。
 *
 * <p>抽成独立 bean 的动机 (#1): {@link BucketPartitioner} 是 @StepScope, 每次 matchEvaluate manager step
 * (含断点续跑重启) 重建时读取本策略的<b>当前</b>值。生产默认实现从 {@code recon.skew.sub-bucket.*} 配置读取 (不可变);
 * 集成测试可用可变实现<b>在两次 launch 之间翻转</b>形状 (整桶↔sub-bucket), 复现"restart + skew 配置变" 的
 * shape-flip 场景, 验证局部结果不被双算 (见 deleteStaleBucketPartials)。
 */
public interface SubBucketPolicy {

    /** 是否对热点 bucket 启用二级 sub-bucket 拆分。 */
    boolean enabled();

    /** 拆分 fanout (子分片数); {@code <=1} 退化为不拆。 */
    int fanout();

    /** 生产默认: 从配置读取, 进程内不可变。 */
    @Component
    class ConfiguredSubBucketPolicy implements SubBucketPolicy {
        private final boolean enabled;
        private final int fanout;

        public ConfiguredSubBucketPolicy(
                @Value("${recon.skew.sub-bucket.enabled:false}") boolean enabled,
                @Value("${recon.skew.sub-bucket.fanout:8}") int fanout) {
            this.enabled = enabled;
            this.fanout = fanout;
        }

        @Override
        public boolean enabled() {
            return enabled;
        }

        @Override
        public int fanout() {
            return fanout;
        }
    }
}
