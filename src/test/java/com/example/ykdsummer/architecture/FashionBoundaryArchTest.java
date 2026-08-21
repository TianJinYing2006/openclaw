package com.example.ykdsummer.architecture;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * 需求三 Full-B：用 ArchUnit 在测试层强制两套 fashion 子系统的边界契约（详见 docs/architecture/FASHION_BOUNDARIES.md）。
 *
 * <p>只扫描 main 源码（{@link ImportOption.DoNotIncludeTests}），生产代码的依赖方向是契约红线；
 * 测试类之间的耦合由单独的重构（接口化 / 测试归位）处理，不在此处约束。</p>
 */
@AnalyzeClasses(packages = "com.example.ykdsummer", importOptions = ImportOption.DoNotIncludeTests.class)
public class FashionBoundaryArchTest {

    /** 规则二（核心）：衣橱引擎(B) 严禁依赖 Look 引擎(A)——含 ai.fashion 根及其所有子包。 */
    @ArchTest
    static final ArchRule wardrobeMustNotDependOnLookEngine =
            noClasses().that().resideInAPackage("..fashion.wardrobe..")
                    .should().dependOnClassesThat(resideInAPackage("..ai.fashion.."));

    /** 共用层中性：common.fashion 不得依赖 Look 引擎(A) 或衣橱引擎(B)。 */
    @ArchTest
    static final ArchRule commonFashionMustNotDependOnEitherEngine =
            noClasses().that().resideInAPackage("..common.fashion..")
                    .should().dependOnClassesThat(
                            resideInAnyPackage("..ai.fashion..", "..fashion.wardrobe.."));

    /** 重命名后 ai.fashion 下只允许存在 look 子包，杜绝旧包根（ai.fashion.X）残留导致的隐性耦合。 */
    @ArchTest
    static final ArchRule aiFashionRootMustOnlyContainLook =
            classes().that().resideInAPackage("..ai.fashion..")
                    .should().resideInAPackage("..ai.fashion.look..");
}
