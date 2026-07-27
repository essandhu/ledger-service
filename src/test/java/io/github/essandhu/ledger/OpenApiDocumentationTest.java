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
@DisplayName("OpenAPI: the spec describes the M1+M2+M3+M4+M6 surface and becomes the CI artifact")
class OpenApiDocumentationTest {

    @Autowired
    private MockMvcTester mvc;

    @Test
    @DisplayName("/v3/api-docs covers the account, posting, balance, idempotency, and reconciliation operations, secured with bearer JWT")
    void api_docs_cover_the_account_surface_and_are_written_for_ci() throws Exception {
        MvcTestResult result = mvc.get().uri("/v3/api-docs").with(jwt()).exchange();
        assertThat(result).hasStatusOk();
        String spec = result.getResponse().getContentAsString();

        Map<String, Object> collection = JsonPath.read(spec, "$.paths['/api/v1/accounts']");
        assertThat(collection).containsKeys("post", "get");
        Map<String, Object> item = JsonPath.read(spec, "$.paths['/api/v1/accounts/{id}']");
        assertThat(item).containsKeys("get", "patch");

        // M2 posting surface — derived purely from the MVC signatures, like M1's.
        Map<String, Object> entries = JsonPath.read(spec, "$.paths['/api/v1/journal-entries']");
        assertThat(entries).containsKeys("post");
        Map<String, Object> entryItem = JsonPath.read(spec, "$.paths['/api/v1/journal-entries/{id}']");
        assertThat(entryItem).containsKeys("get");
        Map<String, Object> reversal =
                JsonPath.read(spec, "$.paths['/api/v1/journal-entries/{id}/reversal']");
        assertThat(reversal).containsKeys("post");
        Map<String, Object> transfers = JsonPath.read(spec, "$.paths['/api/v1/transfers']");
        assertThat(transfers).containsKeys("post");

        // M4 (ADR-0004): the Idempotency-Key header parameter is documented — required, in
        // header — on every money-moving POST, straight from the controller signatures.
        // M7 discharges the ADR's deferred client-facing text: each of these operations must
        // carry the replay/conflict semantics INCLUDING the two deviations from the IETF
        // draft (block-instead-of-409, responses not immutable over time) — the OperationCustomizer
        // keys off the parameter, so this loop also proves no money mover escaped it.
        for (String path : new String[] {"/api/v1/journal-entries",
                "/api/v1/journal-entries/{id}/reversal", "/api/v1/transfers"}) {
            java.util.List<Map<String, Object>> parameters = JsonPath.read(spec,
                    "$.paths['%s'].post.parameters[?(@.name == 'Idempotency-Key')]"
                            .formatted(path));
            assertThat(parameters).as("Idempotency-Key documented on POST " + path).hasSize(1);
            assertThat(parameters.get(0))
                    .containsEntry("in", "header")
                    .containsEntry("required", true);
            String description = JsonPath.read(spec,
                    "$.paths['%s'].post.description".formatted(path));
            assertThat(description)
                    .as("ADR-0004 semantics discharged on POST " + path)
                    .contains("Idempotency-Replayed: true")
                    .contains("idempotency-key-conflict")
                    .contains("blocks")
                    .contains("not immutable over time");
        }

        // M3 balance & statement surface.
        Map<String, Object> balance =
                JsonPath.read(spec, "$.paths['/api/v1/accounts/{id}/balance']");
        assertThat(balance).containsKeys("get");
        Map<String, Object> postings =
                JsonPath.read(spec, "$.paths['/api/v1/accounts/{id}/postings']");
        assertThat(postings).containsKeys("get");

        // M6 reconciliation surface.
        Map<String, Object> runs = JsonPath.read(spec, "$.paths['/api/v1/reconciliation-runs']");
        assertThat(runs).containsKeys("post");
        Map<String, Object> runItem =
                JsonPath.read(spec, "$.paths['/api/v1/reconciliation-runs/{id}']");
        assertThat(runItem).containsKeys("get");
        Map<String, Object> findings =
                JsonPath.read(spec, "$.paths['/api/v1/reconciliation-runs/{id}/findings']");
        assertThat(findings).containsKeys("get");

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
