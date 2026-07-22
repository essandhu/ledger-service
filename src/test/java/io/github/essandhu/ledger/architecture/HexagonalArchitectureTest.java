package io.github.essandhu.ledger.architecture;

import java.util.Set;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.base.DescribedPredicate.anyElementThat;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noCodeUnits;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * I14: the hexagonal dependency rules (PLAN §3), initial set — extended as packages appear.
 *
 * <p>Uses the ArchUnit <em>core</em> API from plain Jupiter tests: {@code archunit-junit5}'s
 * engine targets JUnit Platform 1.x and does not run on Boot 4.1's Platform 6
 * (TNG/ArchUnit#1556, TEST-STRATEGY §2). Same rules, no engine.
 */
@Tag("architecture")
@DisplayName("I14: hexagonal dependency rules")
class HexagonalArchitectureTest {

    private static final String ROOT = "io.github.essandhu.ledger";

    /*
     * allowEmptyShould(true) below: the hexagonal packages hold only javadoc package-info files
     * until M1/M2, and javac emits no .class file for annotation-free package-info — so several
     * rules currently match zero classes/members. Remove each allowEmptyShould as its package
     * gains real classes, so a future package rename cannot silently vacuify the rule.
     */

    private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages(ROOT);

    private static final DescribedPredicate<JavaClass> FLOATING_POINT =
            new DescribedPredicate<>("a floating-point type (money must be integer minor units, ADR-0001)") {
                private final Set<String> names =
                        Set.of("float", "double", "java.lang.Float", "java.lang.Double");

                @Override
                public boolean test(JavaClass input) {
                    // Base component type so double[], double[][] and double... don't slip through.
                    return names.contains(input.getBaseComponentType().getName());
                }
            };

    @Test
    @DisplayName("domain depends on the JDK only")
    void domain_depends_on_jdk_only() {
        classes().that().resideInAPackage(ROOT + ".domain..")
                .should().onlyDependOnClassesThat()
                .resideInAnyPackage("java..", ROOT + ".domain..")
                .because("the domain core is framework-free (PLAN §3)")
                .allowEmptyShould(true)
                .check(PRODUCTION_CLASSES);
    }

    @Test
    @DisplayName("application's only framework concession is the transaction boundary")
    void application_is_spring_free_except_transactions() {
        classes().that().resideInAPackage(ROOT + ".application..")
                .should().onlyDependOnClassesThat()
                .resideInAnyPackage(
                        "java..",
                        ROOT + ".domain..",
                        ROOT + ".application..",
                        "org.springframework.transaction.annotation..",
                        // Method security on use-case entry points is mandated by PLAN §5;
                        // like @Transactional it is declarative and container-interpreted.
                        "org.springframework.security.access.prepost..")
                .because("declarative transaction and method-security annotations are the only "
                        + "Spring dependencies allowed in the core (PLAN §3, §5)")
                .allowEmptyShould(true)
                .check(PRODUCTION_CLASSES);
    }

    @Test
    @DisplayName("application never reaches into adapters")
    void application_does_not_depend_on_adapters() {
        noClasses().that().resideInAPackage(ROOT + ".application..")
                .should().dependOnClassesThat().resideInAPackage(ROOT + ".adapter..")
                .because("dependencies point inward: adapters implement ports, never the reverse")
                .allowEmptyShould(true)
                .check(PRODUCTION_CLASSES);
    }

    @Test
    @DisplayName("adapters are mutually isolated")
    void adapters_do_not_depend_on_each_other() {
        slices().matching(ROOT + ".adapter.(*)..")
                .should().notDependOnEachOther()
                .because("adapters may only meet through the application core (PLAN §3)")
                .allowEmptyShould(true)
                .check(PRODUCTION_CLASSES);
    }

    @Test
    @DisplayName("JPA and Hibernate stay inside adapter.persistence")
    void jpa_only_in_the_persistence_adapter() {
        noClasses().that().resideOutsideOfPackage(ROOT + ".adapter.persistence..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "jakarta.persistence..",
                        "org.hibernate..",
                        "org.springframework.data.jpa..",
                        "org.springframework.orm..")
                .because("persistence technology is an adapter detail (PLAN §3)")
                .check(PRODUCTION_CLASSES);
    }

    @Test
    @DisplayName("no float/double anywhere in domain or application")
    void no_floating_point_in_the_core() {
        String[] core = {ROOT + ".domain..", ROOT + ".application.."};

        // Dependency-level net first: catches generic type arguments (List<Double>) and any
        // other reference the member-level rules below cannot see.
        noClasses().that().resideInAnyPackage(core)
                .should().dependOnClassesThat(FLOATING_POINT)
                .allowEmptyShould(true)
                .check(PRODUCTION_CLASSES);

        noFields().that().areDeclaredInClassesThat().resideInAnyPackage(core)
                .should().haveRawType(FLOATING_POINT)
                .allowEmptyShould(true)
                .check(PRODUCTION_CLASSES);

        noMethods().that().areDeclaredInClassesThat().resideInAnyPackage(core)
                .should().haveRawReturnType(FLOATING_POINT)
                .allowEmptyShould(true)
                .check(PRODUCTION_CLASSES);

        noCodeUnits().that().areDeclaredInClassesThat().resideInAnyPackage(core)
                .should().haveRawParameterTypes(anyElementThat(FLOATING_POINT))
                .allowEmptyShould(true)
                .check(PRODUCTION_CLASSES);
    }

    @Test
    @DisplayName("no field injection anywhere in production code")
    void no_field_injection() {
        NO_CLASSES_SHOULD_USE_FIELD_INJECTION.allowEmptyShould(true).check(PRODUCTION_CLASSES);
    }

    @Test
    @DisplayName("ground rule (TEST-STRATEGY §1): time flows only through the injected Clock")
    void no_ambient_time_reads() {
        DescribedPredicate<JavaMethodCall> ambientTimeRead =
                new DescribedPredicate<>("read ambient time (zero-arg now(), currentTimeMillis, "
                        + "nanoTime, Calendar.getInstance)") {
                    @Override
                    public boolean test(JavaMethodCall call) {
                        var target = call.getTarget();
                        String owner = target.getOwner().getName();
                        if (target.getName().equals("now")
                                && target.getOwner().getPackageName().startsWith("java.time")
                                && target.getRawParameterTypes().isEmpty()) {
                            return true;
                        }
                        if (owner.equals("java.lang.System")
                                && (target.getName().equals("currentTimeMillis")
                                        || target.getName().equals("nanoTime"))) {
                            return true;
                        }
                        return owner.equals("java.util.Calendar")
                                && target.getName().equals("getInstance");
                    }
                };

        noClasses().should().callMethodWhere(ambientTimeRead)
                .because("time must be injectable and controllable in tests (TEST-STRATEGY §1)")
                .check(PRODUCTION_CLASSES);

        DescribedPredicate<JavaConstructorCall> zeroArgDate =
                new DescribedPredicate<>("instantiate java.util.Date at the current instant") {
                    @Override
                    public boolean test(JavaConstructorCall call) {
                        return call.getTarget().getOwner().getName().equals("java.util.Date")
                                && call.getTarget().getRawParameterTypes().isEmpty();
                    }
                };

        noClasses().should().callConstructorWhere(zeroArgDate)
                .because("time must be injectable and controllable in tests (TEST-STRATEGY §1)")
                .check(PRODUCTION_CLASSES);

        // System clocks may be constructed only where the Clock bean lives.
        DescribedPredicate<JavaMethodCall> systemClockConstruction =
                new DescribedPredicate<>("construct a system Clock") {
                    @Override
                    public boolean test(JavaMethodCall call) {
                        return call.getTarget().getOwner().getName().equals("java.time.Clock")
                                && call.getTarget().getName().startsWith("system");
                    }
                };

        noClasses().that().resideOutsideOfPackage(ROOT + ".config..")
                .should().callMethodWhere(systemClockConstruction)
                .because("the config package is the single home of the Clock bean (PLAN §3.1)")
                .check(PRODUCTION_CLASSES);
    }
}
