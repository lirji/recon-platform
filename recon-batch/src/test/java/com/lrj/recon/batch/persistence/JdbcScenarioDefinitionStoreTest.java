package com.lrj.recon.batch.persistence;

import com.lrj.recon.batch.service.ScenarioDefinitionCodecTest;
import com.lrj.recon.batch.service.ScenarioDefinitionStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B4 · 场景定义 JDBC 存储 (H2)。upsert / find / list / 版本自增 / 坏定义 fail-fast 不入库。
 */
@SpringBootTest
class JdbcScenarioDefinitionStoreTest {

    @Autowired
    ScenarioDefinitionStore store;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM recon_scenario_def");
    }

    @Test
    void saves_and_finds_by_code() {
        store.save(ScenarioDefinitionCodecTest.sample("S1"), true);

        Optional<ScenarioDefinitionStore.Stored> found = store.find("S1");
        assertThat(found).isPresent();
        assertThat(found.get().version()).isEqualTo(1);
        assertThat(found.get().enabled()).isTrue();
        assertThat(found.get().definition()).isEqualTo(ScenarioDefinitionCodecTest.sample("S1"));
        assertThat(store.find("nope")).isEmpty();
    }

    @Test
    void upsert_increments_version_and_updates_enabled() {
        store.save(ScenarioDefinitionCodecTest.sample("S2"), true);
        store.save(ScenarioDefinitionCodecTest.sample("S2"), false);

        ScenarioDefinitionStore.Stored s = store.find("S2").orElseThrow();
        assertThat(s.version()).isEqualTo(2);
        assertThat(s.enabled()).isFalse();
    }

    @Test
    void lists_all_ordered_by_code() {
        store.save(ScenarioDefinitionCodecTest.sample("B"), true);
        store.save(ScenarioDefinitionCodecTest.sample("A"), false);
        assertThat(store.list()).extracting(x -> x.definition().code()).containsExactly("A", "B");
    }

    @Test
    void bad_definition_fails_fast_and_persists_nothing() {
        assertThatThrownBy(() -> store.save(ScenarioDefinitionCodecTest.duplicateSegmentIds(), true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(store.find("BAD")).isEmpty();
    }
}
