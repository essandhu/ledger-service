package io.github.essandhu.ledger.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.repository.support.ResourcelessJobRepository;
import org.springframework.context.annotation.Profile;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The cds-training configuration exists for exactly one {@code RUN} line in the Dockerfile and
 * must NEVER activate anywhere else (M7): it swaps Batch to a
 * resourceless JobRepository, which would invalidate every Batch-metadata proof if it leaked
 * into a served or tested context. This pins the two facts that make it safe and useful.
 */
@DisplayName("cds-training Batch configuration: profile-caged, resourceless by inheritance")
class CdsTrainingBatchConfigurationTest {

    @Test
    @DisplayName("the profile cage is exactly 'cds-training' — without it the resourceless Batch graph would ship to every environment")
    void profile_cage_is_exact() {
        Profile profile = CdsTrainingBatchConfiguration.class.getAnnotation(Profile.class);
        assertThat(profile).as("dropping @Profile would activate this in production").isNotNull();
        assertThat(profile.value()).containsExactly("cds-training");
    }

    @Test
    @DisplayName("the inherited jobRepository is the DB-free resourceless one — the property the training run depends on")
    void job_repository_is_resourceless() {
        assertThat(new CdsTrainingBatchConfiguration().jobRepository())
                .isInstanceOf(ResourcelessJobRepository.class);
    }
}
