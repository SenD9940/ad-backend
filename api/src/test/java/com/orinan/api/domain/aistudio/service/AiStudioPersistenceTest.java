package com.orinan.api.domain.aistudio.service;

import com.orinan.api.common.exception.ApiException;
import com.orinan.api.domain.aistudio.client.*;
import com.orinan.api.domain.aistudio.controller.model.AiStudioGenerationRequest;
import com.orinan.api.domain.aistudio.exception.AiStudioErrorCode;
import com.orinan.db.aistudio.category.AiStudioCategoryEntity;
import com.orinan.db.aistudio.enums.AiStudioKind;
import com.orinan.db.aistudio.output.*;
import com.orinan.db.aistudio.template.*;
import com.orinan.db.crypto.*;
import com.orinan.db.user.*;
import com.orinan.db.user.enums.*;
import com.orinan.db.userprofile.UserProfileEntity;
import com.orinan.db.workspace.*;
import com.orinan.db.workspacemember.*;
import com.orinan.db.workspacemember.enums.WorkspaceMemberRole;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import java.time.LocalDateTime;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop", showSql = false)
@ActiveProfiles("test")
@ContextConfiguration(classes = AiStudioPersistenceTest.Config.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AiStudioPersistenceTest {
    @Autowired AiStudioService service;
    @Autowired AiStudioTransactions writes;
    @Autowired AiStudioAccess access;
    @Autowired AiStudioOutputRepository outputs;
    @Autowired AiStudioProperties properties;
    @Autowired AiStudioStorage storage;
    @Autowired OpenAiStudioClient client;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager transactions;
    long owner, member, stranger, workspace, template, category;
    final AiStudioImage image = new AiStudioImage(new byte[]{1,2,3}, "image/png", 2, 2);
    @BeforeEach void setup() {
        reset(storage, client); properties.setApiKey("private-test-key");
        when(storage.isConfigured()).thenReturn(true);
        when(storage.preview(anyString())).thenAnswer(inv -> "https://signed.test/" + inv.getArgument(0));
        when(storage.read(anyString())).thenReturn(image);
        when(storage.store(anyLong(), anyLong(), any())).thenReturn("saved/output.png");
        when(client.generate(any(), anyString(), anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn(new OpenAiStudioClient.Generated(image, null));
        tx(() -> {
            for (String entity : List.of("AiStudioOutputEntity", "AiTemplateEntity", "AiStudioCategoryEntity", "AiTemplateImageEntity", "WorkspaceMemberEntity", "WorkspaceEntity", "UserProfileEntity", "UserEntity"))
                em.createQuery("delete from " + entity).executeUpdate();
            owner = user("owner@test.dev"); member = user("member@test.dev"); stranger = user("stranger@test.dev");
            var w = WorkspaceEntity.builder().name("스토어").user(em.getReference(UserEntity.class, owner)).build(); em.persist(w); workspace = w.getId();
            em.persist(WorkspaceMemberEntity.builder().id(new WorkspaceMemberId(workspace, member)).role(WorkspaceMemberRole.MEMBER).build());
            em.persist(new AiTemplateImageEntity("sample.png", "image/png", 10, 2, 2, owner, LocalDateTime.now()));
            var c = new AiStudioCategoryEntity("일반", LocalDateTime.now()); em.persist(c); category = c.getId();
            var t = new AiTemplateEntity(AiStudioKind.AD_IMAGE, "샘플", "설명", c, "스타일", "sample.png", true, owner, LocalDateTime.now()); em.persist(t); template = t.getId();
        });
    }
    @Test void onlyActiveWorkspaceOwnersAndMembersCanReadOrGenerate() {
        assertThat(service.capabilities(workspace, owner).enabled()).isTrue();
        assertThat(service.capabilities(workspace, member).enabled()).isTrue();
        denied(() -> service.capabilities(workspace, stranger));
        denied(() -> service.generate(workspace, stranger, request("상품", UUID.randomUUID().toString())));
        tx(() -> em.find(UserEntity.class, member).setStatus(UserStatus.SUSPENDED));
        denied(() -> service.outputs(workspace, member, null, 0, 20));
        verifyNoInteractions(client);
    }
    @Test void identicalGenerationCommitsOneTicketAndCallsProviderOnlyOnce() {
        var request = request("상품", UUID.randomUUID().toString());
        when(client.generate(any(), anyString(), anyString(), anyString(), any(), any(), any(), any())).thenAnswer(inv -> {
            assertThat(outputs.count()).isEqualTo(1);
            assertThat(outputs.findAll().get(0).getStatus()).isEqualTo(AiStudioOutputStatus.PENDING);
            return new OpenAiStudioClient.Generated(image, null);
        });
        var first = service.generate(workspace, owner, request);
        var second = service.generate(workspace, owner, request);
        assertThat(first.status()).isEqualTo(AiStudioOutputStatus.SUCCEEDED);
        assertThat(second.id()).isEqualTo(first.id());
        verify(client, times(1)).generate(any(), anyString(), anyString(), anyString(), any(), any(), any(), any());
        assertThatThrownBy(() -> service.generate(workspace, owner, request("변경된 상품", request.idempotencyKey())))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCodeIfs()).isEqualTo(AiStudioErrorCode.IDEMPOTENCY_CONFLICT));
    }
    @Test void failuresArePersistedAndSameRequestNeverRepeatsBillableWork() {
        var request = request("상품", UUID.randomUUID().toString());
        when(client.generate(any(), anyString(), anyString(), anyString(), any(), any(), any(), any()))
                .thenThrow(new ApiException(AiStudioErrorCode.PROVIDER_FAILURE));
        assertThatThrownBy(() -> service.generate(workspace, owner, request)).isInstanceOf(ApiException.class);
        var existing = service.generate(workspace, owner, request);
        assertThat(existing.status()).isEqualTo(AiStudioOutputStatus.FAILED);
        verify(client, times(1)).generate(any(), anyString(), anyString(), anyString(), any(), any(), any(), any());
    }
    @Test void concurrentRetrySeesCommittedPendingTicketAndDoesNotStartAnotherGeneration() throws Exception {
        var request = request("상품", UUID.randomUUID().toString());
        var providerStarted = new java.util.concurrent.CountDownLatch(1);
        var providerMayFinish = new java.util.concurrent.CountDownLatch(1);
        when(client.generate(any(), anyString(), anyString(), anyString(), any(), any(), any(), any())).thenAnswer(inv -> {
            providerStarted.countDown();
            if (!providerMayFinish.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
            return new OpenAiStudioClient.Generated(image, null);
        });
        var first = java.util.concurrent.CompletableFuture.supplyAsync(() -> service.generate(workspace, owner, request));
        try {
            assertThat(providerStarted.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var second = service.generate(workspace, owner, request);
            assertThat(second.status()).isEqualTo(AiStudioOutputStatus.PENDING);
            assertThat(outputs.count()).isEqualTo(1);
        } finally { providerMayFinish.countDown(); }
        assertThat(first.get(5, java.util.concurrent.TimeUnit.SECONDS).status()).isEqualTo(AiStudioOutputStatus.SUCCEEDED);
        verify(client, times(1)).generate(any(), anyString(), anyString(), anyString(), any(), any(), any(), any());
    }
    @Test void accessRevokedDuringProviderCallPreventsFinalStorageAndExport() {
        when(client.generate(any(), anyString(), anyString(), anyString(), any(), any(), any(), any())).thenAnswer(inv -> {
            tx(() -> em.remove(em.find(WorkspaceMemberEntity.class, new WorkspaceMemberId(workspace, member))));
            return new OpenAiStudioClient.Generated(image, null);
        });
        denied(() -> service.generate(workspace, member, request("상품", UUID.randomUUID().toString())));
        assertThat(outputs.findAll().get(0).getStatus()).isEqualTo(AiStudioOutputStatus.FAILED);
        verify(storage, never()).store(anyLong(), anyLong(), any());
        denied(() -> service.exportOutput(workspace, member, outputs.findAll().get(0).getId()));
    }
    @Test void pendingRequestIsRecoverableWithoutRepeatingProviderWork() {
        var request = request("상품", UUID.randomUUID().toString());
        var prepared = writes.prepare(workspace, owner, request, "hash", true);
        assertThat(prepared.generate()).isTrue();
        assertThat(writes.prepare(workspace, owner, request, "hash", true).generate()).isFalse();
        assertThat(service.outputs(workspace, owner, null, 0, 20).items().get(0).status()).isEqualTo(AiStudioOutputStatus.PENDING);
        assertThatThrownBy(() -> service.exportOutput(workspace, owner, prepared.outputId())).isInstanceOf(ApiException.class);
        verifyNoInteractions(client);
    }
    @Test void unpublishedTemplatesAndUnconfiguredProviderCannotCreateTickets() {
        properties.setApiKey("");
        assertThat(service.capabilities(workspace, owner).enabled()).isFalse();
        assertThatThrownBy(() -> service.generate(workspace, owner, request("상품", UUID.randomUUID().toString()))).isInstanceOf(ApiException.class);
        properties.setApiKey("private-test-key"); tx(() -> em.find(AiTemplateEntity.class, template).setPublished(false));
        assertThat(service.templates(workspace, owner, null, null, null, 0, 20).items()).isEmpty();
        assertThatThrownBy(() -> service.generate(workspace, owner, request("상품", UUID.randomUUID().toString()))).isInstanceOf(ApiException.class);
        assertThat(outputs.count()).isZero(); verifyNoInteractions(client);
    }
    @Test void outputAndImageIdsCannotCrossWorkspaces() {
        var created = service.generate(workspace, owner, request("상품", UUID.randomUUID().toString()));
        final long[] otherWorkspace = {0};
        tx(() -> { var w = WorkspaceEntity.builder().name("다른 공간").user(em.getReference(UserEntity.class, owner)).build(); em.persist(w); otherWorkspace[0] = w.getId(); });
        assertThatThrownBy(() -> service.output(otherWorkspace[0], owner, created.id())).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.exportOutput(otherWorkspace[0], owner, created.id())).isInstanceOf(ApiException.class);
        assertThat(service.exportOutput(workspace, owner, created.id()).imageBytes()).isEqualTo(image.bytes());
    }
    @Test void accessRevokedWhileDownloadingS3CannotExportImageBytes() {
        var created = service.generate(workspace, member, request("상품", UUID.randomUUID().toString()));
        when(storage.read("saved/output.png")).thenAnswer(inv -> {
            tx(() -> em.remove(em.find(WorkspaceMemberEntity.class, new WorkspaceMemberId(workspace, member))));
            return image;
        });
        denied(() -> service.exportOutput(workspace, member, created.id()));
    }
    @Test void categoryCatalogOnlyListsCategoriesWithPublishedTemplatesAndRequiresMembership() {
        tx(() -> {
            em.persist(new AiStudioCategoryEntity("아직 사용하지 않음", LocalDateTime.now()));
            var hidden = new AiStudioCategoryEntity("비공개만 사용", LocalDateTime.now()); em.persist(hidden);
            em.persist(new AiTemplateEntity(AiStudioKind.AD_IMAGE, "숨긴 샘플", "설명", hidden, "스타일", "sample.png", false, owner, LocalDateTime.now()));
            em.persist(new AiTemplateEntity(AiStudioKind.DETAIL_PAGE, "다른 공개 샘플", "설명", em.getReference(AiStudioCategoryEntity.class, category), "스타일", "sample.png", true, owner, LocalDateTime.now()));
        });
        assertThat(service.categories(workspace, member)).extracting(com.orinan.api.domain.aistudio.controller.model.AiStudioResponse.Category::id)
                .containsExactly(category);
        denied(() -> service.categories(workspace, stranger));
        tx(() -> em.find(UserEntity.class, member).setStatus(UserStatus.SUSPENDED));
        denied(() -> service.categories(workspace, member));
    }
    @Test void categoryFilterUsesForeignKeyAndRenameImmediatelyUpdatesNamesAndSearch() {
        final long[] otherCategory = {0};
        tx(() -> {
            var other = new AiStudioCategoryEntity("뷰티", LocalDateTime.now()); em.persist(other); otherCategory[0] = other.getId();
            em.persist(new AiTemplateEntity(AiStudioKind.DETAIL_PAGE, "뷰티 샘플", "설명", other, "스타일", "sample.png", true, owner, LocalDateTime.now()));
            em.find(AiStudioCategoryEntity.class, category).rename("라이프스타일", LocalDateTime.now());
        });
        var result = service.templates(workspace, owner, AiStudioKind.AD_IMAGE, category, "라이프스타일", 0, 20);
        assertThat(result.totalElements()).isEqualTo(1);
        assertThat(result.items().get(0).categoryId()).isEqualTo(category);
        assertThat(result.items().get(0).categoryName()).isEqualTo("라이프스타일");
        assertThat(service.template(workspace, owner, template).categoryName()).isEqualTo("라이프스타일");
        assertThat(service.templates(workspace, owner, AiStudioKind.AD_IMAGE, otherCategory[0], null, 0, 20).items()).isEmpty();
        assertThat(service.templates(workspace, owner, null, Long.MAX_VALUE, null, 0, 20).items()).isEmpty();
        assertThatThrownBy(() -> service.templates(workspace, owner, null, 0L, null, 0, 20)).isInstanceOf(ApiException.class);
    }
    private AiStudioGenerationRequest request(String name, String key) { return new AiStudioGenerationRequest(template, name, "상품 사실", null, null, key, null); }
    private long user(String email) { var user = UserEntity.builder().email(email).password("test").status(UserStatus.REGISTERED).role(UserRole.CUSTOMER).build(); em.persist(user); return user.getId(); }
    private void tx(Runnable task) { new TransactionTemplate(transactions).executeWithoutResult(status -> task.run()); }
    private void denied(org.assertj.core.api.ThrowableAssert.ThrowingCallable task) {
        assertThatThrownBy(task).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCodeIfs()).isEqualTo(AiStudioErrorCode.ACCESS_DENIED));
    }
    @Configuration
    @EntityScan(basePackageClasses = {AiStudioCategoryEntity.class, AiTemplateEntity.class, AiStudioOutputEntity.class, UserEntity.class, UserProfileEntity.class, WorkspaceEntity.class, WorkspaceMemberEntity.class})
    @EnableJpaRepositories(basePackageClasses = {AiTemplateRepository.class, AiStudioOutputRepository.class, WorkspaceRepository.class})
    @Import({AiStudioService.class, AiStudioTransactions.class, AiStudioAccess.class})
    static class Config {
        @Bean AiStudioProperties properties() { return new AiStudioProperties(); }
        @Bean AiStudioStorage storage() { return mock(AiStudioStorage.class); }
        @Bean OpenAiStudioClient client() { return mock(OpenAiStudioClient.class); }
        @Bean JsonMapper mapper() { return JsonMapper.builder().build(); }
        @Bean AesGcmStringEncryptor encryptor() {
            return new AesGcmStringEncryptor(new CryptoProperties(Base64.getEncoder().encodeToString(
                    "0123456789abcdef0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        }
    }
}
