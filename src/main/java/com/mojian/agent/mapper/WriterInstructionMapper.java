package com.mojian.agent.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mojian.agent.entity.WriterInstruction;
import org.apache.ibatis.annotations.Mapper;

import java.util.Collection;
import java.util.List;

@Mapper
public interface WriterInstructionMapper extends BaseMapper<WriterInstruction> {

    default List<WriterInstruction> selectPending(Long projectId, Long chapterId) {
        return selectList(new LambdaQueryWrapper<WriterInstruction>()
                .eq(WriterInstruction::getProjectId, projectId)
                .eq(chapterId != null, WriterInstruction::getChapterId, chapterId)
                .eq(WriterInstruction::getStatus, "PENDING")
                .orderByAsc(WriterInstruction::getCreatedAt));
    }

    default List<WriterInstruction> selectRecent(Long projectId, int limit) {
        return selectList(new LambdaQueryWrapper<WriterInstruction>()
                .eq(WriterInstruction::getProjectId, projectId)
                .orderByDesc(WriterInstruction::getCreatedAt)
                .last("limit " + Math.max(1, Math.min(limit, 100))));
    }

    default int markConsumed(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        return update(null, new LambdaUpdateWrapper<WriterInstruction>()
                .in(WriterInstruction::getId, ids)
                .set(WriterInstruction::getStatus, "CONSUMED"));
    }
}
