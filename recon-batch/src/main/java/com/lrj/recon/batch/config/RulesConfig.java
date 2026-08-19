package com.lrj.recon.batch.config;

import com.lrj.recon.core.spi.DroolsEvaluator;
import com.lrj.recon.rules.drools.DroolsDiscrepancyEvaluator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * B2 Drools 判差装配。
 *
 * <p>{@link DroolsEvaluator} bean 仅在 {@code recon.rules.drools.enabled=true} 时创建 (默认关, 不付启动开销);
 * 编译在构造期完成, DRL 有误则应用启动即 fail-fast (红线)。可选 {@code recon.rules.drools.extra-classpath}
 * 追加一份 ops 自定义 DRL (classpath 资源), 叠加在默认规则集之上。
 *
 * <p>{@link EvaluatorResolver} 恒在, 用 {@link ObjectProvider} 注入 Drools bean (未启用则为 null),
 * 使 EXACT/TOLERANCE 路径完全不依赖 Drools。
 */
@Configuration(proxyBeanMethods = false)
public class RulesConfig {

    @Bean
    @ConditionalOnProperty(prefix = "recon.rules.drools", name = "enabled", havingValue = "true")
    public DroolsEvaluator droolsEvaluator(
            @Value("${recon.rules.drools.extra-classpath:}") String extraClasspath) {
        if (extraClasspath == null || extraClasspath.isBlank()) {
            return DroolsDiscrepancyEvaluator.withDefaultRules();
        }
        return DroolsDiscrepancyEvaluator.withDefaultAnd(loadClasspath(extraClasspath.trim()));
    }

    @Bean
    public EvaluatorResolver evaluatorResolver(ObjectProvider<DroolsEvaluator> droolsEvaluator) {
        return new EvaluatorResolver(droolsEvaluator.getIfAvailable());
    }

    private static String loadClasspath(String path) {
        try (InputStream in = RulesConfig.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("recon.rules.drools.extra-classpath not found: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("failed reading extra DRL: " + path, e);
        }
    }
}
