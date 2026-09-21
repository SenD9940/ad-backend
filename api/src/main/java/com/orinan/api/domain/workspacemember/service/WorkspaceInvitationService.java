package com.orinan.api.domain.workspacemember.service;

import com.orinan.api.common.code.ApiCode;
import com.orinan.api.common.exception.ApiException;
import com.orinan.api.common.time.SeoulDateTimes;
import com.orinan.api.domain.user.exception.UserErrorCode;
import com.orinan.db.user.UserEntity;
import com.orinan.db.workspace.WorkspaceEntity;
import com.orinan.db.workspaceinvitation.WorkspaceInvitationEntity;
import com.orinan.db.workspaceinvitation.WorkspaceInvitationRepository;
import com.orinan.db.workspacemember.WorkspaceMemberId;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WorkspaceInvitationService {

    private final WorkspaceInvitationRepository workspaceInvitationRepository;
    private final JavaMailSender mailSender;

    @Value("${app.workspace-invitation.accept-url:}")
    private String acceptUrl;

    @Value("${spring.mail.username:}")
    private String from;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void send(WorkspaceEntity workspace, UserEntity recipient){
        if (acceptUrl.isBlank()) {
            throw new ApiException(ApiCode.SERVER_ERROR,
                    "초대 수락 페이지 주소가 필요합니다 (WORKSPACE_INVITATION_ACCEPT_URL)");
        }
        if (from.isBlank()) {
            throw new ApiException(ApiCode.SERVER_ERROR,
                    "SMTP 메일 계정이 필요합니다 (spring.mail.username)");
        }

        String token = UUID.randomUUID().toString();
        var id = new WorkspaceMemberId(workspace.getId(), recipient.getId());
        var invitation = workspaceInvitationRepository.findById(id)
                .orElseGet(() -> WorkspaceInvitationEntity.builder().id(id).build());
        invitation.setTokenHash(hash(token));
        invitation.setExpiresAt(SeoulDateTimes.now().plusHours(24));
        workspaceInvitationRepository.saveAndFlush(invitation);

        String link = UriComponentsBuilder.fromUriString(acceptUrl)
                .queryParam("token", token)
                .build().encode().toUriString();
        var message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(recipient.getEmail());
        message.setSubject("워크스페이스 초대");
        message.setText("""
                '%s' 워크스페이스에 초대되었습니다.

                초대받은 계정으로 로그인한 뒤 아래 링크에서 참여를 수락해 주세요.
                참여하기: %s

                이 링크는 24시간 동안 유효하며, 한 번만 사용할 수 있습니다.
                초대 메일을 다시 받았다면 가장 최근 링크를 사용해 주세요.
                """.formatted(workspace.getName(), link));
        // 전송 실패 예외를 호출자에게 전달하여 초대 저장도 함께 롤백합니다.
        mailSender.send(message);
    }

    public WorkspaceInvitationEntity findValidWithThrow(String token, Long authenticatedUserId){
        var invitation = workspaceInvitationRepository.findByTokenHash(hash(token))
                .orElseThrow(() -> new ApiException(ApiCode.BAD_REQUEST, "유효하지 않은 초대입니다"));
        // 요청 본문의 사용자 정보가 아닌, 인증된 로그인 계정으로 초대 대상을 확인합니다.
        if (!invitation.getId().getUserId().equals(authenticatedUserId)) {
            throw new ApiException(UserErrorCode.USER_PERMISSION_DENY,
                    "초대받은 사용자만 수락할 수 있습니다. 초대 메일을 받은 계정으로 로그인해 주세요");
        }
        if (!invitation.getExpiresAt().isAfter(SeoulDateTimes.now())) {
            throw new ApiException(ApiCode.BAD_REQUEST, "만료된 초대입니다");
        }
        return invitation;
    }

    public void delete(WorkspaceInvitationEntity invitation){
        workspaceInvitationRepository.delete(invitation);
    }

    private String hash(String token){
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
