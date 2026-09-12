package com.intellidesk.retrieval.indexing;

import com.intellidesk.common.Result;
import com.intellidesk.retrieval.indexing.dto.RetrievalReindexResponse;
import com.intellidesk.retrieval.task.DocumentRetrievalTask;
import com.intellidesk.user.User;
import com.intellidesk.user.UserService;
import com.intellidesk.workspace.WorkspaceAuthorizationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/knowledge-bases/{knowledgeBaseId}/documents/{documentId}/retrieval")
public class DocumentRetrievalController {

    private final RetrievalTaskService retrievalTaskService;
    private final WorkspaceAuthorizationService workspaceAuthorizationService;
    private final UserService userService;

    public DocumentRetrievalController(RetrievalTaskService retrievalTaskService,
                                        WorkspaceAuthorizationService workspaceAuthorizationService,
                                        UserService userService) {
        this.retrievalTaskService = retrievalTaskService;
        this.workspaceAuthorizationService = workspaceAuthorizationService;
        this.userService = userService;
    }

    @PostMapping("/reindex")
    @PreAuthorize("hasAuthority('retrieval:manage')")
    public ResponseEntity<Result<RetrievalReindexResponse>> reindex(
            @PathVariable Long workspaceId,
            @PathVariable Long knowledgeBaseId,
            @PathVariable Long documentId,
            Authentication authentication) {
        User user = userService.findByUsername(authentication.getName());
        workspaceAuthorizationService.requireOwner(workspaceId, user.getId());

        DocumentRetrievalTask task = retrievalTaskService.reindex(documentId, user.getId());

        RetrievalReindexResponse response = RetrievalReindexResponse.builder()
                .documentId(task.getDocumentId())
                .taskId(task.getId())
                .generation(task.getGeneration())
                .status(task.getStatus())
                .createdAt(task.getCreatedAt())
                .build();

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Result.success(response));
    }
}