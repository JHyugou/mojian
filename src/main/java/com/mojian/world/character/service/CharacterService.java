package com.mojian.world.character.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.mojian.common.api.ErrorCode;
import com.mojian.common.exception.BusinessException;
import com.mojian.generation.llm.LlmClient;
import com.mojian.generation.llm.LlmRequest;
import com.mojian.project.service.ProjectService;
import com.mojian.world.character.dto.CharacterSaveRequest;
import com.mojian.world.character.entity.CharacterProfile;
import com.mojian.world.character.mapper.CharacterProfileMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CharacterService extends ServiceImpl<CharacterProfileMapper, CharacterProfile> {

    private final ProjectService projectService;
    private final LlmClient llmClient;

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

    /**
     * 对话示例 AI 草稿（FR-018）：同步轻量调用，草稿由用户确认后才随档案保存，
     * 不创建生成任务、不进入任务状态机。
     */
    public String draftDialogueExample(Long characterId, String hint) {
        CharacterProfile profile = requireById(characterId);

        StringBuilder sb = new StringBuilder();
        section(sb, "人物姓名", profile.getName());
        section(sb, "身份", profile.getRoleIdentity());
        section(sb, "性格", profile.getPersonality());
        section(sb, "语言风格", profile.getSpeechStyle());
        section(sb, "当前状态", profile.getCurrentStatus());
        section(sb, "补充要求", hint);

        String system = "你是中文小说的人物台词设计助手。根据人物资料写 4-6 行对话示例，"
                + "体现其语言风格与性格，只输出对话本身。";
        return llmClient.chat(LlmRequest.of(system, sb.toString(), "EXAMPLE_DIALOGUE"));
    }

    private void section(StringBuilder sb, String marker, String value) {
        if (StringUtils.hasText(value)) {
            sb.append("【").append(marker).append("】").append(value.trim()).append('\n');
        }
    }
}
