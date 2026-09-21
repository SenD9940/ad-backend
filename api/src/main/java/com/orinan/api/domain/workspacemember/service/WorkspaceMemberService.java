package com.orinan.api.domain.workspacemember.service;

import com.orinan.api.common.code.ApiCode;
import com.orinan.api.common.code.DatabaseErrorCode;
import com.orinan.api.common.exception.ApiException;
import com.orinan.db.workspacemember.WorkspaceMemberEntity;
import com.orinan.db.workspacemember.WorkspaceMemberId;
import com.orinan.db.workspacemember.WorkspaceMemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class WorkspaceMemberService {

    private final WorkspaceMemberRepository workspaceMemberRepository;

    public WorkspaceMemberEntity save(WorkspaceMemberEntity entity){
        validateNotMember(entity.getId());
        return workspaceMemberRepository.saveAndFlush(entity);
    }

    public void validateNotMember(WorkspaceMemberId id){
        if (workspaceMemberRepository.existsById(id)) {
            throw new ApiException(DatabaseErrorCode.DUPLICATE_KEY, "이미 등록된 워크스페이스 멤버입니다");
        }
    }

    public void delete(WorkspaceMemberId id){
        var entity = workspaceMemberRepository.findById(id)
                .orElseThrow(() -> new ApiException(ApiCode.BAD_REQUEST, "등록되지 않은 워크스페이스 멤버입니다"));
        workspaceMemberRepository.delete(entity);
    }
}
