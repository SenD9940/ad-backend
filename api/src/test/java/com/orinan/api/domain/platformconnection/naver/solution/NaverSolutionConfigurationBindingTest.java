package com.orinan.api.domain.platformconnection.naver.solution;

import com.orinan.api.domain.platformconnection.naver.NaverCommerceClient;
import com.orinan.db.naverconnection.enums.NaverTokenType;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class NaverSolutionConfigurationBindingTest {

    private static final String APP_ID = "configured-commerce-application";
    private static final String APP_SECRET = "$2a$10$abcdefghijklmnopqrstuv";

    // Load only the shared configuration. Never read application-local.yml or real credentials.
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(context -> {
                var sources = context.getEnvironment().getPropertySources();
                sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
                try {
                    new YamlPropertySourceLoader().load("shared-application", new ClassPathResource("application.yml"))
                            .forEach(sources::addLast);
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            })
            .withUserConfiguration(BindingConfiguration.class);

    @Test
    void commerceApplicationCredentialsBindWithoutEnablingUnregisteredAuthorization() {
        withCommerceCredentials().run(context -> {
            assertThat(context).hasNotFailed();
            var properties = context.getBean(NaverSolutionProperties.class);
            assertThat(properties.getClientId()).isEqualTo(APP_ID);
            assertThat(properties.getClientSecret()).isEqualTo(APP_SECRET);
            assertThat(properties.isEnabled()).isFalse();
            assertThat(properties.isProviderContractVerified()).isFalse();
            assertThat(properties.ready()).isFalse();
        });
    }

    @Test
    void registeredSolutionUsesConfiguredCommerceCredentialsForSellerToken() {
        withCommerceCredentials().withPropertyValues(
                "naver.solution.enabled=true",
                "naver.solution.provider-contract-verified=true",
                "naver.solution.application-ref=mock-application",
                "naver.solution.solution-id=mock-solution",
                "naver.solution.jwt-public-key=mock-public-key",
                "naver.solution.public-base-url=https://app.example.test",
                "naver.solution.frontend-base-url=https://app.example.test",
                "naver.solution.marketplace-url=https://solution.smartstore.naver.com/mock-solution",
                "naver.solution.authorization-url-template=https://auth.commerce.naver.com/mock-only?state={state}&callback={callback}",
                "naver.solution.callback-state-parameter=state",
                "naver.solution.callback-proof-parameter=proof",
                "naver.solution.marketplace-token-parameter=token",
                "naver.solution.webhook-header-name=X-Mock-Naver-Key",
                "naver.solution.webhook-key=mock-webhook-key-with-at-least-32-characters"
        ).run(context -> {
            var properties = context.getBean(NaverSolutionProperties.class);
            assertThat(properties.ready()).isTrue();
            var commerce = mock(NaverCommerceClient.class);
            var http = mock(HttpClient.class);
            var client = new NaverSolutionClient(properties, commerce, JsonMapper.builder().build(), http);

            client.issueSellerToken("mock-seller-uid");

            verify(commerce).issueToken(APP_ID, APP_SECRET, NaverTokenType.SELLER, "mock-seller-uid");
            verifyNoInteractions(http);
        });
    }

    @Test
    void explicitSolutionCredentialsTakePrecedenceOverCommerceApplicationDefaults() {
        withCommerceCredentials().withPropertyValues(
                "naver.solution.client-id=explicit-solution-app",
                "naver.solution.client-secret=explicit-solution-secret"
        ).run(context -> {
            var properties = context.getBean(NaverSolutionProperties.class);
            assertThat(properties.getClientId()).isEqualTo("explicit-solution-app");
            assertThat(properties.getClientSecret()).isEqualTo("explicit-solution-secret");
        });
    }

    @Test
    void existingSolutionCredentialsContinueToBindWithoutCommerceApplicationProperties() {
        runner.withPropertyValues(
                "naver.solution.client-id=existing-solution-app",
                "naver.solution.client-secret=existing-solution-secret"
        ).run(context -> {
            var properties = context.getBean(NaverSolutionProperties.class);
            assertThat(properties.getClientId()).isEqualTo("existing-solution-app");
            assertThat(properties.getClientSecret()).isEqualTo("existing-solution-secret");
        });
    }

    @Test
    void missingCredentialsBindEmptyAndKeepAuthorizationUnavailable() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            var properties = context.getBean(NaverSolutionProperties.class);
            assertThat(properties.getClientId()).isEmpty();
            assertThat(properties.getClientSecret()).isEmpty();
            assertThat(properties.ready()).isFalse();
        });
    }

    private ApplicationContextRunner withCommerceCredentials() {
        return runner.withPropertyValues("app.naver-commerce.app-id=" + APP_ID,
                "app.naver-commerce.app-secret=" + APP_SECRET);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(NaverSolutionProperties.class)
    static class BindingConfiguration {
    }
}
