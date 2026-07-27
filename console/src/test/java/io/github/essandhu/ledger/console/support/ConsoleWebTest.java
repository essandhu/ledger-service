package io.github.essandhu.ledger.console.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

/**
 * The one way to write a console web test — the console's mirror of the core's
 * {@code @LedgerIntegrationTest} discipline: full context (the PRODUCTION security chain on
 * the tested path, not a slice's default), MockMvc, and {@link TestClientRegistrations}
 * standing in for the property-driven registration so no Keycloak is needed.
 *
 * <p>A {@code @WebMvcTest} slice was considered and rejected (recorded at M8a): the slice
 * excludes the production {@code SecurityFilterChain} unless the config class goes public
 * for {@code @Import}, and it still needs a registration bean — while this context is
 * container-free and loads in seconds, so the slice's speed win doesn't exist here.
 *
 * <p>Keep the annotation set identical across console tests: the Spring context cache keys
 * on the merged configuration, so any deviation forks a second context. Two deviations are
 * sanctioned, each in writing where it lives: {@code ConsoleLoginCallbackTest} (stub beans
 * for the production login chain) and {@code ConsoleErrorDispatchTest} (RANDOM_PORT for the
 * container's ERROR dispatch).
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClientRegistrations.class)
public @interface ConsoleWebTest {
}
