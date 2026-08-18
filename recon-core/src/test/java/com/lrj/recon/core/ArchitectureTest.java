package com.lrj.recon.core;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * recon-core 纯度门禁 (照搬 fraud-engine ArchitectureTest 模式)。
 *
 * <p>领域内核零框架: {@code ..domain..} / {@code ..spi..} / {@code ..application..} (持久化端口)
 * 禁依赖 Spring/Batch/Drools/JDBC/CSV/Flowable/JPA 及 adapter/batch 外圈;
 * 额外一条: 金额路径字段禁 {@code double/Double} (ADR-5)。
 */
@AnalyzeClasses(packages = "com.lrj.recon.core", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule domain_spi_and_ports_are_framework_free = noClasses()
            .that().resideInAnyPackage("..domain..", "..spi..", "..application..")
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
            .as("recon-core 领域内核 + 持久化端口禁依赖框架 (Spring/Batch/Drools/JDBC/CSV/Flowable/JPA)");

    @ArchTest
    static final ArchRule core_does_not_depend_on_outer_ring = noClasses()
            .that().resideInAnyPackage("..domain..", "..spi..", "..application..")
            .should().dependOnClassesThat().resideInAnyPackage("..adapter..", "..batch..")
            .as("依赖方向只指向内核, 内核 (含端口) 不依赖外圈适配器/批处理");

    @ArchTest
    static final ArchRule money_path_forbids_double = fields()
            .that().areDeclaredInClassesThat().resideInAnyPackage("..domain..")
            .should().notHaveRawType(double.class)
            .andShould().notHaveRawType(Double.class)
            .as("金额红线: ..domain.. 内任何字段禁 double/Double (ADR-5, 由包规则兜底而非白名单)");
}
