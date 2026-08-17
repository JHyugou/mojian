package com.mojian.chapter.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mojian.chapter.entity.ChapterMemory;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ChapterMemoryMapper extends BaseMapper<ChapterMemory> {

    default ChapterMemory selectByChapterId(Long chapterId) {
        return selectOne(new LambdaQueryWrapper<ChapterMemory>()
                .eq(ChapterMemory::getChapterId, chapterId));
    }

    default int deleteByChapterId(Long chapterId) {
        return delete(new LambdaQueryWrapper<ChapterMemory>()
                .eq(ChapterMemory::getChapterId, chapterId));
    }
}
