package com.orinan.adminapi.domain.auth;

import com.orinan.adminapi.AdminApiApplication;
import com.orinan.db.metaconnection.MetaConnectionEntity;
import com.orinan.db.platformconnection.PlatformConnectionEntity;
import com.orinan.db.platformconnection.enums.ProviderType;
import com.orinan.db.user.UserEntity;
import com.orinan.db.user.UserRepository;
import com.orinan.db.user.enums.UserRole;
import com.orinan.db.user.enums.UserStatus;
import com.orinan.db.userprofile.UserProfileEntity;
import com.orinan.db.userprofile.enums.UserProfileStatus;
import com.orinan.db.workspace.WorkspaceEntity;
import com.orinan.db.workspacemember.WorkspaceMemberEntity;
import com.orinan.db.workspacemember.WorkspaceMemberId;
import com.orinan.db.workspacemember.enums.WorkspaceMemberRole;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Starts the real application using only disposable H2 data and test-only cryptographic keys. */
@SpringBootTest(classes = AdminApiApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:admin-smoke;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "token.secret.key=admin-test-key-with-at-least-32-bytes-secret",
        "aes.key.personal-data-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "aes.key.search-hmac-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "app.admin.access-token-minutes=30", "app.admin.allowed-origins=",
        "springdoc.swagger-ui.enabled=false"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Transactional
class AdminApplicationSmokeTest {
    @Autowired MockMvc mvc;
    @Autowired JsonMapper mapper;
    @Autowired UserRepository users;
    @Autowired EntityManager em;
    @Autowired PasswordEncoder passwords;
    @Autowired ApplicationContext context;
    private UserEntity admin;
    private UserEntity customer;
    private WorkspaceEntity workspace;

    @BeforeEach
    void createOnlyTestFixtures() {
        admin = user("admin-smoke@example.com", UserRole.ADMIN);
        customer = user("customer-smoke@example.com", UserRole.CUSTOMER);
        em.persist(UserProfileEntity.builder().user(admin).name("테스트 관리자")
                .phone("010-sensitive-phone").phoneHash("test-phone-hash")
                .address("sensitive-address").addressDetail("sensitive-address-detail")
                .status(UserProfileStatus.REGISTERED).mailNotificationEnabled(false).build());
        workspace = WorkspaceEntity.builder().name("테스트 워크스페이스").user(admin).build();
        em.persist(workspace);
        em.flush();
        em.persist(WorkspaceMemberEntity.builder().id(new WorkspaceMemberId(workspace.getId(), customer.getId()))
                .role(WorkspaceMemberRole.MEMBER).build());
        var connection = PlatformConnectionEntity.builder().workspace(workspace).providerType(ProviderType.META)
                .externalAccountId("test-external-account").accountName("테스트 Meta 연결").requiresReauth(false).build();
        em.persist(connection);
        em.flush();
        em.persist(MetaConnectionEntity.builder().connection(connection).accessToken("sensitive-platform-token")
                .expiresAt(LocalDateTime.now().plusDays(5)).grantedScopes("ads_read").build());
        em.flush();
        em.clear();
    }

    @Test
    void realApplicationSupportsLoginAllReadAreasMutationAuditAndLogout() throws Exception {
        assertThat(context.getBeansOfType(UserDetailsService.class)).isEmpty();
        String token = login();
        String authorization = "Bearer " + token;
        mvc.perform(get("/admin-api/overview").header("Authorization", authorization))
                .andExpect(status().isOk()).andExpect(jsonPath("$.body.users").value(2))
                .andExpect(jsonPath("$.body.active_administrators").value(1))
                .andExpect(jsonPath("$.body.connections").value(1));
        for (String path : new String[]{"/admin-api/users", "/admin-api/workspaces", "/admin-api/connections", "/admin-api/audit-logs"}) {
            var response = mvc.perform(get(path).header("Authorization", authorization))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.body.items").isArray())
                    .andExpect(jsonPath("$.body.total_elements").isNumber())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andReturn().getResponse().getContentAsString();
            assertPrivateDataAbsent(response);
        }
        mvc.perform(patch("/admin-api/workspaces/" + workspace.getId()).header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"관리자가 변경한 이름\",\"reason\":\"운영 요청 확인\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.body.changed").value(true));
        mvc.perform(get("/admin-api/workspaces/" + workspace.getId()).header("Authorization", authorization))
                .andExpect(status().isOk()).andExpect(jsonPath("$.body.name").value("관리자가 변경한 이름"))
                .andExpect(jsonPath("$.body.owner_id").value(admin.getId()))
                .andExpect(jsonPath("$.body.registered_at").isString());
        mvc.perform(get("/admin-api/audit-logs").param("action", "WORKSPACE_RENAME").header("Authorization", authorization))
                .andExpect(status().isOk()).andExpect(jsonPath("$.body.total_elements").value(1))
                .andExpect(jsonPath("$.body.items[0].actor_user_id").value(admin.getId()))
                .andExpect(jsonPath("$.body.items[0].after_value").value("관리자가 변경한 이름"));
        mvc.perform(post("/admin-api/auth/logout").header("Authorization", authorization))
                .andExpect(status().isOk());
        mvc.perform(get("/admin-api/overview").header("Authorization", authorization))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void customerLoginAndServiceTokenCannotReachAdministratorRoutes() throws Exception {
        mvc.perform(post("/admin-api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"customer-smoke@example.com\",\"password\":\"test-password\"}"))
                .andExpect(status().isUnauthorized());
        String serviceToken = Jwts.builder().claim("userId", admin.getId())
                .expiration(Date.from(Instant.now().plusSeconds(1800)))
                .signWith(Keys.hmacShaKeyFor("admin-test-key-with-at-least-32-bytes-secret".getBytes(StandardCharsets.UTF_8))).compact();
        mvc.perform(get("/admin-api/users").header("Authorization", "Bearer " + serviceToken))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/v3/api-docs")).andExpect(status().isUnauthorized());
    }

    @Test
    void actualJsonMapperAcceptsSnakeCaseOwnerMutationAndRejectsUnsupportedFields() throws Exception {
        String authorization = "Bearer " + login();
        mvc.perform(patch("/admin-api/workspaces/" + workspace.getId() + "/owner")
                        .header("Authorization", authorization).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expected_owner_id\":" + admin.getId() + ",\"new_owner_id\":" + customer.getId()
                                + ",\"reason\":\"테스트 소유권 이전\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.body.changed").value(true));
        mvc.perform(get("/admin-api/workspaces/" + workspace.getId()).header("Authorization", authorization))
                .andExpect(status().isOk()).andExpect(jsonPath("$.body.owner_id").value(customer.getId()));
        mvc.perform(patch("/admin-api/workspaces/" + workspace.getId()).header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"변경 안 됨\",\"reason\":\"테스트\",\"unsupported\":true}"))
                .andExpect(status().isBadRequest());
    }

    private String login() throws Exception {
        var response = mvc.perform(post("/admin-api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin-smoke@example.com\",\"password\":\"test-password\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.body.token_type").value("Bearer"))
                .andExpect(jsonPath("$.body.expires_in").value(1800))
                .andExpect(jsonPath("$.body.user.name").value("테스트 관리자"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString();
        assertPrivateDataAbsent(response);
        return mapper.readTree(response).path("body").path("access_token").asText();
    }

    private UserEntity user(String email, UserRole role) {
        return users.saveAndFlush(UserEntity.builder().email(email).password(passwords.encode("test-password"))
                .status(UserStatus.REGISTERED).role(role).build());
    }

    private void assertPrivateDataAbsent(String response) {
        assertThat(response).doesNotContain("password", "refresh_token", "auth_version", "phone_hash",
                "010-sensitive-phone", "sensitive-address", "sensitive-platform-token", "test-phone-hash");
    }
}
