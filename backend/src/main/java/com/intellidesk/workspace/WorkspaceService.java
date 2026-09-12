package com.intellidesk.workspace;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.intellidesk.common.BusinessException;
import com.intellidesk.common.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
public class WorkspaceService extends ServiceImpl<WorkspaceMapper, Workspace> {

    private final WorkspaceMapper workspaceMapper;
    private final WorkspaceMemberMapper workspaceMemberMapper;
    private final com.intellidesk.user.UserService userService;

    public WorkspaceService(WorkspaceMapper workspaceMapper, WorkspaceMemberMapper workspaceMemberMapper,
                            com.intellidesk.user.UserService userService) {
        this.workspaceMapper = workspaceMapper;
        this.workspaceMemberMapper = workspaceMemberMapper;
        this.userService = userService;
    }

    @Transactional
    public Workspace createWorkspace(String name, String description, Long ownerId) {
        if (!StringUtils.hasText(name)) {
            throw new BusinessException(ErrorCode.WORKSPACE_NAME_EMPTY);
        }
        Workspace workspace = new Workspace();
        workspace.setName(name);
        workspace.setDescription(description);
        workspace.setOwnerId(ownerId);
        save(workspace);

        WorkspaceMember member = new WorkspaceMember();
        member.setWorkspaceId(workspace.getId());
        member.setUserId(ownerId);
        member.setRole("ADMIN");
        workspaceMemberMapper.insert(member);

        return workspace;
    }

    public List<Workspace> listUserWorkspaces(Long userId) {
        return workspaceMapper.findByMemberUserId(userId);
    }

    public Workspace getWorkspace(Long workspaceId, Long userId) {
        Workspace workspace = getById(workspaceId);
        if (workspace == null) {
            throw new BusinessException(ErrorCode.WORKSPACE_NOT_FOUND);
        }
        if (!isMember(workspaceId, userId)) {
            throw new BusinessException(ErrorCode.WORKSPACE_ACCESS_DENIED);
        }
        return workspace;
    }

    @Transactional
    public Workspace updateWorkspace(Long workspaceId, String name, String description, Long userId) {
        Workspace workspace = getById(workspaceId);
        if (workspace == null) {
            throw new BusinessException(ErrorCode.WORKSPACE_NOT_FOUND);
        }
        if (!workspace.getOwnerId().equals(userId)) {
            throw new BusinessException(ErrorCode.WORKSPACE_ACCESS_DENIED);
        }
        if (StringUtils.hasText(name)) {
            workspace.setName(name);
        }
        workspace.setDescription(description);
        updateById(workspace);
        return workspace;
    }

    @Transactional
    public void deleteWorkspace(Long workspaceId, Long userId) {
        Workspace workspace = getById(workspaceId);
        if (workspace == null) {
            throw new BusinessException(ErrorCode.WORKSPACE_NOT_FOUND);
        }
        if (!workspace.getOwnerId().equals(userId)) {
            throw new BusinessException(ErrorCode.WORKSPACE_ACCESS_DENIED);
        }
        LambdaQueryWrapper<WorkspaceMember> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(WorkspaceMember::getWorkspaceId, workspaceId);
        workspaceMemberMapper.delete(wrapper);
        removeById(workspaceId);
    }

    @Transactional
    public void addMember(Long workspaceId, Long memberUserId, Long operatorId) {
        Workspace workspace = getById(workspaceId);
        if (workspace == null) {
            throw new BusinessException(ErrorCode.WORKSPACE_NOT_FOUND);
        }
        if (!workspace.getOwnerId().equals(operatorId)) {
            throw new BusinessException(ErrorCode.WORKSPACE_ACCESS_DENIED);
        }
        if (userService.getById(memberUserId) == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        LambdaQueryWrapper<WorkspaceMember> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(WorkspaceMember::getWorkspaceId, workspaceId)
                .eq(WorkspaceMember::getUserId, memberUserId);
        if (workspaceMemberMapper.selectCount(wrapper) > 0) {
            throw new BusinessException(ErrorCode.WORKSPACE_MEMBER_ALREADY_EXISTS);
        }
        WorkspaceMember member = new WorkspaceMember();
        member.setWorkspaceId(workspaceId);
        member.setUserId(memberUserId);
        member.setRole("MEMBER");
        workspaceMemberMapper.insert(member);
    }

    @Transactional
    public void removeMember(Long workspaceId, Long memberUserId, Long operatorId) {
        Workspace workspace = getById(workspaceId);
        if (workspace == null) {
            throw new BusinessException(ErrorCode.WORKSPACE_NOT_FOUND);
        }
        if (!workspace.getOwnerId().equals(operatorId)) {
            throw new BusinessException(ErrorCode.WORKSPACE_ACCESS_DENIED);
        }
        if (workspace.getOwnerId().equals(memberUserId)) {
            throw new BusinessException(ErrorCode.WORKSPACE_CANNOT_REMOVE_OWNER);
        }
        LambdaQueryWrapper<WorkspaceMember> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(WorkspaceMember::getWorkspaceId, workspaceId)
                .eq(WorkspaceMember::getUserId, memberUserId);
        if (workspaceMemberMapper.delete(wrapper) == 0) {
            throw new BusinessException(ErrorCode.WORKSPACE_MEMBER_NOT_FOUND);
        }
    }

    public List<WorkspaceMember> getMembers(Long workspaceId, Long userId) {
        if (!isMember(workspaceId, userId)) {
            throw new BusinessException(ErrorCode.WORKSPACE_ACCESS_DENIED);
        }
        LambdaQueryWrapper<WorkspaceMember> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(WorkspaceMember::getWorkspaceId, workspaceId);
        return workspaceMemberMapper.selectList(wrapper);
    }

    private boolean isMember(Long workspaceId, Long userId) {
        LambdaQueryWrapper<WorkspaceMember> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(WorkspaceMember::getWorkspaceId, workspaceId)
                .eq(WorkspaceMember::getUserId, userId);
        return workspaceMemberMapper.selectCount(wrapper) > 0;
    }
}