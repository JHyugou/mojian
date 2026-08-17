package com.mojian.version.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mojian.version.entity.ChapterVersion;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ChapterVersionMapper extends BaseMapper<ChapterVersion> {

    default int deleteByChapterId(Long chapterId) {
        return delete(new LambdaQueryWrapper<ChapterVersion>()
                .eq(ChapterVersion::getChapterId, chapterId));
    }
}
