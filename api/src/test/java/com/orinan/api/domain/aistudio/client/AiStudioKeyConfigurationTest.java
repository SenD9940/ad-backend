package com.orinan.api.domain.aistudio.client;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;

import static org.assertj.core.api.Assertions.assertThat;

class AiStudioKeyConfigurationTest {

    // Use common/prod YAML and synthetic values only; never load local configuration or credentials.
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(context -> {
                var sources = context.getEnvironment().getPropertySources();
                sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
                try {
                    new YamlPropertySourceLoader().load("prod-application", new ClassPathResource("application-prod.yml"))
                            .forEach(sources::addLast);
                    new YamlPropertySourceLoader().load("shared-application", new ClassPathResource("application.yml"))
                            .forEach(sources::addLast);
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            })
            .withUserConfiguration(BindingConfiguration.class);

    @Test
    void existingCreativeKeyTakesPrecedenceOverEnvironmentAliases() {
        assertBoundKey("mock-creative-key", "app.ai-creative.api-key=mock-creative-key",
                "OPEN_API_KEY=mock-open-key", "OPENAI_API_KEY=mock-legacy-key");
    }

    @Test
    void openApiKeyTakesPrecedenceOverLegacyEnvironmentAlias() {
        assertBoundKey("mock-open-key", "OPEN_API_KEY=mock-open-key", "OPENAI_API_KEY=mock-legacy-key");
    }

    @Test
    void legacyOpenAiEnvironmentAliasRemainsSupported() {
        assertBoundKey("mock-legacy-key", "OPENAI_API_KEY=mock-legacy-key");
    }

    @Test
    void explicitStudioKeyRetainsItsOverride() {
        assertBoundKey("mock-studio-key", "app.ai-studio.openai.api-key=mock-studio-key",
                "app.ai-creative.api-key=mock-creative-key", "OPEN_API_KEY=mock-open-key",
                "OPENAI_API_KEY=mock-legacy-key");
    }

    @Test
    void missingKeysLeaveGenerationUnconfigured() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            var properties = context.getBean(AiStudioProperties.class);
            assertThat(properties.getApiKey()).isEmpty();
            assertThat(properties.isConfigured()).isFalse();
        });
    }

    private void assertBoundKey(String expected, String... values) {
        runner.withPropertyValues(values).run(context -> {
            assertThat(context).hasNotFailed();
            var properties = context.getBean(AiStudioProperties.class);
            assertThat(properties.getApiKey()).isEqualTo(expected);
            assertThat(properties.isConfigured()).isTrue();
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AiStudioProperties.class)
    static class BindingConfiguration {
    }
}
