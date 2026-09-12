package com.intellidesk.api_key;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface ApiKeyMapper extends BaseMapper<ApiKey> {

    @Update("UPDATE api_key SET last_used_at = #{lastUsedAt} WHERE id = #{id}")
    int updateLastUsedAt(@Param("id") Long id, @Param("lastUsedAt") LocalDateTime lastUsedAt);
}