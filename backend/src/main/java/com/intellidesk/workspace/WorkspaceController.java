package com.intellidesk.workspace;

import com.intellidesk.common.Result;
import com.intellidesk.user.User;
import com.intellidesk.user.UserService;
import com.intellidesk.workspace.dto.AddMemberRequest;
import com.intellidesk.workspace.dto.WorkspaceCreateRequest;
import com.intellidesk.workspace.dto.WorkspaceUpdateRequest;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/workspaces")
public class WorkspaceController {

    private final WorkspaceService workspaceService;
    private final UserService userService;

    public WorkspaceController(WorkspaceService workspaceService, UserService userService) {
        this.workspaceService = workspaceService;
        this.userService = userService;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('workspace:create')")
    public Result<Workspace> create(@Valid @RequestBody WorkspaceCreateRequest request,
                                     Authentication authentication) {
        User user = userService.findByUsername(authentication.getName());
        Workspace workspace = workspaceService.createWorkspace(
                request.getName(), request.getDescription(), user.getId());
        return Result.success(workspace);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('workspace:view')")
    public Result<List<Workspace>> list(Authentication authentication) {
        User user = userService.findByUsername(authentication.getName());
        return Result.success(workspaceService.listUserWorkspaces(user.getId()));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('workspace:view')")
    public Result<Workspace> detail(@PathVariable Long id, Authentication authentication) {
        User user = userService.findByUsername(authentication.getName());
        return Result.success(workspaceService.getWorkspace(id, user.getId()));
    }

    @PutMapping("/{id}")
    public Result<Workspace> update(@PathVariable Long id,
                                     @Valid @RequestBody WorkspaceUpdateRequest request,
                                     Authentication authentication) {
        User user = userService.findByUsername(authentication.getName());
        return Result.success(workspaceService.updateWorkspace(
                id, request.getName(), request.getDescription(), user.getId()));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id, Authentication authentication) {
        User user = userService.findByUsername(authentication.getName());
        workspaceService.deleteWorkspace(id, user.getId());
        return Result.success();
    }

    @GetMapping("/{id}/members")
    @PreAuthorize("hasAuthority('workspace:view')")
    public Result<List<WorkspaceMember>> getMembers(@PathVariable Long id,
                                                               Authentication authentication) {
        User user = userService.findByUsername(authentication.getName());
        return Result.success(workspaceService.getMembers(id, user.getId()));
    }

    @PostMapping("/{id}/members")
    public Result<Void> addMember(@PathVariable Long id,
                                   @Valid @RequestBody AddMemberRequest request,
                                   Authentication authentication) {
        User user = userService.findByUsername(authentication.getName());
        workspaceService.addMember(id, request.getUserId(), user.getId());
        return Result.success();
    }

    @DeleteMapping("/{id}/members/{userId}")
    public Result<Void> removeMember(@PathVariable Long id,
                                      @PathVariable Long userId,
                                      Authentication authentication) {
        User user = userService.findByUsername(authentication.getName());
        workspaceService.removeMember(id, userId, user.getId());
        return Result.success();
    }
}