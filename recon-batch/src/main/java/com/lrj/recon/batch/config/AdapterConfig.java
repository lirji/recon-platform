package com.lrj.recon.batch.config;

import com.lrj.recon.source.db.DbSourceAdapter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 组合根装配: 把外圈源适配器接入 Spring 容器 (recon-source-db 本身零 Spring 注解, 由此处 @Bean 化)。
 * Jdbc*Store 持久化适配器则用 @Repository 组件扫描, 不在此重复声明。
 */
@Configuration
public class AdapterConfig {

    @Bean
    public DbSourceAdapter dbSourceAdapter(JdbcTemplate jdbcTemplate) {
        return new DbSourceAdapter(jdbcTemplate);
    }
}
