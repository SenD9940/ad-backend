package com.orinan.adminapi.domain.audit.service;

import com.orinan.adminapi.common.api.AdminPageRequest;
import com.orinan.adminapi.common.exception.AdminException;
import com.orinan.db.adminaudit.AdminAuditEntity;
import com.orinan.db.adminaudit.AdminAuditRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;

@Service
@RequiredArgsConstructor
public class AdminAuditService {
    private final AdminAuditRepository repository;

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(long actorId, String action, String targetType, Long targetId,
                       String reason, String beforeValue, String afterValue) {
        if (actorId <= 0 || reason != null && reason.length() > 500
                || action == null || !action.matches("[A-Z_]{1,60}")
                || targetType == null || !targetType.matches("[A-Z_]{1,40}")
                || beforeValue != null && beforeValue.length() > 1000 || afterValue != null && afterValue.length() > 1000)
            throw new AdminException(HttpStatus.BAD_REQUEST, "관리 작업 정보와 500자 이하의 메모를 확인해 주세요.");
        String normalizedReason = reason == null || reason.isBlank() ? defaultReason(action, afterValue) : reason.strip();
        repository.save(new AdminAuditEntity(actorId, action, targetType, targetId, normalizedReason, beforeValue,
                afterValue, LocalDateTime.now(ZoneId.of("Asia/Seoul"))));
    }

    private String defaultReason(String action, String afterValue) {
        return switch (action) {
            case "USER_STATUS_CHANGED" -> "SUSPENDED".equals(afterValue) ? "회원 정지"
                    : "REGISTERED".equals(afterValue) ? "회원 정지 해제" : "회원 상태 변경";
            case "USER_ROLE_CHANGED" -> "ADMIN".equals(afterValue) ? "관리자 권한 부여"
                    : "CUSTOMER".equals(afterValue) ? "관리자 권한 해제" : "회원 권한 변경";
            case "USER_SESSIONS_REVOKED" -> "전체 세션 종료";
            case "WORKSPACE_RENAME" -> "워크스페이스 이름 변경";
            case "WORKSPACE_TRANSFER_OWNER" -> "워크스페이스 소유권 이전";
            case "WORKSPACE_REMOVE_MEMBER" -> "워크스페이스 멤버 제거";
            case "WORKSPACE_REVOKE_INVITATION" -> "워크스페이스 초대 취소";
            case "CONNECTION_REQUIRE_REAUTH" -> "연결 재인증 요청";
            default -> throw new AdminException(HttpStatus.BAD_REQUEST, "해당 관리 작업에는 감사 메모가 필요합니다.");
        };
    }

    @Transactional(readOnly = true)
    public Page<AdminAuditEntity> list(Long actorId, String action, String targetType, Long targetId, int page, int size) {
        var paging = AdminPageRequest.page(page, size);
        if (actorId != null && actorId < 1 || targetId != null && targetId < 1
                || action != null && !action.matches("[A-Z_]{1,60}")
                || targetType != null && !targetType.matches("[A-Z_]{1,40}")) {
            throw new AdminException(HttpStatus.BAD_REQUEST, "감사 이력 필터 형식을 확인해 주세요.");
        }
        return repository.findAll(actorId, action, targetType, targetId, paging);
    }
}
