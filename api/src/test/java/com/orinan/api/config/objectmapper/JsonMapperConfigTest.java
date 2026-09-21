package com.orinan.api.config.objectmapper;

import com.orinan.api.common.api.Api;
import com.orinan.api.domain.workspacemember.controller.model.WorkspaceMemberInviteRequest;
import com.orinan.api.domain.workspace.controller.model.WorkspaceResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class JsonMapperConfigTest {

    @Test
    void autoConfiguredJsonMapperUsesSnakeCaseForRequestsAndResponses() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
                .withUserConfiguration(JsonMapperConfig.class, ObjectMapperConfig.class)
                .run(context -> {
                    var mapper = context.getBean(JsonMapper.class);
                    var request = mapper.readValue("""
                            {"workspace_id": 1, "emails": ["member@example.com", "other@example.com"]}
                            """, WorkspaceMemberInviteRequest.class);
                    assertThat(request.getWorkspaceId()).isEqualTo(1L);
                    assertThat(request.getEmails()).containsExactly("member@example.com", "other@example.com");

                    var response = mapper.readTree(mapper.writeValueAsString(
                            Api.OK(WorkspaceResponse.builder().userId(2L).build())));
                    assertThat(response.path("body").path("user_id").asLong()).isEqualTo(2L);
                    assertThat(response.path("result").path("result_code").asInt()).isEqualTo(200);
                    assertThat(response.path("body").has("userId")).isFalse();
                });
    }
}
