package com.lrj.recon.scenario;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * recon-scenario 纯度门禁 (与 recon-core 看齐): 场景装配是纯 Java 领域件, 只依赖 recon-core,
 * 禁依赖 Spring/Batch/JDBC/Drools/CSV/Flowable/JPA。框架 (Spring Batch / JdbcTemplate) 装配归组合根 recon-batch。
 */
@AnalyzeClasses(packages = "com.lrj.recon.scenario", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule scenario_is_framework_free = noClasses()
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
            .as("recon-scenario 场景装配零框架, 只依赖 recon-core (框架装配归组合根 recon-batch)");
}
