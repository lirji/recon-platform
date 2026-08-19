package com.lrj.recon.batch.web;

import com.lrj.recon.batch.service.ScenarioDefinitionCodec;
import com.lrj.recon.batch.service.ScenarioDefinitionCodecTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** B4 · 场景定义管理 API 契约(H2/MockMvc,dev profile permitAll)。 */
@SpringBootTest
@AutoConfigureMockMvc
class ScenarioAdminControllerTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ScenarioDefinitionCodec codec;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM recon_scenario_def");
    }

    @Test
    void put_then_list_and_get() throws Exception {
        String json = codec.toJson(ScenarioDefinitionCodecTest.sample("S1"));

        mvc.perform(put("/recon/scenarios/S1").param("enabled", "true")
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("S1"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.enabled").value(true));

        mvc.perform(get("/recon/scenarios"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("S1"))
                .andExpect(jsonPath("$[0].segmentCount").value(1));

        mvc.perform(get("/recon/scenarios/S1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.definition.segments[0].id").value("SEG_A"));
    }

    @Test
    void get_missing_is_404() throws Exception {
        mvc.perform(get("/recon/scenarios/NOPE")).andExpect(status().isNotFound());
    }

    @Test
    void put_with_mismatched_code_is_400() throws Exception {
        String json = codec.toJson(ScenarioDefinitionCodecTest.sample("S1"));
        mvc.perform(put("/recon/scenarios/OTHER")
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest());
    }
}
