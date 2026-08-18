package com.lrj.recon.batch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * recon-batch 组合根 (Spring Boot 应用)。
 *
 * <p>M1 职责: 装配 JDBC 持久化适配器 ({@code persistence.Jdbc*Store} 实现 recon-core 的
 * {@code application.port.out} 端口) + Flyway 迁移 (V1 领域 schema / V2 Batch 元数据)。
 * <b>本轮不引入 Spring Batch 依赖、不编排 Job</b> (归 M2)。DataSource/Flyway 由 application.yml 驱动 Boot autoconfig。
 */
@SpringBootApplication
public class ReconBatchApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReconBatchApplication.class, args);
    }
}
