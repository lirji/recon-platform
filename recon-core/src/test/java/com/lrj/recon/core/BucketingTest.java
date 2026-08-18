package com.lrj.recon.core;

import com.lrj.recon.core.domain.service.Bucketing;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link Bucketing} 单测 (修补①): bucket = floorMod(hash(group_key), N) 工具 + refine 不变式 fail-fast。
 */
class BucketingTest {

    @Test
    void bucketAlwaysInRangeAndNonNegative() {
        int n = 64;
        for (String key : new String[]{"I-1", "I-2", "ISSUE-99999", "渠道流水-887766", "", "z".repeat(200)}) {
            int b = Bucketing.bucketOf(key, n);
            assertThat(b).as("bucket for %s", key).isGreaterThanOrEqualTo(0).isLessThan(n);
        }
    }

    @Test
    void bucketIsDeterministicAndMatchesFloorModHash() {
        int n = 64;
        String key = "MKT-ISSUE-42";
        int expected = Math.floorMod(key.hashCode(), n);
        assertThat(Bucketing.bucketOf(key, n)).isEqualTo(expected);
        assertThat(Bucketing.bucketOf(key, n)).isEqualTo(Bucketing.bucketOf(key, n)); // 稳定
    }

    @Test
    void sameGroupKeyAlwaysLandsInSameBucket() {
        // 修补①核心: 桶键 = group_key, 故同发放单 (同 group_key) 必落同桶 → GROUP_SUM 与 join 单桶内完成
        int n = 8;
        int b1 = Bucketing.bucketOf("SETTLE-2026-08-17-0001", n);
        int b2 = Bucketing.bucketOf("SETTLE-2026-08-17-0001", n);
        assertThat(b1).isEqualTo(b2);
    }

    @Test
    void nonPositiveBucketCountDegradesToSingleBucket() {
        assertThat(Bucketing.bucketOf("anything", 0)).isZero();
        assertThat(Bucketing.bucketOf("anything", -5)).isZero();
    }

    @Test
    void nullGroupKeyFailsFast() {
        assertThatThrownBy(() -> Bucketing.bucketOf(null, 64))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void identityRefinePassesWhenMatchEqualsGroupOrMatchNull() {
        // match == group: 合法
        Bucketing.assertIdentityRefine("I-1", "I-1");
        // match == null (该侧无键, 由上游路由为单边): 放行, 不参与 refine 断言
        Bucketing.assertIdentityRefine(null, "I-1");
        Bucketing.assertIdentityRefine(null, null);
    }

    @Test
    void identityRefineFailsFastWhenMatchDiffersFromGroup() {
        // 违背 match_key == group_key 不变式 → fail-fast (启动/装载期拒绝)
        assertThatThrownBy(() -> Bucketing.assertIdentityRefine("LINE-1", "ISSUE-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("refine invariant violated");
        assertThatThrownBy(() -> Bucketing.assertIdentityRefine("I-1", null))
                .isInstanceOf(IllegalStateException.class);
    }
}
