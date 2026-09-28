package com.orinan.adminapi.domain.aistudio.business;

import com.orinan.adminapi.annotation.Business;
import com.orinan.adminapi.domain.aistudio.client.AdminAiTemplateAnalysisClient;
import com.orinan.adminapi.domain.aistudio.controller.model.*;
import com.orinan.adminapi.domain.aistudio.service.AdminAiImageStorage;
import com.orinan.adminapi.domain.aistudio.service.AdminAiTemplateAnalysisAccess;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Business
@RequiredArgsConstructor
public class AdminAiTemplateAnalysisBusiness {
    private final AdminAiTemplateAnalysisAccess access;
    private final AdminAiImageStorage storage;
    private final AdminAiTemplateAnalysisClient client;

    /** Every authority check commits before remote I/O; no database lock spans S3 or OpenAI. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AdminAiTemplateAnalysisResponse analyze(long actor, AdminAiTemplateAnalysisRequest request) {
        access.authorizeImage(actor, request.imageKey());
        client.requireConfigured();
        var image = storage.read(request.imageKey());
        access.authorizeImage(actor, request.imageKey());
        var result = client.analyze(request.kind(), image);
        access.complete(actor, request.imageKey());
        return result;
    }
}
