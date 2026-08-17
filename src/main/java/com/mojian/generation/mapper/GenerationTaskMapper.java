package com.mojian.generation.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mojian.generation.entity.GenerationTask;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface GenerationTaskMapper extends BaseMapper<GenerationTask> {

    default int deleteByChapterId(Long chapterId) {
        return delete(new LambdaQueryWrapper<GenerationTask>()
                .eq(GenerationTask::getChapterId, chapterId));
    }
}
