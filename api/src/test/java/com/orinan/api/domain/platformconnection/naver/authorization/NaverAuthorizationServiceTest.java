package com.orinan.api.domain.platformconnection.naver.authorization;

import com.orinan.api.common.exception.ApiException;
import com.orinan.api.domain.platformconnection.naver.NaverCommerceClient;
import com.orinan.api.domain.platformconnection.naver.solution.NaverAuthorizationProvider;
import com.orinan.api.domain.platformconnection.naver.solution.NaverAuthorizationProvider.*;
import com.orinan.api.domain.platformconnection.service.PlatformConnectionService;
import com.orinan.db.naverauthorization.*;
import com.orinan.db.naversolution.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import java.net.URI;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NaverAuthorizationServiceTest {
    NaverAuthorizationAttemptRepository attempts=mock(NaverAuthorizationAttemptRepository.class);
    NaverMarketplaceReceiptRepository receipts=mock(NaverMarketplaceReceiptRepository.class);
    NaverSolutionSubscriptionRepository subscriptions=mock(NaverSolutionSubscriptionRepository.class);
    NaverSubscriptionOperationRepository operations=mock(NaverSubscriptionOperationRepository.class);
    PlatformConnectionService permissions=mock(PlatformConnectionService.class);
    NaverAuthorizationConnectionBridge connections=mock(NaverAuthorizationConnectionBridge.class);
    NaverAuthorizationProvider provider=mock(NaverAuthorizationProvider.class);
    TransactionTemplate tx=mock(TransactionTemplate.class);
    EntityManager em=mock(EntityManager.class);
    Map<String,NaverAuthorizationAttemptEntity> attemptRows=new HashMap<>();
    Map<String,NaverSubscriptionOperationEntity> operationRows=new HashMap<>();
    Map<Long,NaverSolutionSubscriptionEntity> grantRows=new HashMap<>();
    Map<String,NaverMarketplaceReceiptEntity> receiptRows=new HashMap<>();
    NaverAuthorizationService service;
    SellerProof proof=new SellerProof("seller-1",null,"스토어",null,"subscription-1","기본 요금제",false,true,"plan-1");
    Subscription pending=new Subscription("subscription-1","PENDING","기본 요금제",null,"seller-1","plan-1");

    @BeforeEach void setup() {
        when(provider.ready()).thenReturn(true);when(provider.applicationRef()).thenReturn("application");when(provider.solutionId()).thenReturn("solution");
        when(provider.publicBaseUrl()).thenReturn("https://app.example.com");when(provider.frontendBaseUrl()).thenReturn("https://app.example.com");
        when(provider.launchUri(anyString(),anyString())).thenReturn(URI.create("https://auth.naver.com/popup"));
        when(provider.interpretProof(anyString())).thenReturn(proof);when(provider.querySubscription(anyString())).thenReturn(pending);
        when(attempts.findCurrentOwnerId(anyLong())).thenReturn(Optional.of(7L));
        when(tx.execute(any())).thenAnswer(invocation->((TransactionCallback<?>)invocation.getArgument(0)).doInTransaction(mock(TransactionStatus.class)));
        doAnswer(invocation->{((java.util.function.Consumer<TransactionStatus>)invocation.getArgument(0)).accept(mock(TransactionStatus.class));return null;}).when(tx).executeWithoutResult(any());
        when(attempts.saveAndFlush(any())).thenAnswer(i->{var a=(NaverAuthorizationAttemptEntity)i.getArgument(0);attemptRows.put(a.getId(),a);return a;});
        when(attempts.save(any())).thenAnswer(i->{var a=(NaverAuthorizationAttemptEntity)i.getArgument(0);attemptRows.put(a.getId(),a);return a;});
        when(attempts.findByIdForUpdate(anyString())).thenAnswer(i->Optional.ofNullable(attemptRows.get(i.getArgument(0))));
        when(attempts.findByLaunchHash(anyString())).thenAnswer(i->attemptRows.values().stream().filter(a->Objects.equals(a.getLaunchHash(),i.getArgument(0))).findFirst());
        when(attempts.existsByProofHash(anyString())).thenAnswer(i->attemptRows.values().stream().anyMatch(a->Objects.equals(a.getProofHash(),i.getArgument(0))));
        when(subscriptions.findByApplicationRefAndAccountUidForUpdate(anyString(),anyString())).thenAnswer(i->grantRows.values().stream().filter(g->g.getAccountUid().equals(i.getArgument(1))).findFirst());
        when(subscriptions.saveAndFlush(any())).thenAnswer(i->{var g=(NaverSolutionSubscriptionEntity)i.getArgument(0);if(g.getId()==null)g.setId((long)grantRows.size()+1);grantRows.put(g.getId(),g);return g;});
        when(subscriptions.findById(anyLong())).thenAnswer(i->Optional.ofNullable(grantRows.get(i.getArgument(0))));
        when(subscriptions.findByIdForUpdate(anyLong())).thenAnswer(i->Optional.ofNullable(grantRows.get(i.getArgument(0))));
        when(operations.findById(anyString())).thenAnswer(i->Optional.ofNullable(operationRows.get(i.getArgument(0))));
        when(operations.saveAndFlush(any())).thenAnswer(i->{var o=(NaverSubscriptionOperationEntity)i.getArgument(0);operationRows.put(o.getId(),o);return o;});
        when(receipts.saveAndFlush(any())).thenAnswer(i->{var r=(NaverMarketplaceReceiptEntity)i.getArgument(0);receiptRows.put(r.getId(),r);return r;});
        when(receipts.findByIdForUpdate(anyString())).thenAnswer(i->Optional.ofNullable(receiptRows.get(i.getArgument(0))));
        when(provider.issueSellerToken("seller-1")).thenReturn(new NaverCommerceClient.IssuedToken("seller-token",LocalDateTime.now().plusHours(1)));
        when(provider.getSellerAccount("seller-token")).thenReturn(new NaverCommerceClient.SellerAccount("account-1","seller-1"));
        when(connections.save(anyLong(),anyLong(),nullable(Long.class),anyString(),anyLong(),anyLong(),any(),any())).thenReturn(44L);
        service=new NaverAuthorizationService(attempts,receipts,subscriptions,operations,permissions,connections,provider,tx,em);
    }
    @Test void disabledCapabilityDoesNotTouchNewTablesOrProviderNetwork() {
        when(provider.ready()).thenReturn(false);
        assertThat(service.capabilities(1L,7L).ready()).isFalse();
        assertThatThrownBy(()->service.start(1L,7L,null,null,null)).isInstanceOf(ApiException.class);
        verifyNoInteractions(attempts,receipts,subscriptions,operations);verify(provider,never()).querySubscription(any());
    }
    @Test void startBindsOwnerWorkspaceAndHashesBrowserBeforePublicLaunch() {
        var s=start();var row=row(s);
        assertThat(row.getWorkspaceId()).isEqualTo(1L);assertThat(row.getUserId()).isEqualTo(7L);
        assertThat(row.getBrowserHash()).doesNotContain(s.browserSecret());assertThat(row.getExpiresAt()).isAfter(Instant.now().plusSeconds(590));
        assertThat(row.getLaunchExpiresAt()).isBefore(row.getExpiresAt());assertThat(s.response().reviewRevision()).isZero();
    }
    @Test void forwardedLaunchCannotBindAnotherBrowser() {
        var s=start();assertThatThrownBy(()->service.launch(ticket(s),"other-browser")).isInstanceOf(ApiException.class);
        verify(provider,never()).launchUri(anyString(),anyString());assertThat(row(s).getLaunchHash()).isNotNull();
    }
    @Test void launchTicketIsOneUseAndReissueRejectsOldGeneration() {
        var s=start();String oldState=row(s).getProviderState();String oldTicket=ticket(s);
        var replacement=service.reissue(1L,7L,s.response().attemptId(),s.browserSecret());
        assertThatThrownBy(()->service.launch(oldTicket,s.browserSecret())).isInstanceOf(ApiException.class);
        service.launch(replacement.launchUrl().split("ticket=")[1],s.browserSecret());
        assertThatThrownBy(()->service.callback(oldState,"jwe",s.browserSecret())).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->service.launch(replacement.launchUrl().split("ticket=")[1],s.browserSecret())).isInstanceOf(ApiException.class);
    }
    @Test void callbackRequiresConsumedLaunchBrowserAndStateAndNeverApproves() {
        var s=start();String state=row(s).getProviderState();
        assertThatThrownBy(()->service.callback(state,"proof",s.browserSecret())).isInstanceOf(ApiException.class);
        service.launch(ticket(s),s.browserSecret());
        assertThatThrownBy(()->service.callback(state,"proof","different-browser")).isInstanceOf(ApiException.class);
        service.callback(state,"proof",s.browserSecret());
        assertThat(row(s).getStatus()).isEqualTo("REVIEW_REQUIRED");assertThat(row(s).getReviewRevision()).isEqualTo(1);
        verify(provider,never()).approve(any(),any(),any());verifyNoInteractions(connections);
    }
    @Test void sameProofCannotBeReusedAcrossAttempts() {
        var first=review("proof");var second=start();String state=row(second).getProviderState();service.launch(ticket(second),second.browserSecret());
        assertThatThrownBy(()->service.callback(state,"proof",second.browserSecret())).isInstanceOf(ApiException.class);
        assertThat(row(first).getStatus()).isEqualTo("REVIEW_REQUIRED");
    }
    @Test void reconnectDifferentSellerPreservesExistingConnection() {
        when(connections.reconnectAccountUid(1L,7L,44L)).thenReturn("other-seller");
        var s=service.start(1L,7L,44L,null,null);String state=row(s).getProviderState();service.launch(ticket(s),s.browserSecret());service.callback(state,"proof",s.browserSecret());
        assertThat(row(s).getStatus()).isEqualTo("FAILED");assertThat(row(s).getEncryptedProof()).isNull();verify(connections,never()).save(any(),any(),any(),any(),any(),anyLong(),any(),any());
    }
    @Test void differentUserOrWorkspaceCannotReadAttempt() {
        var s=start();assertThatThrownBy(()->service.get(2L,7L,s.response().attemptId())).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->service.get(null,8L,s.response().attemptId())).isInstanceOf(ApiException.class);
        assertThat(service.get(null,7L,s.response().attemptId()).workspaceId()).isEqualTo(1L);
    }
    @Test void ownerLostBeforeApprovalNeverDispatches() {
        var s=review("proof");when(attempts.findCurrentOwnerId(1L)).thenReturn(Optional.of(8L));
        assertThatThrownBy(()->complete(s)).isInstanceOf(ApiException.class);verify(provider,never()).approve(any(),any(),any());
    }
    @Test void confirmRequiresBrowserBindingEvenWithBearer() {
        var s=review("proof");assertThatThrownBy(()->service.complete(1L,7L,s.response().attemptId(),"",1,"idempotency-key-0001")).isInstanceOf(ApiException.class);
        verify(provider,never()).approve(any(),any(),any());
    }
    @Test void changedPlanRequiresNewReviewBeforeApproval() {
        var s=review("proof");when(provider.querySubscription("seller-1")).thenReturn(new Subscription("subscription-1","PENDING","변경 요금제",null,"seller-1","plan-2"));
        var response=complete(s);assertThat(response.status()).isEqualTo("REVIEW_REQUIRED");assertThat(response.reviewRevision()).isEqualTo(2);
        assertThat(response.subscription().planName()).isEqualTo("변경 요금제");verify(provider,never()).approve(any(),any(),any());
    }
    @Test void uncertainApprovalNeverResendsOnSameOrDifferentIdempotencyKeyAndSurvivesExpiry() {
        var s=review("proof");when(provider.approve(any(),any(),any())).thenThrow(new OutcomeUnknownException());
        assertThat(complete(s).status()).isEqualTo("RECONCILING");row(s).setExpiresAt(Instant.now().minusSeconds(1));
        service.complete(1L,7L,s.response().attemptId(),s.browserSecret(),1,"different-key-0002");service.get(null,7L,s.response().attemptId());
        verify(provider,times(1)).approve(any(),any(),any());assertThat(row(s).getStatus()).isEqualTo("RECONCILING");assertThat(operationRows).hasSize(1);
    }
    @Test void approvalClaimIsDurableBeforeRemoteCallAndCompletesOnlyAfterSellerVerification() {
        var s=review("proof");when(provider.approve(any(),any(),any())).thenAnswer(i->{
            assertThat(operationRows.values()).singleElement().extracting(NaverSubscriptionOperationEntity::getStatus).isEqualTo("DISPATCHING");
            var active=active(i.getArgument(2));when(provider.querySubscription("seller-1")).thenReturn(active);return active;
        });
        var result=complete(s);assertThat(result.status()).isEqualTo("CONNECTED");assertThat(result.connectionId()).isEqualTo(44);
        assertThat(row(s).getEncryptedProof()).isNull();complete(s);verify(provider,times(1)).approve(any(),any(),any());
    }
    @Test void activeSubscriptionWithFreshAuthenticationSkipsApproval() {
        when(provider.interpretProof(anyString())).thenReturn(new SellerProof("seller-1",null,"스토어",null,"subscription-1","기본 요금제",true,false,"plan-1"));
        when(provider.querySubscription("seller-1")).thenReturn(active("existing-mapping"));var s=review("proof");
        assertThat(complete(s).status()).isEqualTo("CONNECTED");verify(provider,never()).approve(any(),any(),any());
    }
    @Test void twoWorkspaceAttemptsShareOneGlobalApprovalOperation() {
        var one=review("proof-one");var two=service.start(2L,7L,null,null,null);String state=row(two).getProviderState();service.launch(ticket(two),two.browserSecret());service.callback(state,"proof-two",two.browserSecret());
        when(provider.approve(any(),any(),any())).thenThrow(new OutcomeUnknownException());complete(one);
        service.complete(2L,7L,two.response().attemptId(),two.browserSecret(),1,"idempotency-key-0002");verify(provider,times(1)).approve(any(),any(),any());assertThat(operationRows).hasSize(1);
    }
    @Test void cancelledAttemptCannotBeRevivedByLateCallbackAndProofIsRemoved() {
        var s=start();String state=row(s).getProviderState();service.launch(ticket(s),s.browserSecret());service.cancel(1L,7L,s.response().attemptId(),s.browserSecret());
        assertThatThrownBy(()->service.callback(state,"proof",s.browserSecret())).isInstanceOf(ApiException.class);assertThat(row(s).getStatus()).isEqualTo("CANCELLED");
    }
    @Test void waitingAttemptExpiresButNoApprovalOperationIsDeleted() {
        var s=start();row(s).setExpiresAt(Instant.now().minusSeconds(1));assertThat(service.get(null,7L,s.response().attemptId()).status()).isEqualTo("EXPIRED");assertThat(row(s).getProviderState()).isNull();
    }
    @Test void marketplaceReceiptRequiresSameBrowserAndIsConsumedWithoutGrantingIdentityAlone() {
        when(provider.verifyMarketplace("jwt")).thenReturn(new MarketplaceProof("seller-1",Instant.now().plusSeconds(300)));
        var r=service.marketplace("jwt");assertThatThrownBy(()->service.start(1L,7L,null,r.receiptId(),"wrong")).isInstanceOf(ApiException.class);
        var s=service.start(1L,7L,null,r.receiptId(),r.browserSecret());assertThat(row(s).getExpectedAccountUid()).isEqualTo("seller-1");assertThat(row(s).getStatus()).isEqualTo("WAITING_AUTH");
        assertThatThrownBy(()->service.start(1L,7L,null,r.receiptId(),r.browserSecret())).isInstanceOf(ApiException.class);verify(provider,never()).approve(any(),any(),any());
    }
    @Test void grantRevokedDuringApprovalResponseCannotBeRestoredByStaleActiveResult() {
        var s=review("proof");
        when(provider.approve(any(),any(),any())).thenAnswer(i->{var g=grantRows.values().iterator().next();g.setStatus("ENDED");g.setVersion(g.getVersion()+1);var result=active(i.getArgument(2));when(provider.querySubscription("seller-1")).thenReturn(result);return result;});
        assertThat(complete(s).status()).isEqualTo("RECONCILING");assertThat(grantRows.values()).singleElement().extracting(NaverSolutionSubscriptionEntity::getStatus).isEqualTo("ENDED");
        verify(connections,never()).save(any(),any(),any(),any(),any(),anyLong(),any(),any());
    }
    @Test void changedGrantVersionDuringReadReconciliationCannotBeOverwritten() {
        var s=review("proof");when(provider.approve(any(),any(),any())).thenThrow(new OutcomeUnknownException());complete(s);
        when(provider.querySubscription("seller-1")).thenAnswer(i->{var g=grantRows.values().iterator().next();g.setVersion(g.getVersion()+1);return active(g.getAccountMappingId());});
        assertThat(service.get(null,7L,s.response().attemptId()).status()).isEqualTo("RECONCILING");verify(connections,never()).save(any(),any(),any(),any(),any(),anyLong(),any(),any());
    }
    @Test void definitiveProviderRejectionIsFailedWithoutRetryOrConnectionWrites() {
        var s=review("proof");when(provider.approve(any(),any(),any())).thenThrow(new ApiException(com.orinan.api.common.code.ApiCode.BAD_REQUEST,"네이버에서 승인을 거절했습니다."));
        assertThat(complete(s).status()).isEqualTo("FAILED");assertThat(operationRows.values()).singleElement().extracting(NaverSubscriptionOperationEntity::getStatus).isEqualTo("REJECTED");
        service.get(null,7L,s.response().attemptId());verify(provider,times(1)).approve(any(),any(),any());verify(provider,never()).issueSellerToken(any());
    }
    @Test void freshReviewForRejectedLifecycleFailsWithoutSubmittingAnotherApproval() {
        var first=review("first-proof");
        when(provider.approve(any(),any(),any())).thenThrow(new ApiException(com.orinan.api.common.code.ApiCode.BAD_REQUEST,"네이버에서 승인을 거절했습니다."));
        assertThat(complete(first).status()).isEqualTo("FAILED");
        var second=review("fresh-proof");assertThat(row(second).getStatus()).isEqualTo("REVIEW_REQUIRED");
        var result=complete(second);
        assertThat(result.status()).isEqualTo("FAILED");assertThat(result.errorMessage()).contains("승인 요청이 거절", "다시 전송하지 않았습니다");
        assertThat(row(second).getEncryptedProof()).isNull();assertThat(operationRows).hasSize(1);
        verify(provider,times(1)).approve(any(),any(),any());verify(provider,never()).issueSellerToken(any());
    }
    @Test void missingVerifiedPlanCannotReachConfirmation() {
        when(provider.querySubscription("seller-1")).thenReturn(new Subscription("subscription-1","PENDING",null,null,"seller-1",null));var s=review("proof");
        assertThat(row(s).getStatus()).isEqualTo("FAILED");verify(provider,never()).approve(any(),any(),any());
    }
    @Test void pendingApprovalAllowedDoesNotIssueSellerTokenBeforeSubscriptionIsActive() {
        var s=review("proof");assertThat(proof.authenticated()).isFalse();assertThat(row(s).getStatus()).isEqualTo("REVIEW_REQUIRED");
        when(provider.approve(any(),any(),any())).thenThrow(new OutcomeUnknownException());complete(s);verify(provider,never()).issueSellerToken(any());
    }
    @Test void backgroundRecoveryUsesPersistedConfirmedAttemptAndNeverRepeatsApproval() {
        var s=review("proof");when(provider.approve(any(),any(),any())).thenThrow(new OutcomeUnknownException());complete(s);
        row(s).setEncryptedProof(null);row(s).setExpiresAt(Instant.now().minusSeconds(600));
        when(provider.querySubscription("seller-1")).thenReturn(active(grantRows.values().iterator().next().getAccountMappingId()));
        when(attempts.findRecoveryCandidates(any(),any(),any())).thenReturn(List.of(s.response().attemptId()));
        service.recoverPending();assertThat(row(s).getStatus()).isEqualTo("CONNECTED");assertThat(row(s).getRecoveryAttempts()).isEqualTo(1);
        verify(provider,times(1)).approve(any(),any(),any());
    }
    @Test void backgroundRecoveryBindsOwnerAndStopsAutomaticLoopAfterOwnerLoss() {
        var s=review("proof");when(provider.approve(any(),any(),any())).thenThrow(new OutcomeUnknownException());complete(s);
        when(attempts.findRecoveryCandidates(any(),any(),any())).thenReturn(List.of(s.response().attemptId()));when(attempts.findCurrentOwnerId(1L)).thenReturn(Optional.of(9L));
        clearInvocations(provider);service.recoverPending();service.recoverPending();
        assertThat(row(s).getRecoveryAttempts()).isEqualTo(20);assertThat(row(s).getStatus()).isEqualTo("RECONCILING");verify(provider,never()).querySubscription(any());verify(provider,never()).approve(any(),any(),any());
    }
    @Test void disabledBackgroundRecoveryDoesNotQueryTables() {
        when(provider.ready()).thenReturn(false);service.recoverPending();verifyNoInteractions(attempts,subscriptions,operations);
    }
    private NaverAuthorizationService.Started start(){return service.start(1L,7L,null,null,null);}
    private NaverAuthorizationAttemptEntity row(NaverAuthorizationService.Started s){return attemptRows.get(s.response().attemptId());}
    private String ticket(NaverAuthorizationService.Started s){return s.response().launchUrl().split("ticket=")[1];}
    private NaverAuthorizationService.Started review(String jwe){var s=start();String state=row(s).getProviderState();service.launch(ticket(s),s.browserSecret());service.callback(state,jwe,s.browserSecret());return s;}
    private NaverAuthorizationResponse complete(NaverAuthorizationService.Started s){return service.complete(1L,7L,s.response().attemptId(),s.browserSecret(),1,"idempotency-key-0001");}
    private Subscription active(String mapping){return new Subscription("subscription-1","ACTIVE","기본 요금제",mapping,"seller-1","plan-1");}
}
