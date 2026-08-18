package com.lrj.recon.batch.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 调度装配 (M5): 仅当 {@code recon.scheduler.enabled=true} 时启用 Spring 调度 ({@code @EnableScheduling})。
 * 默认关 —— 与 {@link com.lrj.recon.batch.job.ReconScheduler} 同一开关, 使测试/默认部署无任何后台定时触发。
 */
@Configuration
@ConditionalOnProperty(name = "recon.scheduler.enabled", havingValue = "true")
@EnableScheduling
public class SchedulingConfig {
}
