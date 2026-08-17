package com.mojian.world.character.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.mojian.common.api.ErrorCode;
import com.mojian.common.exception.BusinessException;
import com.mojian.project.service.ProjectService;
import com.mojian.world.character.dto.CharacterSaveRequest;
import com.mojian.world.character.entity.CharacterProfile;
import com.mojian.world.character.mapper.CharacterProfileMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CharacterService extends ServiceImpl<CharacterProfileMapper, CharacterProfile> {

    private final ProjectService projectService;

    public List<CharacterProfile> list(Long projectId) {
        projectService.requireById(projectId);
        return lambdaQuery()
                .eq(CharacterProfile::getProjectId, projectId)
                .orderByDesc(CharacterProfile::getEnabled)
                .orderByAsc(CharacterProfile::getCreatedAt)
                .list();
    }

    public CharacterProfile create(Long projectId, CharacterSaveRequest request) {
        projectService.requireById(projectId);
        CharacterProfile profile = new CharacterProfile();
        BeanUtils.copyProperties(request, profile);
        profile.setProjectId(projectId);
        profile.setEnabled(request.getEnabled() == null || request.getEnabled());
        save(profile);
        return profile;
    }

    public CharacterProfile update(Long characterId, CharacterSaveRequest request) {
        CharacterProfile profile = requireById(characterId);
        BeanUtils.copyProperties(request, profile);
        if (request.getEnabled() != null) {
            profile.setEnabled(request.getEnabled());
        }
        updateById(profile);
        return profile;
    }

    public void delete(Long characterId) {
        requireById(characterId);
        removeById(characterId);
    }

    public CharacterProfile requireById(Long characterId) {
        CharacterProfile profile = getById(characterId);
        if (profile == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "人物档案不存在");
        }
        return profile;
    }
}
