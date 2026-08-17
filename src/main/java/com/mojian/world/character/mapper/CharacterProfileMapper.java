package com.mojian.world.character.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mojian.world.character.entity.CharacterProfile;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface CharacterProfileMapper extends BaseMapper<CharacterProfile> {

    default List<CharacterProfile> selectByProjectId(Long projectId) {
        return selectList(new LambdaQueryWrapper<CharacterProfile>()
                .eq(CharacterProfile::getProjectId, projectId));
    }

    default int deleteByProjectId(Long projectId) {
        return delete(new LambdaQueryWrapper<CharacterProfile>()
                .eq(CharacterProfile::getProjectId, projectId));
    }
}
