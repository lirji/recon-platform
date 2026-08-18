package com.lrj.recon.core.domain.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 差异稳定指纹 (ADR-9, 替换含空列的自然键)。
 *
 * <p>canonical = {@code scenario|period|segment|type|coalesce(group_key,'∅')|coalesce(match_key,'∅')|coalesce(bridge_stage,'∅')}
 * 的 SHA-256 (小写十六进制)。每个字段先转义分隔符 {@code '|'} 与转义符 {@code '\'}, 避免不同字段元组拼出同一 canonical 而指纹碰撞。
 * null 键统一折叠为 {@code '∅'}, 因此对 BRIDGE_BROKEN / CURRENCY_MISMATCH 等
 * 空键类型仍稳定且幂等, 并作为人工处置跨重跑 re-link 的锚。
 */
public final class Fingerprint {

    /** 空值占位符 (U+2205 EMPTY SET)。 */
    public static final String NULL_TOKEN = "∅";
    private static final char SEP = '|';

    private Fingerprint() {
    }

    public static String of(String scenario,
                            String period,
                            String segment,
                            String type,
                            String groupKey,
                            String matchKey,
                            String bridgeStage) {
        String canonical = coalesce(scenario) + SEP
                + coalesce(period) + SEP
                + coalesce(segment) + SEP
                + coalesce(type) + SEP
                + coalesce(groupKey) + SEP
                + coalesce(matchKey) + SEP
                + coalesce(bridgeStage);
        return sha256Hex(canonical);
    }

    private static String coalesce(String v) {
        return v == null ? NULL_TOKEN : escape(v);
    }

    /** 转义转义符与分隔符 (null 已折叠为 NULL_TOKEN, 其不含特殊字符, 与显式 '∅' 折叠语义保持一致)。 */
    private static String escape(String v) {
        if (v.indexOf('\\') < 0 && v.indexOf(SEP) < 0) {
            return v;
        }
        return v.replace("\\", "\\\\").replace(String.valueOf(SEP), "\\" + SEP);
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必备算法, 不会发生; fail-fast。
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
