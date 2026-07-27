package io.github.essandhu.ledger.console.support;

import org.springframework.boot.restclient.test.MockServerRestClientCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Binds the console's API {@code RestClient} to a {@code MockRestServiceServer} through the
 * SAME {@code RestClientBuilderConfigurer} path production uses: the customizer bean is
 * applied while {@code ApiClientConfig}'s builder bean is being configured, so the
 * bind-before-build ordering problem never exists — and the OAuth2 relay interceptor stays
 * in the chain, which is the point: tests assert the {@code Authorization} header the
 * PRODUCTION wiring attached.
 *
 * <p>Tests obtain the server via the injected customizer's {@code getServer()} and MUST
 * {@code reset()} it in {@code @BeforeEach} — the server lives as long as the cached context.
 */
@TestConfiguration(proxyBeanMethods = false)
public class RestClientTestSupport {

    @Bean
    MockServerRestClientCustomizer mockServerRestClientCustomizer() {
        return new MockServerRestClientCustomizer();
    }
}
