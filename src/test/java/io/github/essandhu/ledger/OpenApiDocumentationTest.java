package io.github.essandhu.ledger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.jayway.jsonpath.JsonPath;

import io.github.essandhu.ledger.support.LedgerIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

/**
 * The OpenAPI CI artifact is produced HERE, not by a separate generation step: this test
 * asserts the spec's content and then writes it to the Gradle-supplied path
 * ({@code ledger.openapi.output}, wired in build.gradle.kts — no working-directory assumption),
 * and CI uploads that file with {@code if-no-files-found: error}. The artifact can therefore
 * never be stale or unverified — if this test doesn't run and pass, there is nothing to upload
 * and the pipeline fails loudly.
 *
 * <p>Known springdoc 3.0.3 quirk (#3308): spurious {@code "default": null} entries appear in
 * the generated spec (fix merged upstream, unreleased). Cosmetic; nothing here asserts on
 * defaults, and the artifact ships as generated.
 */
@LedgerIntegrationTest
@DisplayName("OpenAPI: the spec describes the M1 surface and becomes the CI artifact")
class OpenApiDocumentationTest {

    @Autowired
    private MockMvcTester mvc;

    @Test
    @DisplayName("/v3/api-docs covers all four account operations, secured with bearer JWT")
    void api_docs_cover_the_account_surface_and_are_written_for_ci() throws Exception {
        MvcTestResult result = mvc.get().uri("/v3/api-docs").with(jwt()).exchange();
        assertThat(result).hasStatusOk();
        String spec = result.getResponse().getContentAsString();

        Map<String, Object> collection = JsonPath.read(spec, "$.paths['/api/v1/accounts']");
        assertThat(collection).containsKeys("post", "get");
        Map<String, Object> item = JsonPath.read(spec, "$.paths['/api/v1/accounts/{id}']");
        assertThat(item).containsKeys("get", "patch");

        Map<String, Object> schemes = JsonPath.read(spec, "$.components.securitySchemes");
        assertThat(schemes).containsKey("bearer-jwt");
        assertThat(JsonPath.<String>read(spec, "$.info.version")).isEqualTo("v1");

        String output = System.getProperty("ledger.openapi.output");
        assertThat(output)
                .as("build.gradle.kts must supply -Dledger.openapi.output (the CI artifact path)")
                .isNotBlank();
        Path artifact = Path.of(output);
        Files.createDirectories(artifact.getParent());
        Files.writeString(artifact, spec);
    }
}
