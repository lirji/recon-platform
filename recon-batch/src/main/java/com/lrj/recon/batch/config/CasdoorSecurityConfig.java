package com.lrj.recon.batch.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

import java.util.List;

/**
 * A1 认证鉴权 (secure profile):recon-batch 作为 OAuth2 <b>Resource Server</b>,校验 auth-platform (Casdoor) 签发的 JWT。
 * 对齐 risk-platform {@code CasdoorSecurityConfig} 范式。
 *
 * <ul>
 *   <li><b>强 JWT 边界</b>:JWKS 验签 + 默认时间戳/issuer 校验 + 边界校验 (audience allowlist + owner=组织 + 非空 sub);</li>
 *   <li><b>无状态</b>:{@code STATELESS} + 关 CSRF (Bearer 无 Cookie);CORS 不配 (console 与 {@code /recon} 同源);</li>
 *   <li><b>授权矩阵</b> (设计 §6.3,按 <b>permissions</b> 判,Casdoor 是角色→权限唯一真相源):
 *       发起/重跑 = {@code recon.launch};核销/关闭 = {@code recon.dispose};读 = {@code recon.read};</li>
 *   <li>401/403 统一 JSON 错误体 ({@link SecurityErrorWriter})。</li>
 * </ul>
 *
 * <p>本地/测试默认 profile 走 {@link DevSecurityConfig} (permitAll),无需 Casdoor。生产 {@code SPRING_PROFILES_ACTIVE=secure}
 * 并配 {@code recon.auth.*}。Spring Security 不受 recon-batch ArchUnit 门禁约束 (门禁只限 JDBC/Batch/CSV)。
 */
@Configuration
@Profile("secure")
@EnableConfigurationProperties(ReconAuthProperties.class)
public class CasdoorSecurityConfig {

    @Bean
    CasdoorAuthorityMapper casdoorAuthorityMapper() {
        return new CasdoorAuthorityMapper();
    }

    @Bean
    JwtDecoder jwtDecoder(ReconAuthProperties props) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(props.getJwkSetUri()).build();
        OAuth2TokenValidator<Jwt> standard = JwtValidators.createDefaultWithIssuer(props.getIssuer());
        OAuth2TokenValidator<Jwt> boundary = jwt -> validateBoundary(jwt, props);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(standard, boundary));
        return decoder;
    }

    @Bean
    SecurityFilterChain secureSecurityFilterChain(HttpSecurity http, CasdoorAuthorityMapper authorityMapper)
            throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info", "/error").permitAll()
                        // 当前会话身份:任意已认证用户可读 (前端据此做权限门控)。
                        .requestMatchers("/recon/auth/**").authenticated()
                        // 发起 / 重跑 Run → recon.launch (admin)。
                        .requestMatchers(HttpMethod.POST, "/recon/runs").hasAuthority("recon.launch")
                        .requestMatchers(HttpMethod.POST, "/recon/runs/*/rerun").hasAuthority("recon.launch")
                        // 人工核销 / 关闭 → recon.dispose (operator/admin)。
                        .requestMatchers(HttpMethod.POST, "/recon/discrepancies/*/resolve").hasAuthority("recon.dispose")
                        .requestMatchers(HttpMethod.POST, "/recon/discrepancies/*/close").hasAuthority("recon.dispose")
                        // B4 场景定义写入(配置驱动平台)→ recon.launch (admin);读走下方 recon.read 兜底。
                        .requestMatchers(HttpMethod.PUT, "/recon/scenarios/**").hasAuthority("recon.launch")
                        .requestMatchers(HttpMethod.POST, "/recon/scenarios/**").hasAuthority("recon.launch")
                        // B5 冲正审批(提交/审批)→ recon.dispose;读待办走下方 recon.read 兜底。
                        .requestMatchers(HttpMethod.POST, "/recon/reversal-approvals/**").hasAuthority("recon.dispose")
                        // B3 冲正执行(真实资金动作)→ recon.launch(最高权限,与审批独立控制点)。
                        .requestMatchers(HttpMethod.POST, "/recon/reversal-executions/**").hasAuthority("recon.launch")
                        .requestMatchers(HttpMethod.POST, "/recon/benefit-ods/**").hasAuthority("recon.launch")
                        .requestMatchers(HttpMethod.POST, "/recon/benefit-remediations/**").hasAuthority("recon.dispose")
                        // 其余 /recon/** (dashboard/runs/discrepancies 读 + 报表 + 场景读) → recon.read (viewer+)。
                        .requestMatchers("/recon/**").hasAuthority("recon.read")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(authorityMapper.jwtAuthenticationConverter())))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((req, resp, ex) ->
                                SecurityErrorWriter.write(resp, 401, "unauthenticated", "需要登录"))
                        .accessDeniedHandler((req, resp, ex) ->
                                SecurityErrorWriter.write(resp, 403, "forbidden", "权限不足")))
                .build();
    }

    /** 边界校验:audience allowlist + owner=组织 + 非空 sub (对齐 risk validateBoundary)。 */
    static OAuth2TokenValidatorResult validateBoundary(Jwt jwt, ReconAuthProperties props) {
        List<String> audiences = props.getAudiences();
        boolean audienceAllowed = audiences != null && !audiences.isEmpty()
                && jwt.getAudience().stream().anyMatch(audiences::contains);
        if (!audienceAllowed) {
            return invalid("access token audience 不属于 recon-platform");
        }
        if (!props.getOrganization().equals(jwt.getClaimAsString("owner"))) {
            return invalid("access token owner 不是 " + props.getOrganization());
        }
        if (jwt.getSubject() == null || jwt.getSubject().isBlank()) {
            return invalid("access token 缺少 sub");
        }
        return OAuth2TokenValidatorResult.success();
    }

    private static OAuth2TokenValidatorResult invalid(String message) {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", message, null));
    }
}
