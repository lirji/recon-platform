package com.lrj.recon.workflow.flowable;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * recon-workflow-flowable 门禁:工作流模块<b>允许</b> {@code org.flowable..}(存在理由),但禁 Spring/Batch/JDBC/CSV/Drools
 * ——Spring 装配(条件引擎 bean)归组合根 recon-batch。只依赖 recon-core,不横向依赖其它平台模块。
 */
@AnalyzeClasses(packages = "com.lrj.recon.workflow.flowable", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule workflow_module_stays_framework_free_except_flowable = noClasses()
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..",
                    "org.springframework.batch..",
                    "java.sql..",
                    "com.opencsv..",
                    "org.apache.commons.csv..",
                    "org.kie..",
                    "org.drools..")
            .as("recon-workflow-flowable 允许 Flowable, 但框架装配 (Spring/Batch/JDBC) 归组合根 recon-batch");

    @ArchTest
    static final ArchRule workflow_module_only_depends_on_core = noClasses()
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.lrj.recon.batch..",
                    "com.lrj.recon.source..",
                    "com.lrj.recon.scenario..",
                    "com.lrj.recon.handler..",
                    "com.lrj.recon.rules..")
            .as("recon-workflow-flowable 只依赖 recon-core");
}
