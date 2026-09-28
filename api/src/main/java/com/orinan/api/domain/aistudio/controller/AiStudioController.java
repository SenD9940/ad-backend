package com.orinan.api.domain.aistudio.controller;

import com.orinan.api.annotation.UserSession;
import com.orinan.api.common.api.Api;
import com.orinan.api.domain.aistudio.controller.model.AiStudioGenerationRequest;
import com.orinan.api.domain.aistudio.controller.model.AiStudioResponse.*;
import com.orinan.api.domain.aistudio.service.AiStudioService;
import com.orinan.api.domain.user.controller.model.UserResponse;
import com.orinan.db.aistudio.enums.AiStudioKind;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/workspaces/{workspaceId}/ai-studio")
public class AiStudioController {
    private final AiStudioService service;
    @ModelAttribute public void noStore(HttpServletResponse response) { response.setHeader("Cache-Control", "no-store"); }
    @GetMapping("/capabilities")
    public Api<Capabilities> capabilities(@PathVariable long workspaceId, @UserSession UserResponse user) {
        return Api.OK(service.capabilities(workspaceId, user.getId()));
    }
    @GetMapping("/categories")
    public Api<java.util.List<Category>> categories(@PathVariable long workspaceId, @UserSession UserResponse user) {
        return Api.OK(service.categories(workspaceId, user.getId()));
    }
    @GetMapping("/templates")
    public Api<Page<Template>> templates(@PathVariable long workspaceId, @UserSession UserResponse user,
            @RequestParam(required = false) AiStudioKind kind, @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return Api.OK(service.templates(workspaceId, user.getId(), kind, categoryId, q, page, size));
    }
    @GetMapping("/templates/{templateId}")
    public Api<Template> template(@PathVariable long workspaceId, @PathVariable long templateId, @UserSession UserResponse user) {
        return Api.OK(service.template(workspaceId, user.getId(), templateId));
    }
    @PostMapping("/generations")
    public Api<Output> generate(@PathVariable long workspaceId, @UserSession UserResponse user,
                               @RequestBody @Valid AiStudioGenerationRequest request) {
        return Api.OK(service.generate(workspaceId, user.getId(), request));
    }
    @PostMapping(value = "/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Api<AiStudioService.UploadedImage> upload(@PathVariable long workspaceId, @UserSession UserResponse user,
                                                    @RequestPart("file") MultipartFile file) {
        return Api.OK(service.upload(workspaceId, user.getId(), file));
    }
    @GetMapping("/outputs")
    public Api<Page<Output>> outputs(@PathVariable long workspaceId, @UserSession UserResponse user,
            @RequestParam(required = false) AiStudioKind kind, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Api.OK(service.outputs(workspaceId, user.getId(), kind, page, size));
    }
    @GetMapping("/outputs/{outputId}")
    public Api<Output> output(@PathVariable long workspaceId, @PathVariable long outputId, @UserSession UserResponse user) {
        return Api.OK(service.output(workspaceId, user.getId(), outputId));
    }
    @GetMapping("/outputs/{outputId}/image")
    public ResponseEntity<byte[]> image(@PathVariable long workspaceId, @PathVariable long outputId, @UserSession UserResponse user) {
        var output = service.exportOutput(workspaceId, user.getId(), outputId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType(output.imageContentType()))
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("ai-studio-" + outputId + (output.imageContentType().equals("image/png") ? ".png" : ".jpg")).build().toString())
                .body(output.imageBytes());
    }
}
