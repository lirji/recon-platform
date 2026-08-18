package com.lrj.recon.core.domain.service;

/**
 * 勾兑键规范化 (遗留②/#2 修复): 消除 <b>尾随空格</b> 在不同比较语义下的静默错配。
 *
 * <h4>为什么需要</h4>
 * match_key 落 MySQL 时列 collation 为 {@code utf8mb4_bin}, 而 MySQL 的 {@code VARCHAR} 比较是
 * <b>PAD SPACE</b> 语义 —— {@code 'K1'} 与 {@code 'K1 '} (尾随空格) 在 {@code =} / 排序下视为<b>相等</b>;
 * 而 Java {@link String#compareTo}/{@code equals} 与 PostgreSQL ({@code no-pad}) 视为<b>不等</b>。若两侧勾兑键
 * 一侧带尾随空格、一侧不带, DB 与 Java 归并对键相等性的判断就会发散, 产生假 MISSING/EXTRA 或假匹配。
 *
 * <h4>做法</h4>
 * 在标准化收口处 (loadStep 的 StandardizeProcessor, 两侧记录都经过它) 对 match_key / group_key 统一
 * <b>去除尾随空白</b>后再落库。落库值已无尾随空白 → DB(PAD SPACE) 与 Java(no-pad)/PG 对同一批已规范化的键
 * 判断一致, 从根上消除 PAD SPACE 差异。{@code null} 保持 {@code null} (该侧无键)。
 *
 * <p>用 {@link String#stripTrailing()} (去除尾随 Unicode 空白, 含普通空格 {@code U+0020} 与 tab 等): 比"仅去
 * 尾随空格"更强, 但因规范化发生在<b>入库前</b>, 两侧同规则处理, 一致性成立。星平面字符 (surrogate pair) 的
 * UTF-16 码元序 vs 码点序仍可能有极端微差, 与本规范化正交 (MVP 勾兑键为 ASCII/BMP 业务号, 不受影响)。
 *
 * <p>纯函数, 零框架依赖。
 */
public final class KeyNormalizer {

    private KeyNormalizer() {
    }

    /**
     * 去除键值的尾随空白 (消除 PAD SPACE 差异); {@code null} 原样返回。
     *
     * @param value 原始键值 (可空)
     * @return 去尾随空白后的键值; {@code null} → {@code null}
     */
    public static String normalizeTrailing(String value) {
        return value == null ? null : value.stripTrailing();
    }
}
