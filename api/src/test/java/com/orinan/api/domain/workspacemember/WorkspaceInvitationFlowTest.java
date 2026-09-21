package com.orinan.api.domain.workspacemember;

import com.orinan.api.common.exception.ApiException;
import com.orinan.api.common.code.ApiCode;
import com.orinan.api.common.time.SeoulDateTimes;
import com.orinan.api.domain.user.service.UserService;
import com.orinan.api.domain.user.exception.UserErrorCode;
import com.orinan.api.domain.workspace.service.WorkspaceService;
import com.orinan.api.domain.workspacemember.business.WorkspaceMemberBusiness;
import com.orinan.api.domain.workspacemember.controller.model.WorkspaceMemberAcceptRequest;
import com.orinan.api.domain.workspacemember.controller.model.WorkspaceMemberInviteRequest;
import com.orinan.api.domain.workspacemember.controller.model.WorkspaceMemberKickRequest;
import com.orinan.api.domain.workspacemember.controller.model.WorkspaceMemberResponse;
import com.orinan.api.domain.workspacemember.converter.WorkspaceMemberConverter;
import com.orinan.api.domain.workspacemember.service.WorkspaceInvitationService;
import com.orinan.api.domain.workspacemember.service.WorkspaceMemberService;
import com.orinan.db.user.UserEntity;
import com.orinan.db.user.enums.UserStatus;
import com.orinan.db.workspace.WorkspaceEntity;
import com.orinan.db.workspaceinvitation.WorkspaceInvitationEntity;
import com.orinan.db.workspaceinvitation.WorkspaceInvitationRepository;
import com.orinan.db.workspacemember.WorkspaceMemberEntity;
import com.orinan.db.workspacemember.WorkspaceMemberId;
import com.orinan.db.workspacemember.WorkspaceMemberRepository;
import com.orinan.db.workspacemember.enums.WorkspaceMemberRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.mail.MailSendException;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.IOException;
import java.util.Properties;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringJUnitConfig(WorkspaceInvitationFlowTest.Config.class)
@TestPropertySource(properties = {
        "app.workspace-invitation.accept-url=https://frontend.example.com/invitations/accept",
        "spring.mail.username=invite@example.com"
})
class WorkspaceInvitationFlowTest {

    @Autowired private WorkspaceMemberBusiness business;
    @Autowired private WorkspaceInvitationRepository invitations;
    @Autowired private WorkspaceMemberRepository members;
    @Autowired private WorkspaceService workspaceService;
    @Autowired private UserService userService;
    @Autowired private JavaMailSender mailSender;
    @Autowired private JpaTransactionManager transactionManager;

    private final WorkspaceMemberId memberId = new WorkspaceMemberId(10L, 20L);

    @BeforeEach
    void setUp() {
        invitations.deleteAll();
        members.deleteAll();
        reset(mailSender, workspaceService, userService);
        when(mailSender.createMimeMessage()).thenAnswer(invocation -> new MimeMessage(Session.getInstance(new Properties())));
        var owner = UserEntity.builder().id(1L).build();
        var workspace = WorkspaceEntity.builder().id(10L).name("테스트 워크스페이스").user(owner).build();
        var recipient = UserEntity.builder().id(20L).email("member@example.com").build();
        when(workspaceService.findByIdWithThrow(10L)).thenReturn(workspace);
        when(userService.findByEmailAndStatusWithThrow("member@example.com", UserStatus.REGISTERED)).thenReturn(recipient);
    }

