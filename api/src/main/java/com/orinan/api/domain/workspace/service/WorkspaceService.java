package com.orinan.api.domain.workspace.service;

import com.orinan.api.common.code.ApiCode;
import com.orinan.api.common.exception.ApiException;
import com.orinan.db.workspace.WorkspaceEntity;
import com.orinan.db.workspace.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class WorkspaceService {

    private final WorkspaceRepository workspaceRepository;

    public List<WorkspaceEntity> findAllByUserId(Long userId){
        return workspaceRepository.findAllByUserIdOrderByIdDesc(userId);
    }

    public List<WorkspaceEntity> findAllJoinedByUserId(Long userId){
        return workspaceRepository.findAllJoinedByUserId(userId);
    }

    public WorkspaceEntity findByIdWithThrow(Long id){
        return workspaceRepository.findById(id)
                .orElseThrow(() -> new ApiException(ApiCode.BAD_REQUEST, "존재하지 않는 워크스페이스입니다"));
    }

    public WorkspaceEntity save(WorkspaceEntity entity){
        return workspaceRepository.save(entity);
    }

}
