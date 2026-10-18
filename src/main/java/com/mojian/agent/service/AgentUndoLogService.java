package com.mojian.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mojian.agent.draft.DraftStatus;
import com.mojian.agent.entity.AgentDraft;
import com.mojian.agent.entity.AgentUndoLog;
import com.mojian.agent.mapper.AgentDraftMapper;
import com.mojian.agent.mapper.AgentUndoLogMapper;
import com.mojian.outline.entity.PlotOutline;
import com.mojian.outline.mapper.PlotOutlineMapper;
import com.mojian.outline.service.OutlineService;
import com.mojian.world.character.entity.CharacterProfile;
import com.mojian.world.character.mapper.CharacterProfileMapper;
import com.mojian.world.character.service.CharacterService;
import com.mojian.world.setting.entity.SettingEntry;
import com.mojian.world.setting.mapper.SettingEntryMapper;
import com.mojian.world.setting.service.SettingService;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 对话回滚的数据库撤销。
 *
 * <p>只撤销策划 Agent 经"草稿确认"写进业务表的结构性改动：
 * 人物、设定、蓝图。<b>不碰正文</b>（正文有版本机制，且写作会话回滚不在当前 UI 里）。
 * 撤销不是删除历史：被撤销的草稿会退回 PENDING，用户可以在"待你确认"里重新决定。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentUndoLogService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AgentUndoLogMapper undoLogMapper;
    private final AgentDraftMapper draftMapper;
    private final CharacterProfileMapper characterProfileMapper;
    private final SettingEntryMapper settingEntryMapper;
    private final PlotOutlineMapper plotOutlineMapper;
    private final CharacterService characterService;
    private final SettingService settingService;
    private final OutlineService outlineService;
    private final ConversationService conversationService;

    /** 新增类草稿确认后登记：撤销时删除该记录 */
    public void recordCreation(AgentDraft draft, String entityType, Long entityId) {
        AgentUndoLog log = baseLog(draft, entityType, entityId);
        log.setActionType("CREATED");
        undoLogMapper.insert(log);
    }

    /** 修改类草稿确认前登记旧值：撤销时把字段写回 */
    public void recordUpdate(AgentDraft draft, String entityType, Long entityId, String beforeJson) {
        AgentUndoLog log = baseLog(draft, entityType, entityId);
        log.setActionType("UPDATED");
        log.setBeforeJson(beforeJson);
        undoLogMapper.insert(log);
    }

    private AgentUndoLog baseLog(AgentDraft draft, String entityType, Long entityId) {
        AgentUndoLog log = new AgentUndoLog();
        log.setProjectId(draft.getProjectId());
        log.setConversationId(draft.getConversationId());
        log.setTurnIndex(conversationService.countUserMessages(draft.getConversationId()));
        log.setEntityType(entityType);
        log.setEntityId(entityId);
        log.setDraftId(draft.getId());
        return log;
    }

    /**
     * 撤销某会话在 {@code keepUserMessages} 之后发生的数据库改动。
     *
     * @return 撤销的条数
     */
    public int undoAfter(Long conversationId, int keepUserMessages) {
        List<AgentUndoLog> logs = undoLogMapper.selectAfterTurn(conversationId, keepUserMessages);
        int undone = 0;
        List<String> failures = new ArrayList<>();
        for (AgentUndoLog entry : logs) {
            try {
                switch (entry.getActionType()) {
                    case "CREATED" -> deleteCreated(entry);
                    case "UPDATED" -> restoreUpdated(entry);
                    default -> throw new IllegalStateException("未知撤销动作 " + entry.getActionType());
                }
                if (entry.getDraftId() != null) {
                    AgentDraft draft = draftMapper.selectById(entry.getDraftId());
                    if (draft != null) {
                        draft.setStatus(DraftStatus.PENDING);
                        draft.setAppliedId(null);
                        draftMapper.updateById(draft);
                    }
                }
                undone++;
            } catch (Exception e) {
                failures.add(entry.getEntityType() + "#" + entry.getEntityId() + "：" + e.getMessage());
                log.warn("撤销 {} {} 失败（继续处理其余）: {}", entry.getEntityType(), entry.getEntityId(), e.getMessage());
            }
        }
        if (!failures.isEmpty()) {
            throw new IllegalStateException("部分数据库改动撤销失败：" + String.join("；", failures));
        }
        log.info("会话 {} 回滚：撤销了 {} 条数据库改动", conversationId, undone);
        return undone;
    }

    private void deleteCreated(AgentUndoLog log) {
        switch (log.getEntityType()) {
            case "CHARACTER" -> {
                if (characterService.getById(log.getEntityId()) != null) {
                    characterService.delete(log.getEntityId());
                }
            }
            case "SETTING" -> {
                if (settingService.getById(log.getEntityId()) != null) {
                    settingService.delete(log.getEntityId());
                }
            }
            case "OUTLINE" -> {
                if (outlineService.getById(log.getEntityId()) != null) {
                    outlineService.delete(log.getEntityId());
                }
            }
            default -> throw new IllegalStateException("未知实体类型 " + log.getEntityType());
        }
    }

    private void restoreUpdated(AgentUndoLog log) {
        Map<String, Object> before = readSnapshot(log.getBeforeJson());
        switch (log.getEntityType()) {
            case "CHARACTER" -> characterProfileMapper.update(null,
                    new LambdaUpdateWrapper<CharacterProfile>()
                            .eq(CharacterProfile::getId, log.getEntityId())
                            .set(CharacterProfile::getName, before.get("name"))
                            .set(CharacterProfile::getRoleIdentity, before.get("role_identity"))
                            .set(CharacterProfile::getDescription, before.get("description"))
                            .set(CharacterProfile::getExampleDialogue, before.get("example_dialogue"))
                            .set(CharacterProfile::getEnabled, before.get("enabled")));
            case "SETTING" -> settingEntryMapper.update(null,
                    new LambdaUpdateWrapper<SettingEntry>()
                            .eq(SettingEntry::getId, log.getEntityId())
                            .set(SettingEntry::getTitle, before.get("title"))
                            .set(SettingEntry::getContent, before.get("content"))
                            .set(SettingEntry::getTags, before.get("tags"))
                            .set(SettingEntry::getTriggerKeywords, before.get("trigger_keywords"))
                            .set(SettingEntry::getPriority, before.get("priority"))
                            .set(SettingEntry::getChapterScope, before.get("chapter_scope"))
                            .set(SettingEntry::getEnabled, before.get("enabled")));
            case "OUTLINE" -> plotOutlineMapper.update(null,
                    new LambdaUpdateWrapper<PlotOutline>()
                            .eq(PlotOutline::getId, log.getEntityId())
                            .set(PlotOutline::getParentId, before.get("parent_id"))
                            .set(PlotOutline::getNodeType, before.get("node_type"))
                            .set(PlotOutline::getName, before.get("name"))
                            .set(PlotOutline::getSummary, before.get("summary"))
                            .set(PlotOutline::getMainCharacters, before.get("main_characters"))
                            .set(PlotOutline::getSortOrder, before.get("sort_order"))
                            .set(PlotOutline::getStatus, before.get("status")));
            default -> throw new IllegalStateException("未知实体类型 " + log.getEntityType());
        }
    }

    /** 在改动发生前立即序列化旧值 —— 业务实体常被 MyBatis 一级缓存复用，晚一步就会被改掉 */
    public String snapshotOf(Object entity) {
        try {
            Map<String, Object> map = new LinkedHashMap<>();
            if (entity instanceof CharacterProfile character) {
                map.put("name", character.getName());
                map.put("role_identity", character.getRoleIdentity());
                map.put("description", character.getDescription());
                map.put("example_dialogue", character.getExampleDialogue());
                map.put("enabled", character.getEnabled());
            } else if (entity instanceof SettingEntry setting) {
                map.put("title", setting.getTitle());
                map.put("content", setting.getContent());
                map.put("tags", setting.getTags());
                map.put("trigger_keywords", setting.getTriggerKeywords());
                map.put("priority", setting.getPriority());
                map.put("chapter_scope", setting.getChapterScope());
                map.put("enabled", setting.getEnabled());
            } else if (entity instanceof PlotOutline node) {
                map.put("parent_id", node.getParentId());
                map.put("node_type", node.getNodeType());
                map.put("name", node.getName());
                map.put("summary", node.getSummary());
                map.put("main_characters", node.getMainCharacters());
                map.put("sort_order", node.getSortOrder());
                map.put("status", node.getStatus());
            } else {
                throw new IllegalArgumentException("不支持的实体类型: " + entity);
            }
            return MAPPER.writeValueAsString(map);
        } catch (Exception e) {
            throw new IllegalStateException("快照序列化失败: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readSnapshot(String json) {
        try {
            return MAPPER.readValue(json, Map.class);
        } catch (Exception e) {
            throw new IllegalStateException("撤销快照读取失败: " + e.getMessage(), e);
        }
    }
}
