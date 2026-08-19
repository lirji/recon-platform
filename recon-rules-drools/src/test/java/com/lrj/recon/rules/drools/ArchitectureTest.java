package com.lrj.recon.rules.drools;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * recon-rules-drools 门禁: Drools 判差策略层是外圈规则库, <b>允许</b> {@code org.kie..}/{@code org.drools..}
 * (这正是它存在的理由), 但仍<b>禁</b> Spring/Batch/JDBC/CSV/Flowable/JPA —— 框架装配 (Spring @Bean、
 * EvaluatorResolver) 归组合根 recon-batch, 本模块保持纯规则库。只依赖 recon-core, 不横向依赖其它平台模块。
 */
@AnalyzeClasses(packages = "com.lrj.recon.rules.drools", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule rules_module_stays_framework_free_except_drools = noClasses()
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..",
                    "org.springframework.batch..",
                    "java.sql..",
                    "com.opencsv..",
                    "org.apache.commons.csv..",
                    "org.flowable..",
                    "jakarta.persistence..")
            .as("recon-rules-drools 允许 Drools/KIE, 但框架装配 (Spring/Batch/JDBC) 归组合根 recon-batch");

    @ArchTest
    static final ArchRule rules_module_only_depends_on_core = noClasses()
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.lrj.recon.batch..",
                    "com.lrj.recon.source..",
                    "com.lrj.recon.scenario..",
                    "com.lrj.recon.handler..")
            .as("recon-rules-drools 只依赖 recon-core, 不横向依赖 batch/source/scenario/handler");
}