    @Test
    void inviteSendsMailWithoutCreatingMembershipAndStoresOnlyTokenHash() throws Exception {
        String token = inviteAndGetToken();
        assertThat(UUID.fromString(token).version()).isEqualTo(4);
        try (var factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(new WorkspaceMemberAcceptRequest(token))).isEmpty();
        }
        assertThat(members.count()).isZero();
        var invitation = invitations.findAll().get(0);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(StandardCharsets.UTF_8)));
        assertThat(invitation.getTokenHash()).isEqualTo(hash).isNotEqualTo(token);
        assertThat(invitation.getExpiresAt()).isAfter(SeoulDateTimes.now().plusHours(23));
    }

    @Test
    void memberListIncludesOnlyAcceptedMembersOfRequestedWorkspace() {
        members.saveAndFlush(WorkspaceMemberEntity.builder().id(new WorkspaceMemberId(10L, 1L))
                .role(WorkspaceMemberRole.MEMBER).build());
        members.saveAndFlush(WorkspaceMemberEntity.builder().id(new WorkspaceMemberId(11L, 30L))
                .role(WorkspaceMemberRole.MEMBER).build());
        String token = inviteAndGetToken();

        assertThat(business.getMembers(10L, 1L)).extracting(WorkspaceMemberResponse::getUserId)
                .containsExactly(1L);
        business.accept(new WorkspaceMemberAcceptRequest(token), 20L);
        assertThat(business.getMembers(10L, 1L)).extracting(WorkspaceMemberResponse::getUserId)
                .containsExactly(1L, 20L);
        assertThat(business.getMembers(10L, 20L)).extracting(WorkspaceMemberResponse::getUserId)
                .containsExactly(1L, 20L);

        business.kick(new WorkspaceMemberKickRequest(10L, 20L), 1L);
        assertThat(business.getMembers(10L, 1L)).extracting(WorkspaceMemberResponse::getUserId)
                .containsExactly(1L);
        assertThatThrownBy(() -> business.getMembers(10L, 20L))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.getCodeIfs()).isEqualTo(UserErrorCode.USER_PERMISSION_DENY));
    }

    @Test
    void memberOfAnotherWorkspaceCannotReadMemberList() {
        members.saveAndFlush(WorkspaceMemberEntity.builder().id(new WorkspaceMemberId(11L, 30L))
                .role(WorkspaceMemberRole.MEMBER).build());

        assertThatThrownBy(() -> business.getMembers(10L, 30L))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.getCodeIfs()).isEqualTo(UserErrorCode.USER_PERMISSION_DENY));
    }

    @Test
    void missingWorkspaceCannotReturnMemberList() {
        when(workspaceService.findByIdWithThrow(99L))
                .thenThrow(new ApiException(ApiCode.BAD_REQUEST, "존재하지 않는 워크스페이스입니다"));

        assertThatThrownBy(() -> business.getMembers(99L, 1L))
                .isInstanceOf(ApiException.class).hasMessage("존재하지 않는 워크스페이스입니다");
    }

    @Test
    void htmlAndPlainTextContainSameInvitationLinkAndRecipient() {
        String token = inviteAndGetToken();
        var captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        var message = captor.getValue();
        String link = "https://frontend.example.com/invitations/accept?token=" + token;

        assertThat(mailBody(message, "text/html"))
                .contains("워크스페이스 참여하기", "테스트 워크스페이스", "member@example.com", "24시간")
                .contains("href=\"" + link + "\"")
                .doesNotContain("th:text", "th:href", "${inviteUrl}");
        assertThat(mailBody(message, "text/plain")).contains(link, "member@example.com", "24시간");
    }

    @Test
    void htmlEscapesUserSuppliedWorkspaceName() {
        var workspace = WorkspaceEntity.builder().id(10L)
                .name("우리 팀 <script>alert(1)</script> & 디자인")
                .user(UserEntity.builder().id(1L).build()).build();
        when(workspaceService.findByIdWithThrow(10L)).thenReturn(workspace);

        var results = business.invite(new WorkspaceMemberInviteRequest(10L, List.of("member@example.com")), 1L);

        assertThat(results.get(0).isSuccess()).isTrue();
        var captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        assertThat(mailBody(captor.getValue(), "text/html"))
                .contains("&lt;script&gt;", "&amp;")
                .doesNotContain("<script>");
    }

    @Test
    void recipientCanAcceptOnlyOnceAndCannotRejoinWithUsedLinkAfterKick() {
        String token = inviteAndGetToken();
        var response = business.accept(new WorkspaceMemberAcceptRequest(token), 20L);
        assertThat(response.getWorkspaceId()).isEqualTo(10L);
        assertThat(response.getUserId()).isEqualTo(20L);
        assertThat(response.getRole()).isEqualTo(WorkspaceMemberRole.MEMBER);
        assertThat(response.getRegisteredAt()).isNotNull();
        assertThat(members.existsById(memberId)).isTrue();
        assertThat(invitations.count()).isZero();
        assertInvalidInvitation(token);
        business.kick(new WorkspaceMemberKickRequest(10L, 20L), 1L);
        assertInvalidInvitation(token);
        assertThat(members.count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(longs = {1L, 30L})
    void anotherUserIncludingOwnerCannotAcceptOrConsumeInvitation(Long authenticatedUserId) {
        String token = inviteAndGetToken();
        assertThatThrownBy(() -> business.accept(new WorkspaceMemberAcceptRequest(token), authenticatedUserId))
                .isInstanceOf(ApiException.class)
                .hasMessage("초대받은 사용자만 수락할 수 있습니다. 초대 메일을 받은 계정으로 로그인해 주세요")
                .satisfies(e -> assertThat(((ApiException) e).getCodeIfs().getHttpStatusCode()).isEqualTo(403));
        assertThat(members.count()).isZero();
        assertThat(invitations.count()).isEqualTo(1);
        business.accept(new WorkspaceMemberAcceptRequest(token), 20L);
        assertThat(members.existsById(memberId)).isTrue();
    }

    @Test
    void expiredInvitationCannotBeAccepted() {
        String token = inviteAndGetToken();
        var invitation = invitations.findAll().get(0);
        invitation.setExpiresAt(SeoulDateTimes.now().minusSeconds(1));
        invitations.saveAndFlush(invitation);
        assertThatThrownBy(() -> business.accept(new WorkspaceMemberAcceptRequest(token), 20L))
                .isInstanceOf(ApiException.class).hasMessage("만료된 초대입니다");
        assertThat(members.count()).isZero();
    }

    @Test
    void resendingInvalidatesPreviousLink() {
        String oldToken = inviteAndGetToken();
        String newToken = inviteAndGetToken();
        assertThat(newToken).isNotEqualTo(oldToken);
        assertThat(invitations.count()).isEqualTo(1);
        assertInvalidInvitation(oldToken);
        business.accept(new WorkspaceMemberAcceptRequest(newToken), 20L);
        assertThat(members.count()).isEqualTo(1);
    }

    @Test
    void mailFailureRollsBackNewInvitation() {
        doThrow(new MailSendException("SMTP unavailable")).when(mailSender).send(any(MimeMessage.class));
        var result = business.invite(new WorkspaceMemberInviteRequest(10L, List.of("member@example.com")), 1L);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).isSuccess()).isFalse();
        assertThat(result.get(0).getMessage()).isEqualTo("초대 메일 발송에 실패했습니다");
        assertThat(invitations.count()).isZero();
        assertThat(members.count()).isZero();
    }

    @Test
    void failedResendPreservesPreviouslySentLink() {
        String token = inviteAndGetToken();
        doThrow(new MailSendException("SMTP unavailable")).when(mailSender).send(any(MimeMessage.class));
        var result = business.invite(new WorkspaceMemberInviteRequest(10L, List.of("member@example.com")), 1L);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).isSuccess()).isFalse();
        assertThat(result.get(0).getMessage()).isEqualTo("초대 메일 발송에 실패했습니다");
        business.accept(new WorkspaceMemberAcceptRequest(token), 20L);
        assertThat(members.count()).isEqualTo(1);
    }

    @Test
    void onlyOwnerCanInvite() {
        assertThatThrownBy(() -> business.invite(new WorkspaceMemberInviteRequest(10L, List.of("member@example.com")), 30L))
                .isInstanceOf(ApiException.class);
        assertThat(invitations.count()).isZero();
        verifyNoInteractions(mailSender);
    }

    @Test
    void existingMemberCannotBeInvited() {
        members.saveAndFlush(WorkspaceMemberEntity.builder().id(memberId).role(WorkspaceMemberRole.MEMBER).build());
        var result = business.invite(new WorkspaceMemberInviteRequest(10L, List.of("member@example.com")), 1L);
        assertThat(result.get(0).isSuccess()).isFalse();
        assertThat(result.get(0).getMessage()).isEqualTo("이미 등록된 워크스페이스 멤버입니다");
        assertThat(invitations.count()).isZero();
        verifyNoInteractions(mailSender);
    }

    @Test
    void rollingBackAcceptanceRestoresInvitationAndRemovesMembership() {
        String token = inviteAndGetToken();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            business.accept(new WorkspaceMemberAcceptRequest(token), 20L);
            status.setRollbackOnly();
        });
        assertThat(members.count()).isZero();
        assertThat(invitations.count()).isEqualTo(1);
        business.accept(new WorkspaceMemberAcceptRequest(token), 20L);
        assertThat(members.count()).isEqualTo(1);
    }

    @Test
    void batchPreservesSuccessfulInvitationsAndContinuesAfterMailFailure() {
        when(userService.findByEmailAndStatusWithThrow("failed@example.com", UserStatus.REGISTERED))
                .thenReturn(UserEntity.builder().id(30L).email("failed@example.com").build());
        when(userService.findByEmailAndStatusWithThrow("other@example.com", UserStatus.REGISTERED))
                .thenReturn(UserEntity.builder().id(40L).email("other@example.com").build());
        doAnswer(invocation -> {
            MimeMessage message = invocation.getArgument(0);
            if ("failed@example.com".equals(message.getAllRecipients()[0].toString())) {
                throw new MailSendException("SMTP unavailable");
            }
            return null;
        }).when(mailSender).send(any(MimeMessage.class));

        var results = business.invite(new WorkspaceMemberInviteRequest(10L, List.of("member@example.com", "failed@example.com", "other@example.com")), 1L);

        assertThat(results).extracting(result -> result.getEmail()).containsExactly("member@example.com", "failed@example.com", "other@example.com");
        assertThat(results).extracting(result -> result.isSuccess()).containsExactly(true, false, true);
        assertThat(invitations.findAll()).extracting(invitation -> invitation.getId().getUserId())
                .containsExactlyInAnyOrder(20L, 40L);
        assertThat(members.count()).isZero();
        var captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender, times(3)).send(captor.capture());
        var messages = captor.getAllValues();
        String firstToken = tokenFrom(messages.get(0));
        String lastToken = tokenFrom(messages.get(2));
        assertThat(firstToken).isNotEqualTo(lastToken);
        business.accept(new WorkspaceMemberAcceptRequest(firstToken), 20L);
        business.accept(new WorkspaceMemberAcceptRequest(lastToken), 40L);
        assertThat(members.count()).isEqualTo(2);
    }

    @Test
    void batchDeduplicatesRecipientsWithoutInvalidatingTheirLink() {
        var results = business.invite(new WorkspaceMemberInviteRequest(10L, List.of("member@example.com", "MEMBER@EXAMPLE.COM")), 1L);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).isSuccess()).isTrue();
        var captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender, times(1)).send(captor.capture());
        business.accept(new WorkspaceMemberAcceptRequest(tokenFrom(captor.getValue())), 20L);
        assertThat(members.count()).isEqualTo(1);
    }

    @Test
    void batchReportsInvalidRecipientsAndStillInvitesValidUser() {
        when(userService.findByEmailAndStatusWithThrow("missing@example.com", UserStatus.REGISTERED))
                .thenThrow(new ApiException(UserErrorCode.USER_NOT_FOUND));
        when(userService.findByEmailAndStatusWithThrow("existing@example.com", UserStatus.REGISTERED))
                .thenReturn(UserEntity.builder().id(40L).email("existing@example.com").build());
        members.saveAndFlush(WorkspaceMemberEntity.builder().id(new WorkspaceMemberId(10L, 40L))
                .role(WorkspaceMemberRole.MEMBER).build());

        var results = business.invite(new WorkspaceMemberInviteRequest(10L, List.of("missing@example.com", "existing@example.com", "member@example.com")), 1L);

        assertThat(results).extracting(result -> result.isSuccess()).containsExactly(false, false, true);
        assertThat(results.get(0).getMessage()).isEqualTo(UserErrorCode.USER_NOT_FOUND.getDescription());
        assertThat(results.get(1).getMessage()).isEqualTo("이미 등록된 워크스페이스 멤버입니다");
        assertThat(invitations.count()).isEqualTo(1);
        verify(mailSender, times(1)).send(any(MimeMessage.class));
    }

    @Test
    void batchRequestRequiresOneToFiftyValidNonBlankEmails() {
        try (var factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(validator.validate(new WorkspaceMemberInviteRequest(10L, List.of("member@example.com", "other@example.com")))).isEmpty();
            assertThat(validator.validate(new WorkspaceMemberInviteRequest(10L, null))).isNotEmpty();
            assertThat(validator.validate(new WorkspaceMemberInviteRequest(10L, List.of()))).isNotEmpty();
            assertThat(validator.validate(new WorkspaceMemberInviteRequest(10L, List.of("not-an-email")))).isNotEmpty();
            assertThat(validator.validate(new WorkspaceMemberInviteRequest(10L, java.util.Arrays.asList("member@example.com", null)))).isNotEmpty();
            assertThat(validator.validate(new WorkspaceMemberInviteRequest(10L, java.util.Collections.nCopies(51, "member@example.com")))).isNotEmpty();
        }
    }

    private String inviteAndGetToken() {
        clearInvocations(mailSender);
        var results = business.invite(new WorkspaceMemberInviteRequest(10L, List.of("member@example.com")), 1L);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).isSuccess()).isTrue();
        var captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        var message = captor.getValue();
        try {
            assertThat(message.getAllRecipients()).extracting(Object::toString).containsExactly("member@example.com");
            assertThat(message.getFrom()).extracting(Object::toString).containsExactly("invite@example.com");
        } catch (MessagingException e) {
            throw new AssertionError(e);
        }
        assertThat(mailBody(message, "text/plain")).contains("테스트 워크스페이스", "https://frontend.example.com/invitations/accept?token=");
        return tokenFrom(message);
    }

    private String tokenFrom(MimeMessage message) {
        var matcher = Pattern.compile("token=([0-9a-f-]{36})").matcher(mailBody(message, "text/plain"));
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private String mailBody(MimeMessage message, String mimeType) {
        try {
            message.saveChanges();
            String body = findBody(message, mimeType);
            assertThat(body).isNotNull();
            return body;
        } catch (MessagingException | IOException e) {
            throw new AssertionError(e);
        }
    }

    private String findBody(Part part, String mimeType) throws MessagingException, IOException {
        if (part.isMimeType(mimeType)) {
            return (String) part.getContent();
        }
        if (part.isMimeType("multipart/*")) {
            var multipart = (Multipart) part.getContent();
            for (int i = 0; i < multipart.getCount(); i++) {
                String body = findBody(multipart.getBodyPart(i), mimeType);
                if (body != null) {
                    return body;
                }
            }
        }
        return null;
    }

    private void assertInvalidInvitation(String token) {
        assertThatThrownBy(() -> business.accept(new WorkspaceMemberAcceptRequest(token), 20L))
                .isInstanceOf(ApiException.class).hasMessage("유효하지 않은 초대입니다");
    }

    @Configuration
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = {WorkspaceInvitationRepository.class, WorkspaceMemberRepository.class})
    @Import({WorkspaceMemberBusiness.class, WorkspaceMemberService.class,
            WorkspaceInvitationService.class, WorkspaceMemberConverter.class})
    static class Config {
        @Bean
        DataSource dataSource() {
            return new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).build();
        }

        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(dataSource);
            factory.setPackagesToScan(WorkspaceInvitationEntity.class.getPackageName(), WorkspaceMemberEntity.class.getPackageName());
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop"));
            return factory;
        }

        @Bean
        JpaTransactionManager transactionManager(jakarta.persistence.EntityManagerFactory factory) {
            return new JpaTransactionManager(factory);
        }

        @Bean
        TemplateEngine templateEngine() {
            var resolver = new ClassLoaderTemplateResolver();
            resolver.setPrefix("templates/");
            resolver.setSuffix(".html");
            resolver.setCharacterEncoding("UTF-8");
            var engine = new SpringTemplateEngine();
            engine.setTemplateResolver(resolver);
            return engine;
        }

        @Bean WorkspaceService workspaceService() { return mock(WorkspaceService.class); }
        @Bean UserService userService() { return mock(UserService.class); }
        @Bean JavaMailSender mailSender() { return mock(JavaMailSender.class); }
    }
}
