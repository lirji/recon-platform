package com.lrj.recon.core;

import com.lrj.recon.core.domain.model.EntryType;
import com.lrj.recon.core.domain.model.MatchGroup;
import com.lrj.recon.core.domain.model.Presence;
import com.lrj.recon.core.domain.model.ReconRecord;
import com.lrj.recon.core.domain.model.Side;
import com.lrj.recon.core.domain.model.SourceRole;
import com.lrj.recon.core.domain.service.SortMergeJoiner;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SortMergeJoinTest {

    private List<MatchGroup> join(List<ReconRecord> lefts, List<ReconRecord> rights) {
        List<MatchGroup> out = new ArrayList<>();
        new SortMergeJoiner().join(lefts.iterator(), rights.iterator(), out::add);
        return out;
    }

    @Test
    void merges_multiple_keys_into_presence_buckets() {
        // K1 both, K2 left-only, K3 right-only —— 已按 key 升序喂入
        List<ReconRecord> lefts = List.of(
                ReconFixtures.left("K1", 100),
                ReconFixtures.left("K2", 200));
        List<ReconRecord> rights = List.of(
                ReconFixtures.right("K1", 100),
                ReconFixtures.right("K3", 300));

        List<MatchGroup> groups = join(lefts, rights);
        assertThat(groups).hasSize(3);

        MatchGroup k1 = groups.get(0);
        assertThat(k1.matchKey().value()).isEqualTo("K1");
        assertThat(k1.presence()).isEqualTo(Presence.BOTH);
        assertThat(k1.sumSignedLeftMinor()).isEqualTo(100L);
        assertThat(k1.sumSignedRightMinor()).isEqualTo(100L);

        MatchGroup k2 = groups.get(1);
        assertThat(k2.matchKey().value()).isEqualTo("K2");
        assertThat(k2.presence()).isEqualTo(Presence.LEFT_ONLY);
        assertThat(k2.sumSignedLeftMinor()).isEqualTo(200L);

        MatchGroup k3 = groups.get(2);
        assertThat(k3.matchKey().value()).isEqualTo("K3");
        assertThat(k3.presence()).isEqualTo(Presence.RIGHT_ONLY);
        assertThat(k3.sumSignedRightMinor()).isEqualTo(300L);
    }

    @Test
    void aggregates_signed_sum_within_group() {
        List<ReconRecord> lefts = List.of(
                ReconFixtures.base(Side.LEFT, SourceRole.MARKETING, "K1", "USD", 100, EntryType.ISSUE).build(),
                ReconFixtures.base(Side.LEFT, SourceRole.MARKETING, "K1", "USD", -30, EntryType.REFUND).build());
        List<ReconRecord> rights = List.of(ReconFixtures.right("K1", 70));

        List<MatchGroup> groups = join(lefts, rights);
        assertThat(groups).hasSize(1);
        MatchGroup g = groups.get(0);
        assertThat(g.countLeft()).isEqualTo(2);
        assertThat(g.sumSignedLeftMinor()).isEqualTo(70L);
        assertThat(g.isMultiLine()).isTrue();
        assertThat(g.duplicate()).isFalse();
    }

    @Test
    void flags_exact_duplicate_copies_on_one_side() {
        List<ReconRecord> lefts = List.of(
                ReconFixtures.left("K1", 100),
                ReconFixtures.left("K1", 100)); // 完全相同副本 → duplicate
        List<ReconRecord> rights = List.of(ReconFixtures.right("K1", 100));

        MatchGroup g = join(lefts, rights).get(0);
        assertThat(g.duplicate()).isTrue();
        assertThat(g.countLeft()).isEqualTo(2);
    }

    @Test
    void rejects_unsorted_input() {
        List<ReconRecord> lefts = List.of(
                ReconFixtures.left("K2", 100),
                ReconFixtures.left("K1", 100)); // 降序 → 非法
        assertThatThrownBy(() -> join(lefts, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not sorted");
    }
}
