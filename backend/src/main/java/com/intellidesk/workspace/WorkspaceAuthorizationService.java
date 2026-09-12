package com.intellidesk.workspace;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.intellidesk.common.BusinessException;
import com.intellidesk.common.ErrorCode;
import org.springframework.stereotype.Service;

@Service
public class WorkspaceAuthorizationService {

    private final WorkspaceMapper workspaceMapper;
    private final WorkspaceMemberMapper workspaceMemberMapper;

    public WorkspaceAuthorizationService(WorkspaceMapper workspaceMapper,
                                         WorkspaceMemberMapper workspaceMemberMapper) {
        this.workspaceMapper = workspaceMapper;
        this.workspaceMemberMapper = workspaceMemberMapper;
    }

    public void requireOwner(Long workspaceId, Long userId) {
        Workspace workspace = workspaceMapper.selectById(workspaceId);
        if (workspace == null) {
            throw new BusinessException(ErrorCode.WORKSPACE_NOT_FOUND);
        }
        if (!workspace.getOwnerId().equals(userId)) {
            throw new BusinessException(ErrorCode.WORKSPACE_ACCESS_DENIED);
        }
    }

    public void requireMember(Long workspaceId, Long userId) {
        if (!isMember(workspaceId, userId)) {
            throw new BusinessException(ErrorCode.WORKSPACE_ACCESS_DENIED);
        }
    }

    public boolean isOwner(Long workspaceId, Long userId) {
        Workspace workspace = workspaceMapper.selectById(workspaceId);
        return workspace != null && workspace.getOwnerId().equals(userId);
    }

    public boolean isMember(Long workspaceId, Long userId) {
        if (workspaceId == null || userId == null) {
            return false;
        }
        LambdaQueryWrapper<WorkspaceMember> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(WorkspaceMember::getWorkspaceId, workspaceId)
                .eq(WorkspaceMember::getUserId, userId);
        return workspaceMemberMapper.selectCount(wrapper) > 0;
    }
}
