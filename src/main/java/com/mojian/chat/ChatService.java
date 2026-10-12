package com.mojian.chat;

import com.mojian.common.api.ErrorCode;
import com.mojian.common.exception.BusinessException;
import com.mojian.generation.llm.LlmClient;
import com.mojian.generation.llm.LlmRequest;
import com.mojian.outline.vo.OutlineNodeVO;
import com.mojian.outline.service.OutlineService;
import com.mojian.project.entity.StoryProject;
import com.mojian.project.service.ProjectService;
import com.mojian.world.character.entity.CharacterNode;
import com.mojian.world.character.entity.CharacterProfile;
import com.mojian.world.character.service.CharacterService;
import com.mojian.world.setting.entity.SettingEntry;
import com.mojian.world.setting.service.SettingService;
import com.mojian.chapter.entity.Chapter;
import com.mojian.chapter.entity.ChapterMemory;
import com.mojian.chapter.mapper.ChapterMemoryMapper;
import com.mojian.chapter.service.ChapterService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

/**
 * AI 创作助手（FR-023）：全书资料上下文的聊天机器人，
 * 支持创作建议、头脑风暴和生成可导入的人物/设定/蓝图 JSON。
 */
@Service
@RequiredArgsConstructor
public class ChatService {

    private final ProjectService projectService;
    private final CharacterService characterService;
    private final SettingService settingService;
    private final ChapterService chapterService;
    private final ChapterMemoryMapper chapterMemoryMapper;
    private final OutlineService outlineService;
    private final LlmClient llmClient;

    /** 组装全书上下文的 system prompt */
    public String buildSystemPrompt(Long projectId) {
        StoryProject project = projectService.requireById(projectId);

        StringBuilder sb = new StringBuilder();
        sb.append("你是一位中文长篇小说的创作顾问。以下是用户当前小说项目的全部资料，"
                + "请据此回答用户的创作问题、给出建议，或在用户要求时生成可导入的 JSON。\n\n");
        sb.append("== 项目信息 ==\n");
        sb.append("书名：").append(project.getName()).append('\n');
        if (StringUtils.hasText(project.getGenre())) {
            sb.append("题材：").append(project.getGenre()).append('\n');
        }
        if (StringUtils.hasText(project.getIntro())) {
            sb.append("简介：").append(project.getIntro()).append('\n');
        }
        if (StringUtils.hasText(project.getStyle())) {
            sb.append("文风：").append(project.getStyle()).append('\n');
        }
        if (StringUtils.hasText(project.getWritingRules())) {
            sb.append("写作规则：").append(project.getWritingRules()).append('\n');
        }

        // 人物（含演变节点）
        List<CharacterProfile> characters = characterService.list(projectId);
        if (!characters.isEmpty()) {
            sb.append("\n== 人物档案 ==\n");
            for (CharacterProfile c : characters) {
                sb.append("【").append(c.getName()).append("】");
                if (StringUtils.hasText(c.getRoleIdentity())) {
                    sb.append(" 身份：").append(c.getRoleIdentity()).append("；");
                }
                if (StringUtils.hasText(c.getDescription())) {
                    sb.append(' ').append(c.getDescription());
                }
                List<CharacterNode> nodes = characterService.listNodes(c.getId());
                for (CharacterNode node : nodes) {
                    if (StringUtils.hasText(node.getDescription())) {
                        sb.append("\n  【自第").append(node.getChapterFrom())
                                .append("章起】").append(node.getDescription());
                    }
                }
                sb.append('\n');
            }
        }

        // 设定
        List<SettingEntry> settings = settingService.list(projectId, null);
        if (!settings.isEmpty()) {
            sb.append("\n== 世界观设定 ==\n");
            for (SettingEntry s : settings) {
                sb.append("【").append(s.getTitle()).append("】");
                if (StringUtils.hasText(s.getContent())) {
                    sb.append(' ').append(s.getContent());
                }
                sb.append('\n');
            }
        }

        // 蓝图摘要
        var tree = outlineService.tree(projectId);
        if (!tree.isEmpty()) {
            sb.append("\n== 剧情蓝图 ==\n");
            appendOutlineBrief(sb, tree, 0);
        }

        // 章节记忆
        var chapters = chapterService.list(projectId);
        boolean hasMemory = false;
        for (Chapter c : chapters) {
            var memory = chapterMemoryMapper.selectByChapterId(c.getId());
            if (memory != null && StringUtils.hasText(memory.getMemory())) {
                if (!hasMemory) {
                    sb.append("\n== 章节记忆 ==\n");
                    hasMemory = true;
                }
                sb.append("【").append(c.getTitle()).append("】").append(memory.getMemory()).append('\n');
            }
        }

        sb.append("\n== JSON 生成规则 ==\n");
        sb.append("当用户要求生成人物、设定或蓝图时，请用 ```json 代码块输出，"
                + "确保可以直接导入。三种格式的字段如下：\n");
        sb.append("- 人物设定集 data 数组元素字段：name(必填), roleIdentity, description(大段自然语言), "
                + "exampleDialogue, enabled(true), nodes[{chapterFrom(≥2), description}]\n");
        sb.append("- 世界观设定 data 数组元素字段：title(必填), content(必填), tags(逗号分隔), "
                + "triggerKeywords(逗号分隔), priority(0-100), chapterScope(如 3-30), enabled(true)\n");
        sb.append("- 蓝图 data 为根节点数组，每个节点字段：nodeType(VOLUME/ARC/CHAPTER), name(必填), "
                + "summary, mainCharacters(人物ID的JSON数组字符串), sortOrder, status(PLANNED), "
                + "children[子节点数组，结构相同]\n");
        sb.append("生成时请确保内容与已有资料一致，不得矛盾。\n");
        return sb.toString();
    }

    private void appendOutlineBrief(StringBuilder sb, List<OutlineNodeVO> nodes, int depth) {
        String indent = "  ".repeat(depth);
        for (OutlineNodeVO node : nodes) {
            sb.append(indent).append(node.getNodeType()).append("：").append(node.getName());
            if (StringUtils.hasText(node.getSummary())) {
                sb.append(" — ").append(node.getSummary());
            }
            sb.append('\n');
            if (node.getChildren() != null && !node.getChildren().isEmpty()) {
                appendOutlineBrief(sb, node.getChildren(), depth + 1);
            }
        }
    }

    /** 发送聊天消息并返回 AI 回复 */
    public String chat(Long projectId, String message, List<Map<String, String>> history) {
        projectService.requireById(projectId);
        if (!StringUtils.hasText(message)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "消息不能为空");
        }

        String system = buildSystemPrompt(projectId);
        StringBuilder userContent = new StringBuilder();
        if (history != null) {
            for (Map<String, String> h : history) {
                String role = h.getOrDefault("role", "user");
                String content = h.getOrDefault("content", "");
                if (StringUtils.hasText(content)) {
                    userContent.append(role.equals("user") ? "用户：" : "助手：")
                            .append(content).append('\n');
                }
            }
        }
        userContent.append("用户：").append(message);
        return llmClient.chat(LlmRequest.of(system, userContent.toString(), "CHAT"));
    }
}
