package com.mojian.world.character.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mojian.world.character.entity.CharacterNode;
import org.apache.ibatis.annotations.Mapper;

import java.util.Collection;
import java.util.List;

@Mapper
public interface CharacterNodeMapper extends BaseMapper<CharacterNode> {

    default List<CharacterNode> selectByCharacterId(Long characterId) {
        return selectList(new LambdaQueryWrapper<CharacterNode>()
                .eq(CharacterNode::getCharacterId, characterId)
                .orderByAsc(CharacterNode::getChapterFrom));
    }

    default int deleteByCharacterId(Long characterId) {
        return delete(new LambdaQueryWrapper<CharacterNode>()
                .eq(CharacterNode::getCharacterId, characterId));
    }

    default int deleteByCharacterIds(Collection<Long> characterIds) {
        if (characterIds == null || characterIds.isEmpty()) {
            return 0;
        }
        return delete(new LambdaQueryWrapper<CharacterNode>()
                .in(CharacterNode::getCharacterId, characterIds));
    }

    default List<CharacterNode> selectByCharacterIds(Collection<Long> characterIds) {
        if (characterIds == null || characterIds.isEmpty()) {
            return List.of();
        }
        return selectList(new LambdaQueryWrapper<CharacterNode>()
                .in(CharacterNode::getCharacterId, characterIds)
                .orderByAsc(CharacterNode::getChapterFrom));
    }
}
