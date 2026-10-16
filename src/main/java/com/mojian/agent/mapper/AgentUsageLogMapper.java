package com.mojian.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mojian.agent.entity.AgentUsageLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AgentUsageLogMapper extends BaseMapper<AgentUsageLog> {

    default List<AgentUsageLog> selectRecent(int limit) {
        return selectList(Wrappers.<AgentUsageLog>lambdaQuery()
                .orderByDesc(AgentUsageLog::getId)
                .last("LIMIT " + limit));
    }

    /** 某个时间点之后的累计花费（美元）—— 界面上"本月已用"就靠它 */
    @Select("SELECT COALESCE(SUM(estimated_cost), 0) FROM agent_usage_log WHERE created_at >= #{since}")
    BigDecimal sumCostSince(@Param("since") LocalDateTime since);

    @Select("SELECT COUNT(*) FROM agent_usage_log WHERE created_at >= #{since}")
    long countSince(@Param("since") LocalDateTime since);
}
