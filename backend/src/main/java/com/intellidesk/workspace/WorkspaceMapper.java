package com.intellidesk.workspace;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface WorkspaceMapper extends BaseMapper<Workspace> {

    @Select("SELECT w.* FROM workspace w " +
            "INNER JOIN workspace_member wm ON w.id = wm.workspace_id " +
            "WHERE wm.user_id = #{userId}")
    List<Workspace> findByMemberUserId(Long userId);
}