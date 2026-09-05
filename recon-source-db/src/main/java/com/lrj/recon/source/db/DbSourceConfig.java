package com.lrj.recon.source.db;

import java.util.Map;
import java.util.Objects;

/**
 * 从 {@link com.lrj.recon.core.spi.SourceDescriptor} 的 params 解出的 DB 源读取配置 (列映射 + 页大小)。
 *
 * <p>必填: {@code table} / {@code idColumn} (keyset 游标主键) / {@code groupKeyColumn} / {@code currencyColumn}
 * / {@code amountColumn}。选填列缺省时对应字段为 null / 默认值。
 */
final class DbSourceConfig {

    static final String P_TABLE = "table";
    static final String P_ID_COLUMN = "idColumn";
    static final String P_MATCH_KEY_COLUMN = "matchKeyColumn";
    static final String P_MATCH_KEY_FIELD = "matchKeyField";
    static final String P_GROUP_KEY_COLUMN = "groupKeyColumn";
    static final String P_GROUP_KEY_FIELD = "groupKeyField";
    static final String P_CURRENCY_COLUMN = "currencyColumn";
    static final String P_AMOUNT_COLUMN = "amountColumn";
    static final String P_ENTRY_TYPE_COLUMN = "entryTypeColumn";
    static final String P_BIZ_STATUS_COLUMN = "bizStatusColumn";
    static final String P_BIZ_TIME_COLUMN = "bizTimeColumn";
    static final String P_POSTING_TIME_COLUMN = "postingTimeColumn";
    static final String P_TENANT_COLUMN = "tenantColumn";
    static final String P_WINDOW_TIME_COLUMN = "windowTimeColumn";
    static final String P_RAW_REF_COLUMN = "rawRefColumn";
    static final String P_PAGE_SIZE = "pageSize";

    static final int DEFAULT_PAGE_SIZE = 1000;

    final String table;
    final String idColumn;
    final String matchKeyColumn;   // nullable
    final String matchKeyField;
    final String groupKeyColumn;
    final String groupKeyField;
    final String currencyColumn;
    final String amountColumn;
    final String entryTypeColumn;  // nullable
    final String bizStatusColumn;  // nullable
    final String bizTimeColumn;    // nullable
    final String postingTimeColumn;// nullable
    final String tenantColumn;     // nullable；权益 ODS 必填
    final String windowTimeColumn; // nullable；权益 ODS 必填
    final String rawRefColumn;     // nullable
    final int pageSize;

    private DbSourceConfig(Map<String, String> p) {
        this.table = require(p, P_TABLE);
        this.idColumn = require(p, P_ID_COLUMN);
        this.matchKeyColumn = p.get(P_MATCH_KEY_COLUMN);
        this.matchKeyField = p.getOrDefault(P_MATCH_KEY_FIELD,
                matchKeyColumn == null ? "matchKey" : matchKeyColumn);
        this.groupKeyColumn = require(p, P_GROUP_KEY_COLUMN);
        this.groupKeyField = p.getOrDefault(P_GROUP_KEY_FIELD, groupKeyColumn);
        this.currencyColumn = require(p, P_CURRENCY_COLUMN);
        this.amountColumn = require(p, P_AMOUNT_COLUMN);
        this.entryTypeColumn = p.get(P_ENTRY_TYPE_COLUMN);
        this.bizStatusColumn = p.get(P_BIZ_STATUS_COLUMN);
        this.bizTimeColumn = p.get(P_BIZ_TIME_COLUMN);
        this.postingTimeColumn = p.get(P_POSTING_TIME_COLUMN);
        this.tenantColumn = optional(p, P_TENANT_COLUMN);
        this.windowTimeColumn = optional(p, P_WINDOW_TIME_COLUMN);
        this.rawRefColumn = optional(p, P_RAW_REF_COLUMN);
        this.pageSize = parsePageSize(p.get(P_PAGE_SIZE));
    }

    static DbSourceConfig from(Map<String, String> params) {
        return new DbSourceConfig(Objects.requireNonNull(params, "params"));
    }

    private static String require(Map<String, String> p, String key) {
        String v = p.get(key);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("DbSourceAdapter descriptor missing required param: " + key);
        }
        return v.trim();
    }

    private static String optional(Map<String, String> p, String key) {
        String value = p.get(key);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static int parsePageSize(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_PAGE_SIZE;
        }
        int v = Integer.parseInt(raw.trim());
        if (v <= 0) {
            throw new IllegalArgumentException("pageSize must be > 0, got: " + v);
        }
        return v;
    }
}
