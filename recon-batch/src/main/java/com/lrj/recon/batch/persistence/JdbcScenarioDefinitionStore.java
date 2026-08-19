package com.lrj.recon.batch.persistence;

import com.lrj.recon.batch.service.ScenarioDefinitionCodec;
import com.lrj.recon.batch.service.ScenarioDefinitionStore;
import com.lrj.recon.scenario.dsl.ScenarioDefinition;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * {@link ScenarioDefinitionStore} 的 JDBC 实现 (B4)。JSON 序列化经 {@link ScenarioDefinitionCodec} (装配校验),
 * 结构不合法即 fail-fast 不入库;可移植 update-else-insert upsert (不依赖 MySQL {@code ON DUPLICATE KEY})。
 */
@Repository
public class JdbcScenarioDefinitionStore implements ScenarioDefinitionStore {

    private final JdbcTemplate jdbc;
    private final ScenarioDefinitionCodec codec;
    private final RowMapper<Stored> rowMapper;

    public JdbcScenarioDefinitionStore(JdbcTemplate jdbc, ScenarioDefinitionCodec codec) {
        this.jdbc = jdbc;
        this.codec = codec;
        this.rowMapper = (rs, i) -> new Stored(
                codec.fromJson(rs.getString("definition_json")),
                rs.getInt("version"),
                rs.getInt("enabled") == 1);
    }

    @Override
    public void save(ScenarioDefinition definition, boolean enabled) {
        codec.validate(definition);                 // fail-fast: 坏定义不入库
        String json = codec.toJson(definition);
        Timestamp now = Timestamp.from(Instant.now());
        int updated = jdbc.update(
                "UPDATE recon_scenario_def SET version = version + 1, definition_json = ?, enabled = ?, updated_at = ?"
                + " WHERE code = ?",
                json, enabled ? 1 : 0, now, definition.code());
        if (updated == 0) {
            jdbc.update(
                    "INSERT INTO recon_scenario_def(code, version, definition_json, enabled, created_at, updated_at)"
                    + " VALUES (?,?,?,?,?,?)",
                    definition.code(), 1, json, enabled ? 1 : 0, now, now);
        }
    }

    @Override
    public Optional<Stored> find(String code) {
        List<Stored> rows = jdbc.query(
                "SELECT code, version, definition_json, enabled FROM recon_scenario_def WHERE code = ?",
                rowMapper, code);
        return rows.stream().findFirst();
    }

    @Override
    public List<Stored> list() {
        return jdbc.query(
                "SELECT code, version, definition_json, enabled FROM recon_scenario_def ORDER BY code",
                rowMapper);
    }
}
