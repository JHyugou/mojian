package com.mojian.world.character.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.mojian.common.api.ErrorCode;
import com.mojian.common.exception.BusinessException;
import com.mojian.generation.context.ContextAssembler;
import com.mojian.generation.llm.LlmClient;
import com.mojian.generation.llm.LlmRequest;
import com.mojian.project.service.ProjectService;
import com.mojian.world.setting.entity.SettingEntry;
import com.mojian.world.setting.service.SettingService;
import com.mojian.world.character.dto.CharacterNodeSaveRequest;
import com.mojian.world.character.dto.CharacterSaveRequest;
import com.mojian.world.character.entity.CharacterNode;
import com.mojian.world.character.entity.CharacterProfile;
import com.mojian.world.character.mapper.CharacterNodeMapper;
import com.mojian.world.character.mapper.CharacterProfileMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class CharacterService extends ServiceImpl<CharacterProfileMapper, CharacterProfile> {

    private final ProjectService projectService;
    private final LlmClient llmClient;
    private final JdbcTemplate jdbcTemplate;
    private final CharacterNodeMapper nodeMapper;
    private final SettingService settingService;
    private final ContextAssembler contextAssembler;

    /** 与 schema.sql 第 10 节保持一致：人物版本节点（起点制，单设定大框快照） */
    private static final String CHARACTER_NODE_DDL = """
            CREATE TABLE IF NOT EXISTS character_node (
                id            BIGINT       NOT NULL COMMENT '主键（雪花ID）',
                character_id  BIGINT       NOT NULL COMMENT '所属人物档案',
                chapter_from  INT          NOT NULL COMMENT '生效起点章（含），直至下一节点',
                description   TEXT COMMENT '本阶段人物设定全文（外貌/性格/能力/关系/状态的融合叙述）',
                created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                PRIMARY KEY (id),
                KEY idx_cnode_character (character_id)
            ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '人物版本节点'
            """;

    /**
     * 老库结构演进（幂等，启动时按序执行，任何历史版本的库都能一步步追到最新形态）：
     * 1. abilities 列补列；
     * 2. 核心目标/主要矛盾 并入 人物状态(current_status)；
     * 3. 七个细分字段合并为单个 description（SillyTavern 角色卡式大段描述，标签段落保留结构）；
     * 4. 版本节点表建表，并做与档案一致的 description 瘦身。
     */
    @PostConstruct
    public void migrateSchema() {
        // ---- 1. abilities 补列（仅历史中间形态：新装库无 personality 列，无需经历此步） ----
        if (!columnExists("character_profile", "abilities")
                && columnExists("character_profile", "personality")) {
            jdbcTemplate.execute("ALTER TABLE character_profile "
                    + "ADD COLUMN abilities TEXT COMMENT '能力 / 武功境界（武功、术法、境界、本命器等）' "
                    + "AFTER personality");
            log.info("character_profile 已自动补列 abilities");
        }

        // ---- 2. 核心目标/主要矛盾 并入 current_status ----
        if (columnExists("character_profile", "core_goal")) {
            jdbcTemplate.update("UPDATE character_profile SET current_status = CONCAT_WS('；', "
                    + "NULLIF(current_status, ''), "
                    + "CASE WHEN core_goal IS NOT NULL AND core_goal != '' "
                    + "THEN CONCAT('目标：', core_goal) END, "
                    + "CASE WHEN main_conflict IS NOT NULL AND main_conflict != '' "
                    + "THEN CONCAT('矛盾：', main_conflict) END) "
                    + "WHERE (core_goal IS NOT NULL AND core_goal != '') "
                    + "OR (main_conflict IS NOT NULL AND main_conflict != '')");
            jdbcTemplate.execute("ALTER TABLE character_profile DROP COLUMN core_goal");
            jdbcTemplate.execute("ALTER TABLE character_profile DROP COLUMN main_conflict");
            log.info("character_profile 已将核心目标/主要矛盾并入人物状态");
        }

        // ---- 3. 七个细分字段合并为 description ----
        if (!columnExists("character_profile", "description")) {
            jdbcTemplate.execute("ALTER TABLE character_profile "
                    + "ADD COLUMN description TEXT COMMENT '人物设定全文（SillyTavern 式大段描述）' "
                    + "AFTER role_identity");
            jdbcTemplate.update("UPDATE character_profile SET description = CONCAT_WS(CHAR(10), "
                    + "NULLIF(CONCAT('【外貌】', appearance), '【外貌】'), "
                    + "NULLIF(CONCAT('【性格】', personality), '【性格】'), "
                    + "NULLIF(CONCAT('【能力】', abilities), '【能力】'), "
                    + "NULLIF(CONCAT('【关系】', relationships), '【关系】'), "
                    + "NULLIF(CONCAT('【语言风格】', speech_style), '【语言风格】'), "
                    + "NULLIF(CONCAT('【状态】', current_status), '【状态】'), "
                    + "NULLIF(CONCAT('【弧线】', character_arc), '【弧线】'))");
            jdbcTemplate.execute("ALTER TABLE character_profile "
                    + "DROP COLUMN appearance, DROP COLUMN personality, DROP COLUMN abilities, "
                    + "DROP COLUMN relationships, DROP COLUMN speech_style, "
                    + "DROP COLUMN current_status, DROP COLUMN character_arc");
            log.info("character_profile 七个细分字段已合并为 description");
        }

        // ---- 4. 版本节点表：建表 + 旧形态瘦身 ----
        jdbcTemplate.execute(CHARACTER_NODE_DDL);
        if (columnExists("character_node", "personality")) {
            jdbcTemplate.execute("ALTER TABLE character_node "
                    + "ADD COLUMN description TEXT COMMENT '本阶段人物设定全文' AFTER chapter_from");
            jdbcTemplate.update("UPDATE character_node SET description = CONCAT_WS(CHAR(10), "
                    + "NULLIF(CONCAT('【身份】', role_identity), '【身份】'), "
                    + "NULLIF(CONCAT('【外貌】', appearance), '【外貌】'), "
                    + "NULLIF(CONCAT('【性格】', personality), '【性格】'), "
                    + "NULLIF(CONCAT('【能力】', abilities), '【能力】'), "
                    + "NULLIF(CONCAT('【状态】', current_status), '【状态】'), "
                    + "NULLIF(CONCAT('【关系】', relationships), '【关系】'), "
                    + "NULLIF(CONCAT('【语言风格】', speech_style), '【语言风格】'), "
                    + "NULLIF(CONCAT('【弧线】', character_arc), '【弧线】'))");
            jdbcTemplate.execute("ALTER TABLE character_node "
                    + "DROP COLUMN role_identity, DROP COLUMN appearance, DROP COLUMN personality, "
                    + "DROP COLUMN abilities, DROP COLUMN current_status, DROP COLUMN relationships, "
                    + "DROP COLUMN speech_style, DROP COLUMN example_dialogue, DROP COLUMN character_arc");
            log.info("character_node 已瘦身合并为 description 快照");
        }

        // ---- 5. 章节记忆：四字段合并为单个 memory 大框 ----
        if (columnExists("chapter_memory", "summary")) {
            jdbcTemplate.execute("ALTER TABLE chapter_memory "
                    + "ADD COLUMN memory TEXT COMMENT '记忆全文（摘要/重要事实/未解决冲突/人物状态变化的融合叙述）' "
                    + "AFTER chapter_id");
            jdbcTemplate.update("UPDATE chapter_memory SET memory = CONCAT_WS(CHAR(10), "
                    + "NULLIF(CONCAT('【章节摘要】', summary), '【章节摘要】'), "
                    + "NULLIF(CONCAT('【重要事实】', key_facts), '【重要事实】'), "
                    + "NULLIF(CONCAT('【未解决冲突】', unresolved_conflicts), '【未解决冲突】'), "
                    + "NULLIF(CONCAT('【人物状态变化】', character_changes), '【人物状态变化】'))");
            jdbcTemplate.execute("ALTER TABLE chapter_memory "
                    + "DROP COLUMN summary, DROP COLUMN key_facts, "
                    + "DROP COLUMN unresolved_conflicts, DROP COLUMN character_changes");
            log.info("chapter_memory 四字段已合并为 memory 大框");
        }
    }

    private boolean columnExists(String table, String column) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                Integer.class, table, column);
        return count != null && count > 0;
    }

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
        nodeMapper.deleteByCharacterId(characterId);
        removeById(characterId);
    }

    // ==================== 版本节点（起点制人物演变） ====================

    public List<CharacterNode> listNodes(Long characterId) {
        requireById(characterId);
        return nodeMapper.selectByCharacterId(characterId);
    }

    public CharacterNode createNode(Long characterId, CharacterNodeSaveRequest request) {
        requireById(characterId);
        validateNodeStart(characterId, request.getChapterFrom(), null);
        CharacterNode node = new CharacterNode();
        BeanUtils.copyProperties(request, node);
        node.setCharacterId(characterId);
        nodeMapper.insert(node);
        return node;
    }

    public CharacterNode updateNode(Long nodeId, CharacterNodeSaveRequest request) {
        CharacterNode node = requireNode(nodeId);
        validateNodeStart(node.getCharacterId(), request.getChapterFrom(), nodeId);
        BeanUtils.copyProperties(request, node);
        nodeMapper.updateById(node);
        return node;
    }

    public void deleteNode(Long nodeId) {
        nodeMapper.deleteById(requireNode(nodeId).getId());
    }

    /** 起点校验：必须 ≥2，且不得与该人物已有节点重复（起点制下重复即歧义） */
    private void validateNodeStart(Long characterId, Integer chapterFrom, Long excludeNodeId) {
        if (chapterFrom == null || chapterFrom < 2) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "版本节点需从第 2 章起生效（第 1 章使用基础设定）");
        }
        boolean duplicated = nodeMapper.selectByCharacterId(characterId).stream()
                .anyMatch(n -> !n.getId().equals(excludeNodeId)
                        && chapterFrom.equals(n.getChapterFrom()));
        if (duplicated) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "该章节起点已存在版本节点");
        }
    }

    private CharacterNode requireNode(Long nodeId) {
        CharacterNode node = nodeMapper.selectById(nodeId);
        if (node == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "人物版本节点不存在");
        }
        return node;
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
        section(sb, "人物设定", profile.getDescription());

        // 世界观注入：按人物语料做关键词/优先级筛选，让对话自然落进本书的术语与规则
        String matchText = String.join("\n",
                StringUtils.hasText(profile.getName()) ? profile.getName() : "",
                StringUtils.hasText(profile.getRoleIdentity()) ? profile.getRoleIdentity() : "",
                StringUtils.hasText(profile.getDescription()) ? profile.getDescription() : "",
                StringUtils.hasText(hint) ? hint : "");
        List<SettingEntry> settings = contextAssembler.selectSettingsForCharacterDialogue(
                settingService.list(profile.getProjectId(), null), matchText);
        for (SettingEntry s : settings) {
            section(sb, "世界观设定", s.getTitle() + "：" + s.getContent());
        }

        // 其他人物：让对话使用真实姓名并体现与主角的关系
        List<CharacterProfile> others = contextAssembler.selectOthersForCharacterDialogue(
                list(profile.getProjectId()), characterId, profile.getDescription());
        for (CharacterProfile other : others) {
            StringBuilder ob = new StringBuilder(other.getName());
            if (StringUtils.hasText(other.getRoleIdentity())) {
                ob.append("（").append(other.getRoleIdentity()).append("）");
            }
            if (StringUtils.hasText(other.getDescription())) {
                ob.append("：").append(other.getDescription());
            }
            section(sb, "其他人物", ob.toString());
        }

        section(sb, "已有对话示例（延续其风格）", profile.getExampleDialogue());
        section(sb, "补充要求", hint);

        String system = "你是中文小说的人物台词设计助手。根据人物资料写 4-6 行对话示例，"
                + "体现其性格、语言习惯与人物状态；若涉及其他人物，使用其真实姓名并体现与主角的关系；"
                + "对话需符合给定世界观设定，可自然使用其中的术语，但不要罗列设定。只输出对话本身。";
        return llmClient.chat(LlmRequest.of(system, sb.toString(), "EXAMPLE_DIALOGUE"));
    }

    private void section(StringBuilder sb, String marker, String value) {
        if (StringUtils.hasText(value)) {
            sb.append("【").append(marker).append("】").append(value.trim()).append('\n');
        }
    }
}
