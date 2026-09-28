package com.orinan.api.domain.platformconnection.naver.events;

import com.orinan.api.common.time.SeoulDateTimes;
import com.orinan.api.domain.platformconnection.naver.solution.NaverAuthorizationProvider;
import com.orinan.api.domain.platformconnection.naver.solution.NaverSolutionProperties;
import com.orinan.db.naverconnection.NaverConnectionEntity;
import com.orinan.db.naverconnection.NaverConnectionRepository;
import com.orinan.db.naverconnection.enums.NaverCredentialSource;
import com.orinan.db.naversolution.NaverSolutionSubscriptionEntity;
import com.orinan.db.naversolution.NaverSolutionSubscriptionRepository;
import com.orinan.db.naversolutionevent.NaverSolutionEventEntity;
import com.orinan.db.naversolutionevent.NaverSolutionEventRepository;
import com.orinan.db.platformconnection.PlatformConnectionEntity;
import com.orinan.db.platformconnection.PlatformConnectionRepository;
import com.orinan.db.workspace.WorkspaceEntity;
import com.orinan.db.workspace.WorkspaceRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class NaverSolutionEventInboxTest {
    private final NaverSolutionEventRepository events = mock(NaverSolutionEventRepository.class);
    private final NaverSolutionSubscriptionRepository subscriptions = mock(NaverSolutionSubscriptionRepository.class);
    private final NaverConnectionRepository details = mock(NaverConnectionRepository.class);
    private final PlatformConnectionRepository connections = mock(PlatformConnectionRepository.class);
    private final NaverSolutionProperties properties = mock(NaverSolutionProperties.class);
    private final EntityManager manager = mock(EntityManager.class);
    private final WorkspaceRepository workspaces = mock(WorkspaceRepository.class);
    private final NaverSolutionEventInbox inbox = new NaverSolutionEventInbox(events,subscriptions,details,connections,properties,manager,workspaces);
    private NaverSolutionSubscriptionEntity subscription;
    private NaverSolutionEventEntity event;

    @BeforeEach void setUp() {
        when(properties.getApplicationRef()).thenReturn("application");
        subscription = new NaverSolutionSubscriptionEntity();
        subscription.setId(7L); subscription.setAccountUid("seller"); subscription.setApplicationRef("application");
        subscription.setProviderSubscriptionId("lifecycle-2"); subscription.setAccountMappingId("mapping-2");
        subscription.setGeneration(2); subscription.setVersion(12); subscription.setStatus("ACTIVE");
        when(subscriptions.findByApplicationRefAndAccountUidForUpdate("application","seller")).thenReturn(Optional.of(subscription));
        when(subscriptions.findByApplicationRefAndAccountUid("application","seller")).thenReturn(Optional.of(subscription));
        when(subscriptions.findByIdForUpdate(7L)).thenReturn(Optional.of(subscription));
        event = new NaverSolutionEventEntity(); event.setId(100L); event.setSolutionId("solution");
        event.setAccountUid("seller"); event.setAccountMappingId("mapping-2"); event.setChangeType("END_SUBSCRIPTION");
        when(events.findByIdForUpdate(100L)).thenReturn(Optional.of(event));
        when(events.findById(100L)).thenReturn(Optional.of(event));
        when(workspaces.findByIdForUpdate(anyLong())).thenAnswer(invocation ->
                Optional.of(WorkspaceEntity.builder().id(invocation.getArgument(0)).build()));
    }

    @Test void sameEventIdWithDifferentChangeTypesIsStoredAndOnlyExactDuplicatesAreSkipped() {
        var seen = new HashSet<String>();
        when(events.existsBySolutionIdAndEventIdAndChangeType(anyString(),anyString(),anyString()))
                .thenAnswer(invocation -> seen.contains(invocation.getArgument(1)+":"+invocation.getArgument(2)));
        when(events.saveAndFlush(any())).thenAnswer(invocation -> {
            NaverSolutionEventEntity value=invocation.getArgument(0);
            seen.add(value.getEventId()+":"+value.getChangeType()); return value;
        });
        var first = data("same-event","END_SUBSCRIPTION","mapping-2");
        var second = data("same-event","CHANGE_PLAN","mapping-2");
        inbox.accept(List.of(first,second,first));
        var captured=ArgumentCaptor.forClass(NaverSolutionEventEntity.class);
        verify(events,times(2)).saveAndFlush(captured.capture());
        assertThat(captured.getAllValues()).extracting(NaverSolutionEventEntity::getChangeType)
                .containsExactly("END_SUBSCRIPTION","CHANGE_PLAN");
        assertThat(subscription.getStatus()).isEqualTo("UNKNOWN");
        verifyNoInteractions(details,connections);
    }

    @Test void oldMappingEventCannotFenceOrClearNewlyConfirmedLifecycle() {
        inbox.accept(List.of(data("old-event","END_SUBSCRIPTION","mapping-1")));
        assertThat(subscription.getStatus()).isEqualTo("ACTIVE");
        event.setAccountMappingId("mapping-1");
        inbox.apply(check(),new NaverAuthorizationProvider.Subscription("lifecycle-1","ENDED","Plan","mapping-1","seller","plan"));
        assertThat(subscription.getStatus()).isEqualTo("ACTIVE");
        assertThat(event.getState()).isEqualTo("IGNORED");
        verifyNoInteractions(details,connections);
        verify(subscriptions,never()).saveAndFlush(any());
    }

    @Test void freshReadBackOfEndedSubscriptionClearsOnlyMatchingSolutionGeneration() {
        var matching=detail(20,10,NaverCredentialSource.SOLUTION,2);
        var manual=detail(21,11,NaverCredentialSource.MANUAL,2);
        var old=detail(22,12,NaverCredentialSource.SOLUTION,1);
        var anotherSubscription=detail(23,13,NaverCredentialSource.SOLUTION,2); anotherSubscription.setSolutionSubscriptionId(8L);
        when(details.findAllBySolutionSubscriptionId(7L)).thenReturn(List.of(matching,manual,old,anotherSubscription));
        inbox.apply(check(),remote("ENDED"));
        assertThat(subscription.getStatus()).isEqualTo("ENDED");
        assertThat(matching.getAccessToken()).isNull(); assertThat(matching.getExpiresAt()).isNull();
        assertThat(matching.getCredentialVersion()).isEqualTo(6);
        assertThat(matching.getConnection().getRequiresReauth()).isTrue();
        for(var unchanged:List.of(manual,old,anotherSubscription)) {
            assertThat(unchanged.getAccessToken()).isEqualTo("stored-token");
            assertThat(unchanged.getCredentialVersion()).isEqualTo(5);
            verify(details,never()).save(same(unchanged));
        }
        verify(details).save(same(matching)); assertThat(event.getState()).isEqualTo("PROCESSED");
    }

    @Test void refreshAfterWorkspaceLockPreservesConcurrentManualConversion() {
        var changed=detail(20,10,NaverCredentialSource.SOLUTION,2);
        when(details.findAllBySolutionSubscriptionId(7L)).thenReturn(List.of(changed));
        doAnswer(invocation -> {changed.setCredentialSource(NaverCredentialSource.MANUAL);changed.setAccessToken("new-manual-token");return null;})
                .when(manager).refresh(changed);
        inbox.apply(check(),remote("ENDED"));
        assertThat(changed.getAccessToken()).isEqualTo("new-manual-token");
        verify(details,never()).save(any()); verifyNoInteractions(connections);
        var ordered=inOrder(workspaces,manager);
        ordered.verify(workspaces).findByIdForUpdate(10L); ordered.verify(manager).refresh(changed);
    }

    @Test void remoteUnknownCannotReviveTokensAndRemainsPendingForReadOnlyReconciliation() {
        var matching=detail(20,10,NaverCredentialSource.SOLUTION,2);
        var manual=detail(21,11,NaverCredentialSource.MANUAL,2);
        when(details.findAllBySolutionSubscriptionId(7L)).thenReturn(List.of(matching,manual));
        inbox.apply(check(),remote("UNKNOWN"));
        assertThat(subscription.getStatus()).isEqualTo("UNKNOWN");
        assertThat(matching.getAccessToken()).isNull(); assertThat(manual.getAccessToken()).isEqualTo("stored-token");
        assertThat(event.getState()).isEqualTo("PENDING"); assertThat(event.getAttempts()).isEqualTo(1);
        assertThat(event.getNextAttemptAt()).isAfter(Instant.now());
    }

    @Test void laterActiveStatusNeverRestoresPreviouslyRemovedTokenOrOldGenerationAccess() {
        var revoked=detail(20,10,NaverCredentialSource.SOLUTION,2); revoked.setAccessToken(null); revoked.setExpiresAt(null);
        revoked.getConnection().setRequiresReauth(true);
        subscription.setStatus("UNKNOWN");
        inbox.apply(check(),remote("ACTIVE"));
        assertThat(subscription.getStatus()).isEqualTo("ACTIVE");
        assertThat(revoked.getAccessToken()).isNull(); assertThat(revoked.getConnection().getRequiresReauth()).isTrue();
        assertThat(event.getState()).isEqualTo("PROCESSED"); verifyNoInteractions(details,connections);
    }

    @Test void changedDatabaseGenerationOrVersionDiscardsStaleRemoteResult() {
        subscription.setVersion(13);
        inbox.apply(check(),remote("ENDED"));
        assertThat(subscription.getStatus()).isEqualTo("ACTIVE");
        assertThat(event.getState()).isEqualTo("PENDING"); assertThat(event.getNextAttemptAt()).isAfter(Instant.now());
        verify(subscriptions,never()).saveAndFlush(any()); verifyNoInteractions(details,connections);
    }

    @Test void mismatchedRemoteSellerOrLifecycleIsNeverTreatedAsActive() {
        inbox.apply(check(),new NaverAuthorizationProvider.Subscription("different-lifecycle","ACTIVE","Plan","mapping-2","seller","plan"));
        assertThat(subscription.getStatus()).isEqualTo("UNKNOWN");
        assertThat(event.getState()).isEqualTo("PENDING");
        inbox.apply(check(),new NaverAuthorizationProvider.Subscription("lifecycle-2","ACTIVE","Plan","mapping-2","other-seller","plan"));
        assertThat(subscription.getStatus()).isEqualTo("UNKNOWN");
    }

    @Test void unknownLocalSellerIsIgnoredAndProcessedEventsAreNotReapplied() {
        when(subscriptions.findByApplicationRefAndAccountUid("application","seller")).thenReturn(Optional.empty());
        var context=inbox.context(100L); assertThat(context.subscriptionId()).isNull();
        inbox.apply(context,null); assertThat(event.getState()).isEqualTo("IGNORED");
        inbox.apply(check(),remote("ENDED"));
        assertThat(subscription.getStatus()).isEqualTo("ACTIVE"); verifyNoInteractions(details,connections);
    }

    @Test void readFailuresBackOffWithoutTurningPendingEventIntoSuccess() {
        inbox.failed(100L); assertThat(event.getState()).isEqualTo("PENDING"); assertThat(event.getAttempts()).isEqualTo(1);
        assertThat(event.getNextAttemptAt()).isAfter(Instant.now());
        event.setState("PROCESSED"); inbox.failed(100L); assertThat(event.getAttempts()).isEqualTo(1);
    }

    private NaverSolutionEventData data(String id,String type,String mapping) {return new NaverSolutionEventData("solution",id,type,"seller",mapping);}
    private NaverSolutionEventInbox.Check check() {return new NaverSolutionEventInbox.Check(100L,"seller",7L,2,12);}
    private NaverAuthorizationProvider.Subscription remote(String status) {return new NaverAuthorizationProvider.Subscription("lifecycle-2",status,"Plan","mapping-2","seller","plan");}
    private NaverConnectionEntity detail(long id,long workspace,NaverCredentialSource source,long generation) {
        return NaverConnectionEntity.builder().connectionId(id).connection(PlatformConnectionEntity.builder().id(id)
                .workspace(WorkspaceEntity.builder().id(workspace).build()).requiresReauth(false).build())
                .solutionSubscriptionId(7L).boundSubscriptionGeneration(generation).credentialSource(source)
                .accessToken("stored-token").expiresAt(SeoulDateTimes.now().plusHours(1)).credentialVersion(5).build();
    }
}
