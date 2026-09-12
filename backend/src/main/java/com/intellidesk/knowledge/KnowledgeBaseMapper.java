package com.intellidesk.knowledge;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface KnowledgeBaseMapper extends BaseMapper<KnowledgeBase> {

    @Select("SELECT COUNT(*) FROM knowledge_base WHERE workspace_id = #{workspaceId}")
    long countByWorkspaceId(Long workspaceId);
}
