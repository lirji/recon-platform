package com.lrj.recon.source.csv;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/** CSV 外圈适配器只能依赖 core 和 CSV 解析库，禁止横向依赖其它模块或框架。 */
@AnalyzeClasses(packages = "com.lrj.recon.source.csv", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule csv_adapter_stays_framework_free = noClasses()
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..",
                    "org.springframework.batch..",
                    "org.kie..",
                    "org.drools..",
                    "java.sql..",
                    "org.flowable..",
                    "jakarta.persistence..")
            .as("CSV 数据源只依赖 recon-core + Apache Commons CSV");

    @ArchTest
    static final ArchRule csv_adapter_does_not_depend_on_sibling_modules = noClasses()
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..source.db..", "..batch..", "..scenario..", "..handler..")
            .as("外圈 CSV 适配器不横向依赖 DB/scenario/handler 或组合根");
}
