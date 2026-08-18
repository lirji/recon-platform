package com.lrj.recon.core.domain.model;

import java.util.Objects;

/**
 * 匹配键值对象: 用于 sort-merge join 的对齐。封装键值 + 字段名 + 桶号。
 *
 * <p>join 语义由键<b>值</b>决定: {@link #compareTo(MatchKey)} 与 {@link #equals(Object)} 只看 {@code value}
 * (两侧字段名可能不同 —— SEG1 左取 marketingIssueId、右取账务侧的营销发放ID 列, 但同一发放的值相等)。
 * {@code fieldName}/{@code bucket} 为血缘/分桶元数据, 不参与身份。
 */
public final class MatchKey implements Comparable<MatchKey> {

    private final String fieldName;
    private final String value;
    private final int bucket;

    public MatchKey(String fieldName, String value, int bucket) {
        this.fieldName = fieldName;
        this.value = Objects.requireNonNull(value, "match key value must not be null");
        this.bucket = bucket;
    }

    public static MatchKey of(String fieldName, String value, int bucket) {
        return new MatchKey(fieldName, value, bucket);
    }

    public String fieldName() {
        return fieldName;
    }

    public String value() {
        return value;
    }

    public int bucket() {
        return bucket;
    }

    @Override
    public int compareTo(MatchKey o) {
        return this.value.compareTo(o.value);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MatchKey other)) {
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
        return "MatchKey{" + fieldName + "=" + value + ", bucket=" + bucket + '}';
    }
}
