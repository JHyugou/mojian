package com.mojian.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mojian.agent.draft.DraftStatus;
import com.mojian.agent.draft.DraftType;
import com.mojian.agent.entity.AgentDraft;
import com.mojian.agent.mapper.AgentDraftMapper;
import com.mojian.common.api.ErrorCode;
import com.mojian.common.exception.BusinessException;
import com.mojian.outline.dto.OutlineNodeSaveRequest;
import com.mojian.outline.entity.PlotOutline;
import com.mojian.outline.service.OutlineService;
import com.mojian.world.character.dto.CharacterSaveRequest;
import com.mojian.world.character.entity.CharacterProfile;
import com.mojian.world.character.service.CharacterService;
import com.mojian.world.setting.dto.SettingSaveRequest;
import com.mojian.world.setting.entity.SettingEntry;
import com.mojian.world.setting.service.SettingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 草稿的起草与确认。
 *
 * <p><b>起草</b>只写 {@code agent_draft}，绝不碰业务表；
 * <b>确认</b>才把内容写进 {@code character_profile / setting_entry / plot_outline}。
 * 这条分界线是"人工确认"能真正生效的前提 —— 否则 Agent 一提交就已经改了你的书。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DraftService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AgentDraftMapper draftMapper;
    private final CharacterService characterService;
    private final SettingService settingService;
    private final OutlineService outlineService;

    // ==================== 起草 ====================

    @Transactional
    public AgentDraft draft(Long conversationId, Long projectId, String draftType,
                            String title, Map<String, Object> payload, String reason) {
        return draft(conversationId, projectId, draftType, title, payload, reason, null);
    }

    /**
     * 起草一份变更草案。
     *
     * @param targetId 要修改的对象 id；<b>为空表示新增</b>。
     *                 用"有没有 targetId"区分新增与修改，而不是再加类型字段：
     *                 两种情况要展示的东西、用户做的判断完全一样，
     *                 区别只在确认时是 INSERT 还是 UPDATE。
     */
    @Transactional
    public AgentDraft draft(Long conversationId, Long projectId, String draftType,
                            String title, Map<String, Object> payload, String reason, Long targetId) {
        AgentDraft draft = new AgentDraft();
        draft.setConversationId(conversationId);
        draft.setProjectId(projectId);
        draft.setDraftType(draftType);
        draft.setStatus(DraftStatus.PENDING);
        draft.setTitle(title);
        draft.setPayloadJson(writeJson(payload));
        draft.setAgentReason(reason);
        draft.setTargetId(targetId);
        draftMapper.insert(draft);
        log.info("起草{}{}「{}」（会话 {}）", targetId == null ? "" : "修改", draftType, title, conversationId);
        return draft;
    }

    public List<AgentDraft> listByConversation(Long conversationId) {
        return draftMapper.selectByConversation(conversationId);
    }

    /** 本书全部草稿（最近的在前面） */
    public List<AgentDraft> listByProject(Long projectId) {
        return draftMapper.selectByProject(projectId);
    }

    /** 界面上的"待确认"列表 */
    public List<AgentDraft> listPending(Long conversationId) {
        return draftMapper.selectPendingByConversation(conversationId);
    }

    /**
     * 本书全部待确认草稿（不看是哪段会话起草的）。
     *
     * <p><b>为什么必须按项目查而不是按会话</b>：写作 Agent 也会起草人物卡
     * （写到一半发现缺个角色），而那些草稿挂在<b>写作会话</b>上。
     * 如果界面只查策划会话，它们就永远不会出现在"待你确认"里 ——
     * 用户看不到、也确认不了，那条草稿等于凭空消失。
     */
    public List<AgentDraft> listPendingByProject(Long projectId) {
        return draftMapper.selectByProject(projectId).stream()
                .filter(draft -> DraftStatus.PENDING.equals(draft.getStatus()))
                .toList();
    }

    public AgentDraft require(Long draftId) {
        AgentDraft draft = draftMapper.selectById(draftId);
        if (draft == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "草稿不存在: " + draftId);
        }
        return draft;
    }

    // ==================== 确认 ====================

    /**
     * 用户确认：把草稿写进业务表，并记下写入后的记录 id（便于从草稿跳到正式数据）。
     *
     * <p>带 {@code targetId} 的草稿走"修改"分支：它写的是<b>已有记录</b>，
     * 而不是新插一条 —— 两者绝不能混，弄错就会凭空多出一份重复数据。
     */
    @Transactional(rollbackFor = Exception.class)
    public AgentDraft accept(Long draftId) {
        AgentDraft draft = require(draftId);
        requirePending(draft);

        boolean revising = draft.getTargetId() != null;
        Long appliedId;
        if (revising) {
            appliedId = switch (draft.getDraftType() == null ? "" : draft.getDraftType()) {
                case DraftType.CHARACTER -> applyCharacterRevision(draft);
                case DraftType.SETTING -> applySettingRevision(draft);
                case DraftType.BLUEPRINT -> applyBlueprintRevision(draft);
                default -> throw new BusinessException(ErrorCode.PARAM_INVALID,
                        "暂时不支持修改这类内容：" + draft.getDraftType());
            };
        } else {
            appliedId = switch (draft.getDraftType() == null ? "" : draft.getDraftType()) {
                case DraftType.CHARACTER -> applyCharacter(draft);
                case DraftType.SETTING -> applySetting(draft);
                case DraftType.BLUEPRINT -> applyBlueprint(draft);
                default -> throw new BusinessException(ErrorCode.PARAM_INVALID,
                        "未知的草稿类型: " + draft.getDraftType());
            };
        }

        draft.setStatus(DraftStatus.ACCEPTED);
        draft.setAppliedId(appliedId);
        draftMapper.updateById(draft);
        log.info("草稿 {}「{}」已确认{}，业务记录 {}",
                draft.getDraftType(), draft.getTitle(), revising ? "（修改）" : "", appliedId);
        return draft;
    }

    @Transactional
    public AgentDraft reject(Long draftId) {
        AgentDraft draft = require(draftId);
        requirePending(draft);
        draft.setStatus(DraftStatus.REJECTED);
        draftMapper.updateById(draft);
        return draft;
    }

    private void requirePending(AgentDraft draft) {
        if (!DraftStatus.PENDING.equals(draft.getStatus())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "该草稿已经处理过了（当前状态：" + draft.getStatus() + "）");
        }
    }

    // ==================== 各类草稿的落地 ====================

    private Long applyCharacter(AgentDraft draft) {
        Map<String, String> payload = payloadOf(draft);
        CharacterSaveRequest request = new CharacterSaveRequest();
        request.setName(firstNonBlank(payload.get("name"), draft.getTitle()));
        request.setRoleIdentity(payload.get("roleIdentity"));
        request.setDescription(payload.get("description"));
        CharacterProfile created = characterService.create(draft.getProjectId(), request);
        return created.getId();
    }

    private Long applySetting(AgentDraft draft) {
        Map<String, String> payload = payloadOf(draft);
        SettingSaveRequest request = new SettingSaveRequest();
        request.setTitle(firstNonBlank(payload.get("title"), draft.getTitle()));
        request.setContent(payload.get("content"));
        request.setTags(payload.get("tags"));
        request.setTriggerKeywords(payload.get("triggerKeywords"));
        request.setPriority(0);
        request.setEnabled(true);
        return settingService.create(draft.getProjectId(), request).getId();
    }

    /**
     * 蓝图落成蓝图树的一个节点。
     *
     * <p>摘要里会带上"详细走向"和"决策理由" —— 理由尤其不能丢：
     * 双 Agent 架构下，策划的取舍只能靠蓝图传给写作 Agent，不写就断了。
     */
    private Long applyBlueprint(AgentDraft draft) {
        Map<String, String> payload = payloadOf(draft);
        OutlineNodeSaveRequest request = new OutlineNodeSaveRequest();
        request.setNodeType(firstNonBlank(payload.get("nodeType"), "CHAPTER"));
        request.setName(firstNonBlank(payload.get("name"), draft.getTitle()));
        request.setSummary(joinSections(
                payload.get("summary"),
                payload.get("detail"),
                StringUtils.hasText(draft.getAgentReason()) ? "【决策理由】" + draft.getAgentReason() : null));
        request.setSortOrder(0);
        request.setStatus("PLANNED");
        return outlineService.create(draft.getProjectId(), request).getId();
    }

    /**
     * 修改已有的人物卡。
     *
     * <p>和蓝图一样：草稿里只放"Agent 想改成什么样"，没提到的字段沿用原值。
     * 这里尤其要注意 —— {@code CharacterService.update} 是属性全量覆盖，
     * 只传 description 会把姓名、身份一起清空。
     */
    private Long applyCharacterRevision(AgentDraft draft) {
        CharacterProfile profile = characterService.requireById(draft.getTargetId());
        Map<String, String> payload = payloadOf(draft);

        CharacterSaveRequest request = new CharacterSaveRequest();
        request.setName(firstNonBlank(payload.get("name"), profile.getName()));
        request.setRoleIdentity(firstNonBlank(payload.get("roleIdentity"), profile.getRoleIdentity()));
        request.setDescription(firstNonBlank(payload.get("description"), profile.getDescription()));
        request.setExampleDialogue(firstNonBlank(payload.get("exampleDialogue"), profile.getExampleDialogue()));
        request.setEnabled(profile.getEnabled());
        characterService.update(profile.getId(), request);
        return profile.getId();
    }

    /** 修改已有的设定条目（同样要把字段补齐，理由见上） */
    private Long applySettingRevision(AgentDraft draft) {
        SettingEntry entry = settingService.requireById(draft.getTargetId());
        Map<String, String> payload = payloadOf(draft);

        SettingSaveRequest request = new SettingSaveRequest();
        request.setTitle(firstNonBlank(payload.get("title"), entry.getTitle()));
        request.setContent(firstNonBlank(payload.get("content"), entry.getContent()));
        request.setTags(firstNonBlank(payload.get("tags"), entry.getTags()));
        request.setTriggerKeywords(firstNonBlank(payload.get("triggerKeywords"), entry.getTriggerKeywords()));
        request.setPriority(entry.getPriority());
        request.setEnabled(entry.getEnabled());
        settingService.update(entry.getId(), request);
        return entry.getId();
    }

    /**
     * 修改已有的蓝图节点。
     *
     * <p>草稿里只放"Agent 想改成什么样"。凡是他没提到的字段，一律沿用节点原值 ——
     * 因为 {@code OutlineService.update} 是属性全量覆盖，漏传哪个字段哪个字段就被清空。
     * 这个坑本项目已经踩过一次（角色配置的 baseUrl"清除"没生效），不重蹈。
     */
    private Long applyBlueprintRevision(AgentDraft draft) {
        PlotOutline node = outlineService.requireById(draft.getTargetId());
        Map<String, String> payload = payloadOf(draft);

        OutlineNodeSaveRequest request = new OutlineNodeSaveRequest();
        request.setName(firstNonBlank(payload.get("name"), node.getName()));
        request.setNodeType(firstNonBlank(payload.get("nodeType"), node.getNodeType()));
        request.setSummary(firstNonBlank(payload.get("summary"), node.getSummary()));
        request.setParentId(node.getParentId());
        request.setSortOrder(node.getSortOrder());
        request.setStatus(node.getStatus());
        request.setMainCharacters(node.getMainCharacters());
        outlineService.update(node.getId(), request);
        return node.getId();
    }

    // ==================== JSON 出入 ====================
    private String writeJson(Map<String, Object> payload) {
        try {
            return MAPPER.writeValueAsString(payload == null ? Map.of() : payload);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "草稿内容无法序列化: " + e.getMessage());
        }
    }

    /** 读草稿内容。只取字符串值 —— 草稿本就是给人看、给人改的文本 */
    public Map<String, String> payloadOf(AgentDraft draft) {
        Map<String, String> map = new LinkedHashMap<>();
        if (!StringUtils.hasText(draft.getPayloadJson())) {
            return map;
        }
        try {
            JsonNode node = MAPPER.readTree(draft.getPayloadJson());
            node.fields().forEachRemaining(entry -> {
                if (!entry.getValue().isNull()) {
                    map.put(entry.getKey(), entry.getValue().asText(""));
                }
            });
        } catch (Exception e) {
            // 草稿内容坏了不该让整个会话打不开，按空处理并留痕
            log.warn("草稿 {} 内容解析失败: {}", draft.getId(), e.getMessage());
        }
        return map;
    }

    private String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (StringUtils.hasText(candidate)) {
                return candidate.trim();
            }
        }
        return null;
    }

    private String joinSections(String... parts) {
        List<String> kept = new ArrayList<>();
        for (String part : parts) {
            if (StringUtils.hasText(part)) {
                kept.add(part.trim());
            }
        }
        return String.join("\n\n", kept);
    }
}
