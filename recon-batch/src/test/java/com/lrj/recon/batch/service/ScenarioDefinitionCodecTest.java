package com.lrj.recon.batch.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.recon.core.domain.model.SourceRole;
import com.lrj.recon.scenario.dsl.ScenarioDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B4 · 场景定义 JSON 编解码 + 装配校验。
 */
public class ScenarioDefinitionCodecTest {

    private final ScenarioDefinitionCodec codec = new ScenarioDefinitionCodec(new ObjectMapper());

    public static ScenarioDefinition sample(String code) {
        return new ScenarioDefinition(code, List.of(
                new ScenarioDefinition.Segment("SEG_A", SourceRole.MARKETING, SourceRole.ACCOUNTING,
                        SourceRole.ACCOUNTING, "SEG1", "issueId", "orderNo",
                        new ScenarioDefinition.Source("db", Map.of("table", "t_mkt", "matchKeyColumn", "issue_id")),
                        new ScenarioDefinition.Source("db", Map.of("table", "t_acc", "matchKeyColumn", "issue_id")),
                        ScenarioDefinition.Rule.exact())));
    }

    public static ScenarioDefinition duplicateSegmentIds() {
        ScenarioDefinition.Segment seg = new ScenarioDefinition.Segment("SEG_X", SourceRole.MARKETING,
                SourceRole.ACCOUNTING, SourceRole.ACCOUNTING, "SEG1", "k", "k",
                new ScenarioDefinition.Source("db", Map.of("table", "t")),
                new ScenarioDefinition.Source("db", Map.of("table", "t")), ScenarioDefinition.Rule.exact());
        return new ScenarioDefinition("BAD", List.of(seg, seg));
    }

    @Test
    void round_trips_through_json() {
        ScenarioDefinition def = sample("ROUNDTRIP");
        String json = codec.toJson(def);
        assertThat(codec.fromJson(json)).isEqualTo(def);
    }

    @Test
    void from_json_fails_fast_on_structurally_invalid_definition() {
        String json = codec.toJson(duplicateSegmentIds()); // 合法 JSON, 但装配非法 (重复段)
        assertThatThrownBy(() -> codec.fromJson(json)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void from_json_fails_fast_on_malformed_json() {
        assertThatThrownBy(() -> codec.fromJson("{not json")).isInstanceOf(IllegalArgumentException.class);
    }
}
