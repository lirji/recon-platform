package com.lrj.recon.batch;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * recon-batch (组合根) 门禁:
 * <ul>
 *   <li>JDBC (spring.jdbc / java.sql) 只允许出现在 {@code ..persistence..} / {@code ..config..} (装配), 不散落;
 *       job 层的 reader/writer/tasklet 走 recon-core 端口, 不直接碰 JDBC;</li>
 *   <li>M2 起<b>允许</b> Spring Batch, 但限定在 {@code ..job..} / {@code ..config..} (Job/Step 编排 + 组件),
 *       不泄漏到 {@code ..persistence..};</li>
 *   <li>CSV 解析实现留在 recon-source-csv，组合根只依赖其公开适配器；Drools / Flowable 仍未引入。</li>
 * </ul>
 */
@AnalyzeClasses(packages = "com.lrj.recon.batch", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule jdbc_confined_to_persistence_and_config = noClasses()
            .that().resideOutsideOfPackages("..persistence..", "..config..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework.jdbc..", "java.sql..")
            .as("JDBC 只在持久化适配器 / 装配层出现, job 层经端口访问 DB 不直接碰 JDBC");

    @ArchTest
    static final ArchRule spring_batch_confined_to_job_and_config = noClasses()
            .that().resideOutsideOfPackages("..job..", "..config..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework.batch..")
            .as("Spring Batch 限定在 job/config 编排层, 不泄漏到其它包");

    @ArchTest
    static final ArchRule composition_root_does_not_parse_csv_or_pull_rule_workflow_frameworks = noClasses()
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.kie..",
                    "org.drools..",
                    "com.opencsv..",
                    "org.apache.commons.csv..",
                    "org.flowable..")
            .as("组合根不直接解析 CSV，且未引入 Drools/Flowable");
}
