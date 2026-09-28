package com.orinan.api.domain.platformconnection.naver.authorization;

import com.orinan.api.common.code.ApiCode;
import com.orinan.api.common.exception.ApiException;
import com.orinan.api.domain.platformconnection.naver.solution.NaverAuthorizationProvider;
import com.orinan.api.domain.platformconnection.naver.solution.NaverAuthorizationProvider.SellerProof;
import com.orinan.api.domain.platformconnection.naver.solution.NaverAuthorizationProvider.Subscription;
import com.orinan.api.domain.platformconnection.service.PlatformConnectionService;
import com.orinan.db.naverauthorization.*;
import com.orinan.db.naversolution.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;

@Service @RequiredArgsConstructor
public class NaverAuthorizationService {
    private final NaverAuthorizationAttemptRepository attempts;
    private final NaverMarketplaceReceiptRepository receipts;
    private final NaverSolutionSubscriptionRepository subscriptions;
    private final NaverSubscriptionOperationRepository operations;
    private final PlatformConnectionService permissions;
    private final NaverAuthorizationConnectionBridge connections;
    private final NaverAuthorizationProvider provider;
    private final TransactionTemplate transactions;
    private final jakarta.persistence.EntityManager entityManager;
    private static final SecureRandom RANDOM=new SecureRandom();
    private static final Set<String> BEFORE=Set.of("WAITING_AUTH","VALIDATING","REVIEW_REQUIRED");
    private static final Set<String> IN_PROGRESS=Set.of("APPROVING","RECONCILING","VERIFYING_CONNECTION");

