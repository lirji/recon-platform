package com.lrj.recon.core.domain.model;

import java.util.Objects;

/**
 * 1:N 聚合键 (发放单级)。身份由键<b>值</b>决定。
 *
 * <p>桶/组对齐不变式 (修补①): {@code bucket = floorMod(hash(group_key), N)}, 且 match_key 必须是 group_key 的细分。
 * MVP 两段均满足 <b>match_key == group_key</b> (SEG1 都为 marketingIssueId, SEG2 都为 channelSerialNo),
 * 同发放单必落同桶, GROUP_SUM 聚合与 join 都在单桶内完成。
 */
public final class GroupKey implements Comparable<GroupKey> {

    private final String fieldName;
    private final String value;

    public GroupKey(String fieldName, String value) {
        this.fieldName = fieldName;
        this.value = Objects.requireNonNull(value, "group key value must not be null");
    }

    public static GroupKey of(String fieldName, String value) {
        return new GroupKey(fieldName, value);
    }

    public String fieldName() {
        return fieldName;
    }

    public String value() {
        return value;
    }

    @Override
    public int compareTo(GroupKey o) {
        return this.value.compareTo(o.value);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof GroupKey other)) {
            return false;
        }
        return value.equals(other.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return "GroupKey{" + fieldName + "=" + value + '}';
    }
}
