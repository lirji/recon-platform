package com.lrj.recon.batch.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

/**
 * JDBC 值转换小工具 (null-safe): {@link Instant} ⇄ {@link Timestamp}, 及可空数值读取。
 */
final class SqlTimes {

    private SqlTimes() {
    }

    static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    static Long longOrNull(ResultSet rs, String column) throws SQLException {
        Object v = rs.getObject(column);
        return (v instanceof Number n) ? n.longValue() : null;
    }
}