    public NaverAuthorizationResponse.Capabilities capabilities(Long workspaceId,Long userId) {
        permissions.requireMember(workspaceId,userId);
        boolean ready=provider.ready();
        return new NaverAuthorizationResponse.Capabilities("SOLUTION_OAUTH",ready,
                ready?null:"네이버 커머스솔루션 등록과 인증 설정을 완료한 후 사용할 수 있습니다.",true);
    }
    public Started start(Long workspaceId,Long userId,Long reconnectId,String receiptId,String receiptBrowser) {
        ready(); owner(workspaceId,userId);
        String expected=reconnectId==null?null:connections.reconnectAccountUid(workspaceId,userId,reconnectId);
        String id=random(), browser=random(), ticket=id+"."+random(), state=id+"."+random();
        return transactions.execute(tx->{
            owner(workspaceId,userId);
            var a=new NaverAuthorizationAttemptEntity();
            a.setId(id); a.setWorkspaceId(workspaceId); a.setUserId(userId); a.setReconnectConnectionId(reconnectId);
            a.setExpectedAccountUid(expected);
            if(receiptId!=null) {
                var r=receipts.findByIdForUpdate(receiptId).orElseThrow(()->error(NaverAuthorizationCode.NOT_FOUND));
                if(r.isConsumed()||!r.getExpiresAt().isAfter(now())||!matches(r.getBrowserHash(),receiptBrowser)) throw error(NaverAuthorizationCode.FORBIDDEN);
                if(expected!=null&&!expected.equals(r.getAccountUid())) throw mismatch();
                a.setExpectedAccountUid(r.getAccountUid()); r.setConsumed(true); receipts.save(r);
            }
            a.setStatus("WAITING_AUTH"); a.setBrowserHash(hash(browser)); a.setStateHash(hash(state)); a.setProviderState(state);
            a.setLaunchHash(hash(ticket)); a.setLaunchExpiresAt(now().plusSeconds(60)); a.setExpiresAt(now().plusSeconds(600));
            a.setCreatedAt(now()); a.setUpdatedAt(now()); attempts.saveAndFlush(a);
            return new Started(response(a,launchUrl(ticket)),browser);
        });
    }
    public NaverAuthorizationResponse reissue(Long workspaceId,Long userId,String id,String browser) {
        ready();
        return owned(workspaceId,userId,id,browser,a->{
            if(!"WAITING_AUTH".equals(a.getStatus())) throw error(NaverAuthorizationCode.CONFLICT);
            String ticket=id+"."+random(),state=id+"."+random();
            a.setLaunchHash(hash(ticket));a.setLaunchExpiresAt(now().plusSeconds(60));a.setProviderState(state);a.setStateHash(hash(state));
            return response(a,launchUrl(ticket));
        });
    }
    public String launch(String ticket,String browser) {
        ready();
        if(ticket==null||ticket.length()>180) throw error(NaverAuthorizationCode.NOT_FOUND);
        return transactions.execute(tx->{
            var found=attempts.findByLaunchHash(hash(ticket)).orElseThrow(()->error(NaverAuthorizationCode.NOT_FOUND));
            var a=lock(found.getId()); bound(a,browser); expire(a);
            if(!"WAITING_AUTH".equals(a.getStatus())||!hash(ticket).equals(a.getLaunchHash())||!a.getLaunchExpiresAt().isAfter(now())) throw error(NaverAuthorizationCode.CONFLICT);
            owner(a.getWorkspaceId(),a.getUserId());
            String location=provider.launchUri(a.getProviderState(),provider.publicBaseUrl()+"/open-api/integrations/naver/callback").toString();
            a.setLaunchHash(null);a.setProviderState(null);a.setUpdatedAt(now());attempts.saveAndFlush(a);
            return location;
        });
    }
    public String callback(String state,String jwe,String browser) {
        ready(); String id=stateId(state);
        var claimed=transactions.execute(tx->{
            var a=lock(id);bound(a,browser);expire(a);
            if(!matches(a.getStateHash(),state)||a.getLaunchHash()!=null||!"WAITING_AUTH".equals(a.getStatus())) throw error(NaverAuthorizationCode.CONFLICT);
            owner(a.getWorkspaceId(),a.getUserId());
            if(jwe==null||jwe.isBlank()||jwe.length()>32768) throw new ApiException(ApiCode.BAD_REQUEST,"네이버 인증 응답을 확인할 수 없습니다.");
            String proofHash=hash(jwe);
            if(attempts.existsByProofHash(proofHash)) throw error(NaverAuthorizationCode.CONFLICT);
            a.setProofHash(proofHash);a.setEncryptedProof(jwe);a.setStatus("VALIDATING");a.setUpdatedAt(now());attempts.saveAndFlush(a);return a;
        });
        try {
            var proof=provider.interpretProof(jwe);var fence=grantFence(proof.accountUid());var subscription=provider.querySubscription(proof.accountUid());
            transactions.executeWithoutResult(tx->{
                var a=lock(id);
                if(!"VALIDATING".equals(a.getStatus())||!matches(a.getStateHash(),state)) return;
                if(!a.getExpiresAt().isAfter(now())) { terminal(a,"EXPIRED",null);return; }
                owner(a.getWorkspaceId(),a.getUserId());
                validateProof(a,proof,subscription);
                var grant=syncGrant(proof.accountUid(),subscription,fence);
                applyReview(a,proof,subscription,grant);
            });
        } catch(RuntimeException exception) {
            transactions.executeWithoutResult(tx->{var a=lock(claimed.getId());if("VALIDATING".equals(a.getStatus())) terminal(a,"FAILED",safe(exception,"네이버 판매자 인증을 완료하지 못했습니다. 연결을 다시 시작해 주세요."));});
        }
        return id;
    }
    /** Bounded read-only provider reconciliation; approval dispatch is exclusive to complete(). */
    public void recoverPending() {
        if(!provider.ready()) return;
        Instant current=now();
        var ids=attempts.findRecoveryCandidates(current.minusSeconds(60),current,org.springframework.data.domain.PageRequest.of(0,20));
        for(String id:ids) {
            try {
                var snapshot=transactions.execute(tx->{
                    var a=lock(id);
                    if(!IN_PROGRESS.contains(a.getStatus())||a.getConfirmedAt()==null||a.getRecoveryAttempts()>=20||(a.getNextReconcileAt()!=null&&a.getNextReconcileAt().isAfter(now()))) return null;
                    a.setRecoveryAttempts(a.getRecoveryAttempts()+1);
                    a.setNextReconcileAt(now().plusSeconds(Math.min(3600,60L << Math.min(a.getRecoveryAttempts()-1,6))));
                    a.setUpdatedAt(now());
                    attempts.saveAndFlush(a);return a;
                });
                if(snapshot!=null) {
                    try {owner(snapshot.getWorkspaceId(),snapshot.getUserId());}
                    catch(ApiException permissionFailure) {
                        transactions.executeWithoutResult(tx->{
                            var a=lock(id);a.setRecoveryAttempts(20);
                            a.setErrorMessage("워크스페이스 소유자 권한을 확인할 수 없어 자동 결과 확인을 중단했습니다. 구독 승인 결과는 보존됩니다.");
                            attempts.saveAndFlush(a);
                        });
                        continue;
                    }
                    reconcile(snapshot);
                }
            } catch(RuntimeException ignored) {
                // One malformed/deleted record must not stop other durable recovery jobs.
                // Each claimed job already persisted its next retry before any provider call.
            }
        }
    }
    public NaverAuthorizationResponse get(Long workspaceId,Long userId,String id) {
        ready(); var a=owned(workspaceId,userId,id,null,Function.identity());
        if(IN_PROGRESS.contains(a.getStatus())) reconcile(a);
        return owned(workspaceId,userId,id,null,current->response(current,null));
    }
    public NaverAuthorizationResponse cancel(Long workspaceId,Long userId,String id,String browser) {
        ready();return owned(workspaceId,userId,id,browser,a->{if(!BEFORE.contains(a.getStatus())) throw error(NaverAuthorizationCode.CONFLICT);terminal(a,"CANCELLED",null);return response(a,null);});
    }
    public NaverAuthorizationResponse complete(Long workspaceId,Long userId,String id,String browser,long revision,String key) {
        ready(); if(key==null||!key.matches("[A-Za-z0-9_-]{16,128}")) throw new ApiException(ApiCode.BAD_REQUEST,"유효한 Idempotency-Key가 필요합니다.");
        var current=owned(workspaceId,userId,id,browser,a->{
            if(a.getIdempotencyHash()!=null&&a.getIdempotencyHash().equals(hash(key))&&!Objects.equals(a.getConfirmationHash(),hash(Long.toString(revision)))) throw error(NaverAuthorizationCode.CONFLICT);
            return a;
        });
        if(IN_PROGRESS.contains(current.getStatus())||"CONNECTED".equals(current.getStatus())) return get(workspaceId,userId,id);
        if(!"REVIEW_REQUIRED".equals(current.getStatus())||current.getReviewRevision()!=revision) throw error(NaverAuthorizationCode.CONFLICT);
        var fence=grantFence(current.getAccountUid());var proof=provider.interpretProof(current.getEncryptedProof());var remote=provider.querySubscription(proof.accountUid());
        var claim=owned(workspaceId,userId,id,browser,a->{
            if(!"REVIEW_REQUIRED".equals(a.getStatus())) return new Claim(a,false);
            validateProof(a,proof,remote);
            var grant=syncGrant(proof.accountUid(),remote,fence);
            if(a.getReviewRevision()!=revision||!sameReview(a,proof,remote)) { applyReview(a,proof,remote,grant);return new Claim(a,false); }
            a.setConfirmedAt(now());a.setIdempotencyHash(hash(key));a.setConfirmationHash(hash(Long.toString(revision)));
            a.setSubscriptionId(grant.getId());a.setSubscriptionGeneration(grant.getGeneration());
            if("ACTIVE".equals(remote.status())) { a.setStatus("VERIFYING_CONNECTION");return new Claim(a,false); }
            String operationId=hash(provider.applicationRef()+"\n"+proof.accountUid()+"\n"+remote.subscriptionId());
            a.setOperationId(operationId);
            var operation=operations.findById(operationId).orElse(null);
            if(operation!=null) {
                if("REJECTED".equals(operation.getStatus())) {
                    terminal(a,"FAILED","이 구독의 사용 승인 요청이 거절되었습니다. 네이버 신청 내역을 확인해 주세요. 승인 요청은 다시 전송하지 않았습니다.");
                } else {
                    a.setStatus("APPROVED".equals(operation.getStatus())?"VERIFYING_CONNECTION":"RECONCILING");
                }
                return new Claim(a,false);
            }
            operation=new NaverSubscriptionOperationEntity();operation.setId(operationId);operation.setApplicationRef(provider.applicationRef());
            operation.setAccountUid(proof.accountUid());operation.setProviderSubscriptionId(remote.subscriptionId());operation.setAccountMappingId(grant.getAccountMappingId());
            operation.setOwnerAttemptId(a.getId());operation.setStatus("DISPATCHING");operation.setCreatedAt(now());operation.setUpdatedAt(now());operations.saveAndFlush(operation);
            a.setStatus("APPROVING");return new Claim(a,true,grant.getVersion());
        });
        if(claim.dispatch()) {
            boolean approvalReturned=false;
            try {
                owner(workspaceId,userId);
                var grant=transactions.execute(tx->{
                    var a=lock(id);owner(workspaceId,userId);
                    var g=subscriptions.findByIdForUpdate(a.getSubscriptionId()).orElseThrow(()->error(NaverAuthorizationCode.CONFLICT));entityManager.refresh(g);
                    if(g.getGeneration()!=a.getSubscriptionGeneration()||!"PENDING".equals(g.getStatus())||g.getVersion()!=claim.grantVersion()) throw error(NaverAuthorizationCode.CONFLICT);
                    return g;
                });
                var approvalFence=new GrantFence(grant.getId(),grant.getVersion(),grant.getGeneration());
                var result=provider.approve(current.getEncryptedProof(),proof,grant.getAccountMappingId());
                approvalReturned=true;
                recordApproval(claim.attempt(),result,approvalFence);
            } catch(ApiException exception) {
                if(approvalReturned || exception instanceof NaverAuthorizationProvider.OutcomeUnknownException || exception.getCodeIfs().getHttpStatusCode()>=500) {
                    markReconciling(id,"네이버 승인 결과를 확인 중입니다. 승인을 다시 요청하지 않습니다.");
                } else {
                    transactions.executeWithoutResult(tx->{
                        var a=lock(id);
                        if("APPROVING".equals(a.getStatus())) {
                            if(a.getOperationId()!=null) {var op=operations.findById(a.getOperationId()).orElseThrow();op.setStatus("REJECTED");op.setUpdatedAt(now());operations.save(op);}
                            terminal(a,"FAILED",exception.getDescription());
                        }
                    });
                }
            } catch(RuntimeException exception) {
                // Once DISPATCHING is durable, no failure path resends this approval.
                markReconciling(id,"네이버 승인 결과를 확인 중입니다. 승인을 다시 요청하지 않습니다.");
            }
        }
        var latest=owned(workspaceId,userId,id,null,Function.identity());
        if(IN_PROGRESS.contains(latest.getStatus())) reconcile(latest);
        return owned(workspaceId,userId,id,null,a->response(a,null));
    }
    private void reconcile(NaverAuthorizationAttemptEntity snapshot) {
        try {
            owner(snapshot.getWorkspaceId(),snapshot.getUserId());
            var fence=grantFence(snapshot.getAccountUid());
            var remote=provider.querySubscription(snapshot.getAccountUid());
            if(!Objects.equals(snapshot.getProviderSubscriptionId(),remote.subscriptionId())||!Objects.equals(snapshot.getAccountUid(),remote.accountUid())) {markReconciling(snapshot.getId(),"검토한 구독과 현재 구독이 달라 연결을 완료할 수 없습니다.");return;}
            if(!"ACTIVE".equals(remote.status())) {markReconciling(snapshot.getId(),"네이버에서 승인 상태를 확인 중입니다. 확인이 끝날 때까지 기다려 주세요.");return;}
            recordApproval(snapshot,remote,fence);
            var a=owned(snapshot.getWorkspaceId(),snapshot.getUserId(),snapshot.getId(),null,Function.identity());
            if(!"VERIFYING_CONNECTION".equals(a.getStatus())) return;
            var token=provider.issueSellerToken(a.getAccountUid());var account=provider.getSellerAccount(token.accessToken());
            if(!a.getAccountUid().equals(account.accountUid())) throw mismatch();
            owner(a.getWorkspaceId(),a.getUserId());
            transactions.executeWithoutResult(tx->{
                var locked=lock(a.getId()); if(!"VERIFYING_CONNECTION".equals(locked.getStatus())) return;
                owner(locked.getWorkspaceId(),locked.getUserId());
                var grant=subscriptions.findByIdForUpdate(locked.getSubscriptionId()).orElseThrow(()->error(NaverAuthorizationCode.CONFLICT));
                if(!"ACTIVE".equals(grant.getStatus())||grant.getGeneration()!=locked.getSubscriptionGeneration()||!grant.getProviderSubscriptionId().equals(locked.getProviderSubscriptionId())) throw error(NaverAuthorizationCode.CONFLICT);
                Long connectionId=connections.save(locked.getWorkspaceId(),locked.getUserId(),locked.getReconnectConnectionId(),provider.applicationRef(),grant.getId(),grant.getGeneration(),token,account);
                locked.setConnectionId(connectionId);terminal(locked,"CONNECTED",null);
            });
        } catch(RuntimeException exception) {
            markReconciling(snapshot.getId(),"구독 승인 또는 연결 저장 결과를 확인 중입니다. 잠시 후 현재 상태를 확인해 주세요.");
        }
    }
    private void recordApproval(NaverAuthorizationAttemptEntity snapshot,Subscription remote,GrantFence fence) {
        transactions.executeWithoutResult(tx->{
            var a=lock(snapshot.getId());if(!IN_PROGRESS.contains(a.getStatus())) return;
            var grant=subscriptions.findByIdForUpdate(a.getSubscriptionId()).orElseThrow(()->error(NaverAuthorizationCode.CONFLICT));entityManager.refresh(grant);
            requireFence(grant,fence);
            if(Set.of("ENDED","UNKNOWN").contains(grant.getStatus())||!"ACTIVE".equals(remote.status())||!a.getProviderSubscriptionId().equals(remote.subscriptionId())||!a.getAccountUid().equals(remote.accountUid())||grant.getGeneration()!=a.getSubscriptionGeneration()) throw error(NaverAuthorizationCode.CONFLICT);
            if(!Objects.equals(grant.getAccountMappingId(),remote.accountMappingId())) throw error(NaverAuthorizationCode.CONFLICT);
            grant.setStatus("ACTIVE");grant.setVerifiedAt(now());subscriptions.save(grant);
            if(a.getOperationId()!=null) {var op=operations.findById(a.getOperationId()).orElseThrow();op.setStatus("APPROVED");op.setUpdatedAt(now());operations.save(op);}
            a.setStatus("VERIFYING_CONNECTION");a.setErrorMessage(null);a.setUpdatedAt(now());attempts.save(a);
        });
    }
    private void markReconciling(String id,String message) {
        transactions.executeWithoutResult(tx->{var a=lock(id);if(IN_PROGRESS.contains(a.getStatus())) {a.setStatus("RECONCILING");a.setErrorMessage(message);a.setUpdatedAt(now());attempts.save(a);}});
    }
    private NaverSolutionSubscriptionEntity syncGrant(String uid,Subscription remote,GrantFence fence) {
        var grant=subscriptions.findByApplicationRefAndAccountUidForUpdate(provider.applicationRef(),uid).orElse(null);
        if(grant!=null) entityManager.refresh(grant);requireFence(grant,fence);
        if(grant==null) {grant=new NaverSolutionSubscriptionEntity();grant.setApplicationRef(provider.applicationRef());grant.setSolutionId(provider.solutionId());grant.setAccountUid(uid);grant.setProviderSubscriptionId(remote.subscriptionId());grant.setAccountMappingId(mapping(remote));}
        else if(!grant.getProviderSubscriptionId().equals(remote.subscriptionId())) {grant.setProviderSubscriptionId(remote.subscriptionId());grant.setGeneration(grant.getGeneration()+1);grant.setAccountMappingId(mapping(remote));}
        else if("ENDED".equals(grant.getStatus()) || ("ACTIVE".equals(remote.status())&&!Objects.equals(grant.getAccountMappingId(),remote.accountMappingId()))) throw error(NaverAuthorizationCode.CONFLICT);
        grant.setStatus(remote.status());grant.setVerifiedAt(now());return subscriptions.saveAndFlush(grant);
    }
    private String mapping(Subscription remote) {
        if(remote.accountMappingId()!=null&&!remote.accountMappingId().isBlank()) return remote.accountMappingId();
        if("ACTIVE".equals(remote.status())) throw new ApiException(ApiCode.BAD_REQUEST,"네이버 구독의 연결 식별자를 확인할 수 없습니다.");
        return random();
    }
    private void validateProof(NaverAuthorizationAttemptEntity a,SellerProof proof,Subscription remote) {
        if(proof.accountUid()==null||proof.accountUid().isBlank()||remote.subscriptionId()==null||remote.subscriptionId().isBlank()||!Objects.equals(proof.accountUid(),remote.accountUid())||!Objects.equals(proof.subscriptionId(),remote.subscriptionId())) throw new ApiException(ApiCode.BAD_REQUEST,"검증된 네이버 구독 신청을 확인할 수 없습니다.");
        if(remote.planId()==null||remote.planId().isBlank()||remote.planName()==null||remote.planName().isBlank()) throw new ApiException(ApiCode.BAD_REQUEST,"네이버 신청 요금제를 확인할 수 없습니다. 신청 내역을 확인하고 다시 연결해 주세요.");
        if(a.getExpectedAccountUid()!=null&&!a.getExpectedAccountUid().equals(proof.accountUid())) throw mismatch();
        if("ACTIVE".equals(remote.status())) {if(!proof.authenticated()) throw new ApiException(ApiCode.BAD_REQUEST,"네이버 판매자 인증을 다시 진행해 주세요.");}
        else if(!"PENDING".equals(remote.status())||!proof.approvalAllowed()) throw new ApiException(ApiCode.BAD_REQUEST,"네이버 솔루션 신청과 사용 승인 가능 여부를 확인해 주세요.");
    }
    private boolean sameReview(NaverAuthorizationAttemptEntity a,SellerProof proof,Subscription remote) {
        return Objects.equals(a.getAccountUid(),proof.accountUid())&&Objects.equals(a.getProviderSubscriptionId(),remote.subscriptionId())&&Objects.equals(a.getPlanId(),remote.planId())&&Objects.equals(a.getPlanName(),remote.planName())&&a.isRequiresApproval()!= "ACTIVE".equals(remote.status());
    }
    private void applyReview(NaverAuthorizationAttemptEntity a,SellerProof proof,Subscription remote,NaverSolutionSubscriptionEntity grant) {
        a.setAccountUid(proof.accountUid());a.setAccountId(proof.accountId());a.setSellerName(proof.sellerName());a.setStoreUrl(proof.storeUrl());
        a.setProviderSubscriptionId(remote.subscriptionId());a.setPlanName(remote.planName());a.setPlanId(remote.planId());a.setRequiresApproval(!"ACTIVE".equals(remote.status()));
        a.setSubscriptionId(grant.getId());a.setSubscriptionGeneration(grant.getGeneration());a.setReviewRevision(a.getReviewRevision()+1);a.setStatus("REVIEW_REQUIRED");a.setUpdatedAt(now());attempts.save(a);
    }
    public MarketplaceReceived marketplace(String jwt) {
        ready();var proof=provider.verifyMarketplace(jwt);String id=random(),browser=random();
        var receipt=new NaverMarketplaceReceiptEntity();receipt.setId(id);receipt.setBrowserHash(hash(browser));receipt.setAccountUid(proof.accountUid());
        receipt.setExpiresAt(proof.expiresAt().isBefore(now().plusSeconds(600))?proof.expiresAt():now().plusSeconds(600));receipts.saveAndFlush(receipt);
        return new MarketplaceReceived(id,browser);
    }
    private <T>T owned(Long workspaceId,Long userId,String id,String browser,Function<NaverAuthorizationAttemptEntity,T> action) {
        return transactions.execute(tx->{var a=lock(id);if(!a.getUserId().equals(userId)||(workspaceId!=null&&!a.getWorkspaceId().equals(workspaceId))) throw error(NaverAuthorizationCode.NOT_FOUND);
            owner(a.getWorkspaceId(),userId);if(browser!=null) bound(a,browser);expire(a);T value=action.apply(a);a.setUpdatedAt(now());attempts.saveAndFlush(a);return value;});
    }
    private void expire(NaverAuthorizationAttemptEntity a) {if(BEFORE.contains(a.getStatus())&&!a.getExpiresAt().isAfter(now())) terminal(a,"EXPIRED",null);}
    private void terminal(NaverAuthorizationAttemptEntity a,String status,String message) {a.setStatus(status);a.setErrorMessage(message);a.setEncryptedProof(null);a.setProviderState(null);a.setLaunchHash(null);a.setUpdatedAt(now());attempts.save(a);}
    private void bound(NaverAuthorizationAttemptEntity a,String browser) {if(!matches(a.getBrowserHash(),browser)) throw error(NaverAuthorizationCode.FORBIDDEN);}
    private NaverAuthorizationAttemptEntity lock(String id) {if(id==null||!id.matches("[A-Za-z0-9_-]{32,64}")) throw error(NaverAuthorizationCode.NOT_FOUND);var a=attempts.findByIdForUpdate(id).orElseThrow(()->error(NaverAuthorizationCode.NOT_FOUND));entityManager.refresh(a);return a;}
    private void owner(Long workspaceId,Long userId) {
        permissions.requireOwner(workspaceId,userId);
        if(!attempts.findCurrentOwnerId(workspaceId).filter(userId::equals).isPresent()) throw error(NaverAuthorizationCode.FORBIDDEN);
    }
    private void ready() {if(!provider.ready()) throw error(NaverAuthorizationCode.UNAVAILABLE);}
    private String launchUrl(String ticket) {return provider.publicBaseUrl()+"/open-api/integrations/naver/launch?ticket="+ticket;}
    public String resultUrl(String id) {return provider.frontendBaseUrl()+"/settings/integrations/naver/callback?attempt_id="+id;}
    public String marketplaceResultUrl(String id) {return provider.frontendBaseUrl()+"/settings/integrations/naver/callback?marketplace_receipt="+id;}
    public String failureUrl() {return provider.frontendBaseUrl()+"/settings/integrations/naver/callback?error=naver_authentication_failed";}
    public String publicOrigin() {return provider.publicBaseUrl();}
    public static String stateId(String state) {if(state==null||!state.matches("[A-Za-z0-9_-]{32,64}\\.[A-Za-z0-9_-]{32,64}")) throw error(NaverAuthorizationCode.NOT_FOUND);return state.substring(0,state.indexOf('.'));}
    private NaverAuthorizationResponse response(NaverAuthorizationAttemptEntity a,String launchUrl) {
        String action=switch(a.getStatus()) {case "WAITING_AUTH"->"OPEN_AUTHORIZATION";case "REVIEW_REQUIRED"->"CONFIRM_CONNECTION";case "CONNECTED"->"SELECT_CHANNELS";case "APPROVING","RECONCILING","VERIFYING_CONNECTION","VALIDATING"->"WAIT";default->"RESTART";};
        return new NaverAuthorizationResponse(a.getId(),a.getWorkspaceId(),a.getStatus(),a.getReviewRevision(),a.getAccountUid()==null?null:new NaverAuthorizationResponse.Seller(a.getSellerName(),a.getStoreUrl()),
                a.getAccountUid()==null?null:new NaverAuthorizationResponse.Subscription(a.isRequiresApproval(),a.getPlanName(),a.isRequiresApproval()?"신청한 요금제에 따라 네이버에서 결제가 발생할 수 있습니다. 정확한 금액은 네이버 신청 내역을 확인해 주세요.":"이미 사용 승인된 구독입니다. 새 사용 승인을 요청하지 않습니다."),a.getConnectionId(),action,a.getExpiresAt(),launchUrl,a.getErrorMessage());
    }
    private static Instant now(){return Instant.now();}
    private static String random(){byte[] bytes=new byte[32];RANDOM.nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    private static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException("인증 식별자 생성에 실패했습니다.");}}
    private static boolean matches(String expected,String raw){return expected!=null&&raw!=null&&MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),hash(raw).getBytes(StandardCharsets.US_ASCII));}
    private static ApiException error(NaverAuthorizationCode code){return new ApiException(code);}
    private static ApiException mismatch(){return new ApiException(ApiCode.BAD_REQUEST,"다른 판매자로 인증되었습니다. 기존 연결의 판매자로 다시 인증해 주세요.");}
    private static String safe(RuntimeException exception,String fallback){return exception instanceof ApiException api?api.getDescription():fallback;}
    public record Started(NaverAuthorizationResponse response,String browserSecret) { @Override public String toString(){return "Started[REDACTED]";} }
    public record MarketplaceReceived(String receiptId,String browserSecret) { @Override public String toString(){return "MarketplaceReceived[REDACTED]";} }
    private record Claim(NaverAuthorizationAttemptEntity attempt,boolean dispatch,long grantVersion) {
        Claim(NaverAuthorizationAttemptEntity attempt,boolean dispatch){this(attempt,dispatch,-1);}
    }
    private record GrantFence(Long id,long version,long generation) {}
    private GrantFence grantFence(String uid) {
        return transactions.execute(tx->{
            var grant=subscriptions.findByApplicationRefAndAccountUidForUpdate(provider.applicationRef(),uid).orElse(null);
            if(grant==null) return new GrantFence(null,0,0);
            entityManager.refresh(grant);return new GrantFence(grant.getId(),grant.getVersion(),grant.getGeneration());
        });
    }
    private void requireFence(NaverSolutionSubscriptionEntity grant,GrantFence fence) {
        if(grant==null) {if(fence.id()!=null) throw error(NaverAuthorizationCode.CONFLICT);return;}
        if(!Objects.equals(grant.getId(),fence.id())||grant.getVersion()!=fence.version()||grant.getGeneration()!=fence.generation()) throw error(NaverAuthorizationCode.CONFLICT);
    }
}
