package com.lrj.recon.source.csv;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 从 SourceDescriptor params 解出的 CSV 文件、格式和标准字段映射。 */
final class CsvSourceConfig {

    private static final int MAX_PATH_LENGTH_FOR_RAW_REF = 220;

    static final String P_PATH = "path";
    static final String P_CHARSET = "charset";
    static final String P_DELIMITER = "delimiter";
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

    final Path path;
    final Charset charset;
    final boolean charsetExplicit;
    final char delimiter;
    final String idColumn;
    final String matchKeyColumn;
    final String matchKeyField;
    final String groupKeyColumn;
    final String groupKeyField;
    final String currencyColumn;
    final String amountColumn;
    final String entryTypeColumn;
    final String bizStatusColumn;
    final String bizTimeColumn;
    final String postingTimeColumn;

    private CsvSourceConfig(Map<String, String> p) {
        this.path = Path.of(require(p, P_PATH)).toAbsolutePath().normalize();
        if (path.toString().length() > MAX_PATH_LENGTH_FOR_RAW_REF) {
            throw new IllegalArgumentException("CSV path is too long for raw_ref lineage (max "
                    + MAX_PATH_LENGTH_FOR_RAW_REF + "): " + path);
        }
        String rawCharset = trimToNull(p.get(P_CHARSET));
        this.charsetExplicit = rawCharset != null;
        this.charset = rawCharset == null ? StandardCharsets.UTF_8 : parseCharset(rawCharset);
        this.delimiter = parseDelimiter(p.get(P_DELIMITER));
        this.idColumn = require(p, P_ID_COLUMN);
        this.matchKeyColumn = trimToNull(p.get(P_MATCH_KEY_COLUMN));
        this.matchKeyField = defaulted(p.get(P_MATCH_KEY_FIELD),
                matchKeyColumn == null ? "matchKey" : matchKeyColumn);
        this.groupKeyColumn = require(p, P_GROUP_KEY_COLUMN);
        this.groupKeyField = defaulted(p.get(P_GROUP_KEY_FIELD), groupKeyColumn);
        this.currencyColumn = require(p, P_CURRENCY_COLUMN);
        this.amountColumn = require(p, P_AMOUNT_COLUMN);
        this.entryTypeColumn = trimToNull(p.get(P_ENTRY_TYPE_COLUMN));
        this.bizStatusColumn = trimToNull(p.get(P_BIZ_STATUS_COLUMN));
        this.bizTimeColumn = trimToNull(p.get(P_BIZ_TIME_COLUMN));
        this.postingTimeColumn = trimToNull(p.get(P_POSTING_TIME_COLUMN));
    }

    static CsvSourceConfig from(Map<String, String> params) {
        return new CsvSourceConfig(Objects.requireNonNull(params, "params"));
    }

    Set<String> requiredHeaders() {
        Set<String> headers = new LinkedHashSet<>();
        headers.add(idColumn);
        add(headers, matchKeyColumn);
        headers.add(groupKeyColumn);
        headers.add(currencyColumn);
        headers.add(amountColumn);
        add(headers, entryTypeColumn);
        add(headers, bizStatusColumn);
        add(headers, bizTimeColumn);
        add(headers, postingTimeColumn);
        return Set.copyOf(headers);
    }

    private static void add(Set<String> target, String value) {
        if (value != null) {
            target.add(value);
        }
    }

    private static String require(Map<String, String> params, String key) {
        String value = trimToNull(params.get(key));
        if (value == null) {
            throw new IllegalArgumentException("CsvSourceAdapter descriptor missing required param: " + key);
        }
        return value;
    }

    private static String defaulted(String value, String fallback) {
        String normalized = trimToNull(value);
        return normalized == null ? fallback : normalized;
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static Charset parseCharset(String raw) {
        try {
            return Charset.forName(raw);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("unsupported CSV charset: " + raw, invalid);
        }
    }

    private static char parseDelimiter(String raw) {
        if (raw == null || raw.isEmpty()) {
            return ',';
        }
        if (raw.length() != 1) {
            throw new IllegalArgumentException("CSV delimiter must be exactly one character, got: " + raw);
        }
        return raw.charAt(0);
    }
}
