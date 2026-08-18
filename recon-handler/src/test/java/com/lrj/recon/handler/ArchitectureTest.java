package com.lrj.recon.handler;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * recon-handler 纯度门禁 (与 recon-core / recon-scenario 看齐): 处理链是纯 Java 领域件, 只依赖 recon-core
 * (SPI + 端口 + 领域模型), 禁依赖 Spring/Batch/JDBC/Drools/CSV/Flowable/JPA。
 *
 * <p>尤其: <b>Flowable 工单阶段二才接</b> —— 结构性证明 {@link FlowableTicketHandler} 仅 no-op 占位, 未引入
 * 任何 {@code org.flowable..}。框架 (Spring / JdbcTemplate) 装配与 Jdbc*Store 归组合根 recon-batch。
 */
@AnalyzeClasses(packages = "com.lrj.recon.handler", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule handler_is_framework_free = noClasses()
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..",
                    "org.springframework.batch..",
                    "org.kie..",
                    "org.drools..",
                    "java.sql..",
                    "com.opencsv..",
                    "org.apache.commons.csv..",
                    "org.flowable..",
                    "jakarta.persistence..")
            .as("recon-handler 处理链零框架, 只依赖 recon-core (框架装配 + Jdbc*Store 归组合根 recon-batch)");

    @ArchTest
    static final ArchRule handler_does_not_depend_on_other_platform_modules = noClasses()
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.lrj.recon.batch..",
                    "com.lrj.recon.source..",
                    "com.lrj.recon.scenario..")
            .as("recon-handler 只依赖 recon-core, 不横向依赖 batch/source/scenario 等平台模块 (与 recon-source-db 对等)");
}
