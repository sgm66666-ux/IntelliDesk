package com.intellidesk.knowledge;

import com.intellidesk.common.Result;
import com.intellidesk.common.dto.PageResponse;
import com.intellidesk.knowledge.dto.KnowledgeBaseCreateRequest;
import com.intellidesk.knowledge.dto.KnowledgeBaseUpdateRequest;
import com.intellidesk.user.User;
import com.intellidesk.user.UserService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/knowledge-bases")
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;
    private final UserService userService;

    public KnowledgeBaseController(KnowledgeBaseService knowledgeBaseService, UserService userService) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.userService = userService;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('knowledge:manage')")
    public Result<KnowledgeBase> create(@PathVariable Long workspaceId,
                                         @Valid @RequestBody KnowledgeBaseCreateRequest request,
                                         Authentication authentication) {
        User user = userService.findByUsername(authentication.getName());
        KnowledgeBase kb = knowledgeBaseService.createKnowledgeBase(
                workspaceId, user.getId(),
                request.getName(), request.getDescription(),
                request.getChunkStrategy(), request.getChunkSize(), request.getChunkOverlap());
        return Result.success(kb);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('knowledge:view')")
    public Result<PageResponse<KnowledgeBase>> list(@PathVariable Long workspaceId,
                                                     @RequestParam(defaultValue = "1") int page,
                                                     @RequestParam(defaultValue = "20") int size,
                                                     @RequestParam(required = false) String keyword,
                                                     Authentication authentication) {
        User user = userService.findByUsername(authentication.getName());
        return Result.success(knowledgeBaseService.listKnowledgeBases(workspaceId, user.getId(), page, size, keyword));
    }

    @GetMapping("/{knowledgeBaseId}")
    @PreAuthorize("hasAuthority('knowledge:view')")
    public Result<KnowledgeBase> detail(@PathVariable Long workspaceId,
                                         @PathVariable Long knowledgeBaseId,
                                         Authentication authentication) {
        User user = userService.findByUsername(authentication.getName());
        return Result.success(knowledgeBaseService.getKnowledgeBase(workspaceId, knowledgeBaseId, user.getId()));
    }

    @PutMapping("/{knowledgeBaseId}")
    @PreAuthorize("hasAuthority('knowledge:manage')")
    public Result<KnowledgeBase> update(@PathVariable Long workspaceId,
                                         @PathVariable Long knowledgeBaseId,
                                         @Valid @RequestBody KnowledgeBaseUpdateRequest request,
                                         Authentication authentication) {
        User user = userService.findByUsername(authentication.getName());
        KnowledgeBase kb = knowledgeBaseService.updateKnowledgeBase(
                workspaceId, knowledgeBaseId, user.getId(),
                request.getName(), request.getDescription(),
                request.getChunkStrategy(), request.getChunkSize(), request.getChunkOverlap());
        return Result.success(kb);
    }

    @DeleteMapping("/{knowledgeBaseId}")
    @PreAuthorize("hasAuthority('knowledge:manage')")
    public Result<Void> delete(@PathVariable Long workspaceId,
                                @PathVariable Long knowledgeBaseId,
                                Authentication authentication) {
        User user = userService.findByUsername(authentication.getName());
        knowledgeBaseService.deleteKnowledgeBase(workspaceId, knowledgeBaseId, user.getId());
        return Result.success();
    }
}
