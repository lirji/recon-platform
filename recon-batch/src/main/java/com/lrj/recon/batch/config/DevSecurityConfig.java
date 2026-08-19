package com.lrj.recon.batch.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * A1 认证鉴权 (非 secure profile:本地/dev/测试):permitAll,无需 Casdoor。
 * 对齐 risk-platform {@code DevSecurityConfig}——保持「本地免 Docker 可跑绿」,业务测试无需注入身份。
 * 生产用 {@code SPRING_PROFILES_ACTIVE=secure} 切到 {@link CasdoorSecurityConfig} 强鉴权。
 */
@Configuration
@Profile("!secure")
public class DevSecurityConfig {

    @Bean
    SecurityFilterChain devSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()))
                .build();
    }
}
