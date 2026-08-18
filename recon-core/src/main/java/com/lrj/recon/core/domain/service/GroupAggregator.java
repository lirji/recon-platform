package com.lrj.recon.core.domain.service;

import com.lrj.recon.core.domain.model.GroupKey;
import com.lrj.recon.core.domain.model.MatchGroup;
import com.lrj.recon.core.domain.model.MatchKey;
import com.lrj.recon.core.domain.model.Presence;
import com.lrj.recon.core.domain.model.ReconRecord;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 把同一 matchKey 的左右记录簇聚合为 {@link MatchGroup} (只算聚合量, 不物化列表)。
 *
 * <p>组内 signed 求和走 {@link MoneyMath#sumSignedMinor}; duplicate 标记 = 同一侧出现 (entryType, signedAmount)
 * 完全相同的重复副本 (真正的双记账), 与 ISSUE+REFUND 这类<b>不同</b>分录构成的合法多行组区分开。
 */
final class GroupAggregator {

    private GroupAggregator() {
    }

    static MatchGroup assemble(MatchKey matchKey, GroupKey groupKey,
                               List<ReconRecord> lefts, List<ReconRecord> rights) {
        boolean hasLeft = !lefts.isEmpty();
        boolean hasRight = !rights.isEmpty();
        Presence presence = hasLeft && hasRight ? Presence.BOTH
                : hasLeft ? Presence.LEFT_ONLY : Presence.RIGHT_ONLY;

        MatchGroup.Builder b = MatchGroup.builder()
                .matchKey(matchKey)
                .groupKey(groupKey)
                .presence(presence)
                .countLeft(lefts.size())
                .countRight(rights.size());

        boolean duplicate = false;
        if (hasLeft) {
            b.sumSignedLeftMinor(sumSigned(lefts));
            b.leftCurrency(singleCurrency(lefts));
            ReconRecord head = lefts.get(0);
            b.leftSampleRawRef(head.rawRef());
            b.leftBizStatus(head.bizStatus());
            b.leftPostingTime(head.postingTime());
            duplicate = hasExactDuplicate(lefts);
        }
        if (hasRight) {
            b.sumSignedRightMinor(sumSigned(rights));
            b.rightCurrency(singleCurrency(rights));
            ReconRecord head = rights.get(0);
            b.rightSampleRawRef(head.rawRef());
            b.rightBizStatus(head.bizStatus());
            b.rightPostingTime(head.postingTime());
            duplicate = duplicate || hasExactDuplicate(rights);
        }
        b.duplicate(duplicate);
        return b.build();
    }

    private static long sumSigned(List<ReconRecord> records) {
        long acc = 0L;
        for (ReconRecord r : records) {
            acc = MoneyMath.addExact(acc, r.signedAmountMinor());
        }
        return acc;
    }

    /** 取该侧统一币种; 若同侧混币视为数据异常, fail-fast (跨币比较应经 CURRENCY_MISMATCH 而非同侧混入)。 */
    private static String singleCurrency(List<ReconRecord> records) {
        String ccy = records.get(0).currency();
        for (ReconRecord r : records) {
            if (!ccy.equals(r.currency())) {
                throw new IllegalStateException(
                        "records within one side of a group must share currency, got "
                                + ccy + " and " + r.currency() + " for key " + records.get(0).matchKey());
            }
        }
        return ccy;
    }

    private static boolean hasExactDuplicate(List<ReconRecord> records) {
        if (records.size() < 2) {
            return false;
        }
        Set<String> seen = new HashSet<>();
        for (ReconRecord r : records) {
            String signature = r.entryType() + ":" + r.signedAmountMinor();
            if (!seen.add(signature)) {
                return true;
            }
        }
        return false;
    }
}
