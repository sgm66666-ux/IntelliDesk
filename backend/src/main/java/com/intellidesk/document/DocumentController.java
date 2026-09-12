package com.intellidesk.document;

import com.intellidesk.common.Result;
import com.intellidesk.common.dto.PageResponse;
import com.intellidesk.document.dto.DocumentChunkResponse;
import com.intellidesk.document.dto.DocumentResponse;
import com.intellidesk.document.dto.DocumentRetryResponse;
import com.intellidesk.document.dto.DocumentUploadResponse;
import com.intellidesk.user.User;
import com.intellidesk.user.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/knowledge-bases/{knowledgeBaseId}/documents")
public class DocumentController {

    private final DocumentService documentService;
    private final UserService userService;

    public DocumentController(DocumentService documentService, UserService userService) {
        this.documentService = documentService;
        this.userService = userService;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('document:manage')")
    public ResponseEntity<Result<DocumentUploadResponse>> upload(
            @PathVariable Long workspaceId,
            @PathVariable Long knowledgeBaseId,
            @RequestParam("file") MultipartFile file,
            Authentication authentication) {
        User user = userService.findByUsername(authentication.getName());
        DocumentUploadResponse response = documentService.uploadDocument(
                workspaceId, knowledgeBaseId, user.getId(), file);
        return ResponseEntity.accepted().body(Result.success(response));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('document:view')")
    public Result<PageResponse<DocumentResponse>> list(
            @PathVariable Long workspaceId,
            @PathVariable Long knowledgeBaseId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status,
            Authentication authentication) {
        User user = userService.findByUsername(authentication.getName());
        return Result.success(documentService.listDocuments(
                workspaceId, knowledgeBaseId, user.getId(), page, size, status));
    }

    @GetMapping("/{documentId}")
    @PreAuthorize("hasAuthority('document:view')")
    public Result<DocumentResponse> detail(
            @PathVariable Long workspaceId,
            @PathVariable Long knowledgeBaseId,
            @PathVariable Long documentId,
            Authentication authentication) {
        User user = userService.findByUsername(authentication.getName());
        return Result.success(documentService.getDocument(
                workspaceId, knowledgeBaseId, documentId, user.getId()));
    }

    @DeleteMapping("/{documentId}")
    @PreAuthorize("hasAuthority('document:manage')")
    public Result<Void> delete(
            @PathVariable Long workspaceId,
            @PathVariable Long knowledgeBaseId,
            @PathVariable Long documentId,
            Authentication authentication) {
        User user = userService.findByUsername(authentication.getName());
        documentService.deleteDocument(workspaceId, knowledgeBaseId, documentId, user.getId());
        return Result.success(null);
    }

    @PostMapping("/{documentId}/retry")
    @PreAuthorize("hasAuthority('document:manage')")
    public Result<DocumentRetryResponse> retry(
            @PathVariable Long workspaceId,
            @PathVariable Long knowledgeBaseId,
            @PathVariable Long documentId,
            Authentication authentication) {
        User user = userService.findByUsername(authentication.getName());
        return Result.success(documentService.retryDocument(
                workspaceId, knowledgeBaseId, documentId, user.getId()));
    }

    @GetMapping("/{documentId}/chunks")
    @PreAuthorize("hasAuthority('document:view')")
    public Result<PageResponse<DocumentChunkResponse>> listChunks(
            @PathVariable Long workspaceId,
            @PathVariable Long knowledgeBaseId,
            @PathVariable Long documentId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {
        User user = userService.findByUsername(authentication.getName());
        return Result.success(documentService.listChunks(
                workspaceId, knowledgeBaseId, documentId, user.getId(), page, size));
    }
}
