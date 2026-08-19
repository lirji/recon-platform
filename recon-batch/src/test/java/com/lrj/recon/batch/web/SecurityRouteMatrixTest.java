package com.lrj.recon.batch.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A1 secure profile 路由授权矩阵 (对齐 risk-platform {@code SecurityRouteMatrixTest}):
 * 匿名→401;已认证但权限不足→403;带对应 permission→放行 (read=recon.read / launch=recon.launch / dispose=recon.dispose);
 * 且 operator 落库取自 JWT。{@code @MockBean JwtDecoder} 使上下文无 Casdoor 也能加载,{@code .with(jwt())} 绕过解码注入身份。
 * 业务功能测试跑默认 (dev/permitAll) profile,见 {@link DiscrepancyControllerTest}。
 */
@SpringBootTest
@ActiveProfiles("secure")
@AutoConfigureMockMvc
class SecurityRouteMatrixTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @MockBean JwtDecoder jwtDecoder;

    private static final String SCENARIO = "MARKETING_3WAY";
    private static final String PERIOD = "2026-08-20";

    @BeforeEach
    void reset() {
        for (String t : List.of("discrepancy_action", "discrepancy_disposition", "discrepancy",
                "recon_report", "recon_run_seq", "recon_run")) {
            jdbc.update("DELETE FROM " + t);
        }
    }

    @Test
    void anonymousIsUnauthorized() throws Exception {
        mvc.perform(get("/recon/dashboard")).andExpect(status().isUnauthorized());
        mvc.perform(post("/recon/runs").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void readRequiresReconReadPermission() throws Exception {
        mvc.perform(get("/recon/dashboard").with(jwt()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/recon/dashboard").with(jwt().authorities(new SimpleGrantedAuthority("recon.read"))))
                .andExpect(status().isOk());
    }

    @Test
    void launchRequiresReconLaunchPermission() throws Exception {
        // recon.read 不足以发起 → 403。
        mvc.perform(post("/recon/runs").with(jwt().authorities(new SimpleGrantedAuthority("recon.read")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scenarioCode\":\"%s\",\"accountingPeriod\":\"%s\"}".formatted(SCENARIO, PERIOD)))
                .andExpect(status().isForbidden());
        // recon.launch 通过授权 → 到达业务校验 (缺 scenario) → 400, 证明已越过 authz。
        mvc.perform(post("/recon/runs").with(jwt().authorities(new SimpleGrantedAuthority("recon.launch")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountingPeriod\":\"%s\"}".formatted(PERIOD)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void disposeRequiresReconDisposePermission() throws Exception {
        // recon.read 不足以核销 → 403。
        mvc.perform(post("/recon/discrepancies/{id}/resolve", "no-such")
                        .with(jwt().authorities(new SimpleGrantedAuthority("recon.read")))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        // recon.dispose 通过授权 → 到达业务 (差异不存在) → 404, 证明已越过 authz。
        mvc.perform(post("/recon/discrepancies/{id}/resolve", "no-such")
                        .with(jwt().authorities(new SimpleGrantedAuthority("recon.dispose")))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void authMeReturnsIdentityAndPermissionsFromJwt() throws Exception {
        mvc.perform(get("/recon/auth/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/recon/auth/me")
                        .with(jwt().jwt(j -> j.subject("u-1").claim("preferred_username", "alice"))
                                .authorities(new SimpleGrantedAuthority("recon.read"),
                                        new SimpleGrantedAuthority("recon.dispose"),
                                        new SimpleGrantedAuthority("ROLE_OPERATOR"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.name").value("alice"))
                .andExpect(jsonPath("$.permissions", org.hamcrest.Matchers.containsInAnyOrder("recon.read", "recon.dispose")));
    }

    @Test
    void operatorIsDerivedFromJwtClaimNotRequestBody() throws Exception {
        String did = seedDiscrepancy("run-sec-mc", "R".repeat(64));
        // 请求体带一个假 operator, 但 secure 下应被忽略, 落库取 JWT 的 preferred_username。
        mvc.perform(post("/recon/discrepancies/{id}/resolve", did)
                        .with(jwt().jwt(j -> j.claim("preferred_username", "alice"))
                                .authorities(new SimpleGrantedAuthority("recon.dispose")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operator\":\"forged-in-body\",\"note\":\"ok\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.operator").value("alice"));
    }

    /** seed 一个 COMPLETED run + 一条 AMOUNT_MISMATCH 差异, 返回 discrepancy_id。 */
    private String seedDiscrepancy(String runId, String fingerprint) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                INSERT INTO recon_run(run_id, scenario_code, accounting_period, sequence_no, cutoff_time,
                    match_window_from, match_window_to, bucket_count, status, revision, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                """, runId, SCENARIO, PERIOD, 1, now, now, now, 8, "COMPLETED", 4, now, now);
        String did = "disc-" + runId;
        jdbc.update("""
                INSERT INTO discrepancy(discrepancy_id, run_id, segment_id, type, fingerprint,
                    expected_amount_minor, actual_amount_minor, delta_amount_minor, machine_result, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,1,?,?)
                """, did, runId, "SEG1_MKT_ACCT", "AMOUNT_MISMATCH", fingerprint, 1000, 900, 100, now, now);
        return did;
    }
}
