package com.mojian.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mojian.agent.entity.AgentProfile;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AgentProfileMapper extends BaseMapper<AgentProfile> {

    default AgentProfile selectByRole(String role) {
        return selectOne(Wrappers.<AgentProfile>lambdaQuery()
                .eq(AgentProfile::getRole, role)
                .last("LIMIT 1"));
    }
}
