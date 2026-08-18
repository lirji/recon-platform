package com.lrj.recon.source.db;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * recon-source-db 门禁: 外圈适配器只依赖 recon-core (端口/SPI/领域) + spring-jdbc;
 * 禁依赖 Batch/Drools/CSV/Flowable/JPA 及 recon-batch 组合根。
 */
@AnalyzeClasses(packages = "com.lrj.recon.source.db", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule adapter_stays_off_other_frameworks = noClasses()
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework.batch..",
                    "org.kie..",
                    "org.drools..",
                    "com.opencsv..",
                    "org.apache.commons.csv..",
                    "org.flowable..",
                    "jakarta.persistence..")
            .as("db 源适配器禁依赖 Batch/Drools/CSV/Flowable/JPA");

    @ArchTest
    static final ArchRule adapter_does_not_depend_on_composition_root = noClasses()
            .should().dependOnClassesThat().resideInAnyPackage("..batch..", "..source.csv..")
            .as("外圈适配器互不横向依赖, 也不依赖 recon-batch 组合根");
}
