package com.orinan.api.domain.workspace.service;

import com.orinan.api.common.code.ApiCode;
import com.orinan.api.common.exception.ApiException;
import com.orinan.db.workspace.WorkspaceEntity;
import com.orinan.db.workspace.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class WorkspaceService {

    private final WorkspaceRepository workspaceRepository;

    public WorkspaceEntity findByIdWithThrow(Long id){
        return workspaceRepository.findById(id)
                .orElseThrow(() -> new ApiException(ApiCode.BAD_REQUEST, "존재하지 않는 워크스페이스입니다"));
    }

    public WorkspaceEntity save(WorkspaceEntity entity){
        return workspaceRepository.save(entity);
    }

}
