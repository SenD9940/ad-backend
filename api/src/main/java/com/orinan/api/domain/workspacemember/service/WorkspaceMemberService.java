package com.orinan.api.domain.workspacemember.service;

import com.orinan.api.common.code.DatabaseErrorCode;
import com.orinan.api.common.exception.ApiException;
import com.orinan.db.workspacemember.WorkspaceMemberEntity;
import com.orinan.db.workspacemember.WorkspaceMemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class WorkspaceMemberService {

    private final WorkspaceMemberRepository workspaceMemberRepository;

    public WorkspaceMemberEntity save(WorkspaceMemberEntity entity){
        if (workspaceMemberRepository.existsById(entity.getId())) {
            throw new ApiException(DatabaseErrorCode.DUPLICATE_KEY, "이미 등록된 워크스페이스 멤버입니다");
        }
        return workspaceMemberRepository.saveAndFlush(entity);
    }
}
