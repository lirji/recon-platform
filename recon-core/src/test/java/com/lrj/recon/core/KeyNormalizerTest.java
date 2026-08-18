package com.lrj.recon.core;

import com.lrj.recon.core.domain.service.KeyNormalizer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #2 单测: 键规范化去尾随空白, 使 'K1' 与 'K1 ' (尾随空格) 归一 —— 消除 MySQL(PAD SPACE) 与 Java/PG(no-pad)
 * 对键相等性的判断分歧。null 保持 null。
 */
class KeyNormalizerTest {

    @Test
    void trailingSpaceKeysNormalizeToSameValue() {
        assertThat(KeyNormalizer.normalizeTrailing("K1 ")).isEqualTo("K1");
        assertThat(KeyNormalizer.normalizeTrailing("K1   ")).isEqualTo("K1");
        assertThat(KeyNormalizer.normalizeTrailing("K1")).isEqualTo("K1");
        // 两侧一带尾随空格一不带 → 规范化后相等 (根除 PAD SPACE 差异)
        assertThat(KeyNormalizer.normalizeTrailing("K1 "))
                .isEqualTo(KeyNormalizer.normalizeTrailing("K1"));
    }

    @Test
    void trailingTabAndMixedWhitespaceTrimmed() {
        assertThat(KeyNormalizer.normalizeTrailing("K1\t")).isEqualTo("K1");
        assertThat(KeyNormalizer.normalizeTrailing("K1 \t ")).isEqualTo("K1");
    }

    @Test
    void leadingAndInnerWhitespacePreserved() {
        // 只去尾随, 前导/中间空白是键值的一部分, 不动 (避免误改业务键)
        assertThat(KeyNormalizer.normalizeTrailing(" K1")).isEqualTo(" K1");
        assertThat(KeyNormalizer.normalizeTrailing("K 1")).isEqualTo("K 1");
    }

    @Test
    void nullStaysNull() {
        assertThat(KeyNormalizer.normalizeTrailing(null)).isNull();
    }

    @Test
    void emptyAndAllWhitespace() {
        assertThat(KeyNormalizer.normalizeTrailing("")).isEqualTo("");
        assertThat(KeyNormalizer.normalizeTrailing("   ")).isEqualTo("");
    }
}
