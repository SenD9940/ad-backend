package com.orinan.adminapi.domain.audit.service;

import com.orinan.adminapi.common.exception.AdminException;
import com.orinan.db.adminaudit.AdminAuditEntity;
import com.orinan.db.adminaudit.AdminAuditRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import static org.springframework.http.HttpStatus.BAD_REQUEST;

class AdminAuditServiceTest {
    private final AdminAuditRepository repository = mock(AdminAuditRepository.class);
    private final AdminAuditService service = new AdminAuditService(repository);

    @Test
    void optionalMemoDefaultsToActionAndPreservesActorTargetAndBeforeAfterValues() {
        service.record(1, "USER_STATUS_CHANGED", "USER", 3L, null, "REGISTERED", "SUSPENDED");

        var saved = ArgumentCaptor.forClass(AdminAuditEntity.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getReason()).isEqualTo("회원 정지");
        assertThat(saved.getValue().getActorUserId()).isEqualTo(1);
        assertThat(saved.getValue().getAction()).isEqualTo("USER_STATUS_CHANGED");
        assertThat(saved.getValue().getTargetType()).isEqualTo("USER");
        assertThat(saved.getValue().getTargetId()).isEqualTo(3);
        assertThat(saved.getValue().getBeforeValue()).isEqualTo("REGISTERED");
        assertThat(saved.getValue().getAfterValue()).isEqualTo("SUSPENDED");
    }

    @Test
    void providedMemoIsTrimmedAndPreservedIncludingOtherDomainsActions() {
        service.record(1, "CUSTOM_ACTION", "USER", 3L, "  고객 요청\n추가 설명  ", null, null);
        service.record(1, "CUSTOM_ACTION", "USER", 3L, "x".repeat(500), null, null);

        var saved = ArgumentCaptor.forClass(AdminAuditEntity.class);
        verify(repository, times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(AdminAuditEntity::getReason)
                .containsExactly("고객 요청\n추가 설명", "x".repeat(500));
    }

    @Test
    void unknownActionsWithoutMemoAndInvalidMetadataAreStillRejected() {
        assertBadRequest(() -> service.record(1, "CUSTOM_ACTION", "USER", 3L, null, null, null));
        assertBadRequest(() -> service.record(1, "CUSTOM_ACTION", "USER", 3L, "  ", null, null));
        assertBadRequest(() -> service.record(0, "USER_SESSIONS_REVOKED", "USER", 3L, null, null, null));
        assertBadRequest(() -> service.record(1, null, "USER", 3L, null, null, null));
        assertBadRequest(() -> service.record(1, "invalid-action", "USER", 3L, "메모", null, null));
        assertBadRequest(() -> service.record(1, "USER_SESSIONS_REVOKED", "invalid", 3L, null, null, null));
        assertBadRequest(() -> service.record(1, "USER_SESSIONS_REVOKED", "USER", 3L, null, "x".repeat(1001), null));
        assertBadRequest(() -> service.record(1, "USER_SESSIONS_REVOKED", "USER", 3L, null, null, "x".repeat(1001)));
        assertBadRequest(() -> service.record(1, "USER_SESSIONS_REVOKED", "USER", 3L, "x".repeat(501), null, null));
        assertBadRequest(() -> service.record(1, "USER_SESSIONS_REVOKED", "USER", 3L, " ".repeat(501), null, null));
        verifyNoInteractions(repository);
    }

    private void assertBadRequest(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(AdminException.class,
                error -> assertThat(error.status()).isEqualTo(BAD_REQUEST));
    }
}
