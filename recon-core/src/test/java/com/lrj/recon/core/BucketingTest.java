package com.lrj.recon.core;

import com.lrj.recon.core.domain.service.Bucketing;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

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

    // ---------- M4: 放宽版 refine (允许 match != group) ----------

    @Test
    void relaxedRefineAllowsMatchDifferentFromGroup() {
        // M4 SEG1: match=营销发放ID, group=发放单号 (1:N), match != group 但合法 (只要能分桶)
        Bucketing.assertRefine("ISSUE-1", "ORDER-9");   // 放行
        Bucketing.assertRefine("ISSUE-2", "ORDER-9");   // 同发放单不同 issue, 放行
        Bucketing.assertRefine("K", "K");               // IDENTITY 是特例, 放行
        Bucketing.assertRefine(null, "ORDER-9");        // 该侧无键 (null 相位), 放行
    }

    @Test
    void relaxedRefineFailsFastWhenMatchPresentButNoGroupToBucket() {
        // 带 match_key 却无 group_key → 无法分桶 → fail-fast
        assertThatThrownBy(() -> Bucketing.assertRefine("ISSUE-1", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("refine invariant violated");
    }

    @Test
    void functionalRefineAcceptsOneToManyGroupAndDistinctMatchKeys() {
        Map<String, String> witnessed = new HashMap<>();
        // 同发放单 ORDER-9 下两条不同 issue → 1:N, 各 issue 映射唯一发放单, 合法
        Bucketing.assertRefineFunction("ISSUE-1", "ORDER-9", witnessed);
        Bucketing.assertRefineFunction("ISSUE-2", "ORDER-9", witnessed);
        // 重复见同 (match, group) 幂等放行
        Bucketing.assertRefineFunction("ISSUE-1", "ORDER-9", witnessed);
        // 另一发放单的 issue
        Bucketing.assertRefineFunction("ISSUE-3", "ORDER-7", witnessed);
        assertThat(witnessed).containsEntry("ISSUE-1", "ORDER-9").containsEntry("ISSUE-3", "ORDER-7");
    }

    @Test
    void functionalRefineFailsFastWhenMatchKeyMapsToTwoGroups() {
        Map<String, String> witnessed = new HashMap<>();
        Bucketing.assertRefineFunction("ISSUE-1", "ORDER-9", witnessed);
        // 同一 match_key 映射到不同 group_key → 会被分裂到两桶 → fail-fast
        assertThatThrownBy(() -> Bucketing.assertRefineFunction("ISSUE-1", "ORDER-7", witnessed))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("single group_key");
    }
}
