package io.github.essandhu.ledger.architecture;

import java.util.Set;

import jakarta.persistence.Table;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.hibernate.annotations.Immutable;
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
                .check(PRODUCTION_CLASSES);
    }

    @Test
    @DisplayName("application never reaches into adapters")
    void application_does_not_depend_on_adapters() {
        noClasses().that().resideInAPackage(ROOT + ".application..")
                .should().dependOnClassesThat().resideInAPackage(ROOT + ".adapter..")
                .because("dependencies point inward: adapters implement ports, never the reverse")
                .check(PRODUCTION_CLASSES);
    }

    @Test
    @DisplayName("adapters are mutually isolated")
    void adapters_do_not_depend_on_each_other() {
        slices().matching(ROOT + ".adapter.(*)..")
                .should().notDependOnEachOther()
                .because("adapters may only meet through the application core (PLAN §3)")
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
                .check(PRODUCTION_CLASSES);

        noFields().that().areDeclaredInClassesThat().resideInAnyPackage(core)
                .should().haveRawType(FLOATING_POINT)
                .check(PRODUCTION_CLASSES);

        noMethods().that().areDeclaredInClassesThat().resideInAnyPackage(core)
                .should().haveRawReturnType(FLOATING_POINT)
                .check(PRODUCTION_CLASSES);

        noCodeUnits().that().areDeclaredInClassesThat().resideInAnyPackage(core)
                .should().haveRawParameterTypes(anyElementThat(FLOATING_POINT))
                .check(PRODUCTION_CLASSES);
    }

    @Test
    @DisplayName("I3 (layer 2): entities mapping the append-only journal tables are @Immutable")
    void journal_tables_map_through_immutable_entities() {
        // Keyed on the physical table name, not the entity class name: a renamed or additional
        // entity mapping journal_entry/posting must still carry the annotation. ArchUnit's
        // default fail-on-empty-should keeps this rule from passing vacuously if the entities
        // are ever moved or renamed away.
        DescribedPredicate<JavaClass> mapsAppendOnlyTable =
                new DescribedPredicate<>("map the append-only tables journal_entry or posting") {
                    private final Set<String> tables = Set.of("journal_entry", "posting");

                    @Override
                    public boolean test(JavaClass input) {
                        return input.tryGetAnnotationOfType(Table.class)
                                .map(table -> tables.contains(table.name()))
                                .orElse(false);
                    }
                };

        classes().that(mapsAppendOnlyTable)
                .should().beAnnotatedWith(Immutable.class)
                .because("Hibernate must never dirty-check or UPDATE journal rows — the ORM layer "
                        + "of the append-only guarantee (I3, PLAN §4.4; layers 1 and 3 are the "
                        + "mutator-free domain records and the absent UPDATE/DELETE grants)")
                .check(PRODUCTION_CLASSES);
    }

    @Test
    @DisplayName("micrometer stays in adapters and config — never in the core")
    void micrometer_only_in_adapters_and_config() {
        // application_is_spring_free_except_transactions deliberately has NO micrometer
        // allowance: posting metrics live in the config-package decorator and the lock-wait
        // timer in the persistence adapter (PLAN §8). This rule states that placement decision
        // positively, so a future "just inject MeterRegistry into the service" shortcut fails
        // here with the reason attached rather than only tripping the package-list rule above.
        noClasses().that().resideOutsideOfPackages(ROOT + ".adapter..", ROOT + ".config..")
                .should().dependOnClassesThat().resideInAPackage("io.micrometer..")
                .because("metrics are infrastructure: the domain and application core stay "
                        + "micrometer-free; instrumentation wraps the core from config/adapters "
                        + "(PLAN §3, §8)")
                .check(PRODUCTION_CLASSES);
    }

    @Test
    @DisplayName("no field injection anywhere in production code")
    void no_field_injection() {
        NO_CLASSES_SHOULD_USE_FIELD_INJECTION.check(PRODUCTION_CLASSES);
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
