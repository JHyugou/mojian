package com.mojian.generation.context;

import com.mojian.generation.GenerationConst;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Prompt 渲染器：把 ContextSnapshot 渲染为 system / user 提示词。
 * 使用【标记】分段，便于模型对齐，也便于 Mock 客户端嵌入上下文要素。
 */
@Component
public class PromptRenderer {

    public String systemPrompt(ContextSnapshot snapshot, String taskType) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一位中文长篇小说创作助手。严格遵守：只依据给定资料创作，")
          .append("不得虚构与资料矛盾的人物、设定或前情；输出纯正文或方案，不要解释你的工作过程。");
        if (StringUtils.hasText(snapshot.getStyleRules())) {
            sb.append('\n').append(snapshot.getStyleRules());
        }
        if (GenerationConst.TYPE_REWRITE.equals(taskType) || GenerationConst.TYPE_EXPAND_SHRINK.equals(taskType)) {
            sb.append("\n只输出改写后的文本，不要输出原文或其他说明。");
        }
        return sb.toString();
    }

    public String userPrompt(ContextSnapshot snapshot, String taskType) {
        StringBuilder sb = new StringBuilder();
        section(sb, "任务类型", taskLabel(taskType));
        section(sb, "作品", snapshot.getProjectName());
        if (snapshot.getOutline() != null) {
            section(sb, "章节标题", snapshot.getChapterTitle());
            section(sb, "蓝图节点", joinFields(
                    snapshot.getOutline().getName(), snapshot.getOutline().getSummary()));
        } else if (StringUtils.hasText(snapshot.getChapterTitle())) {
            section(sb, "章节标题", snapshot.getChapterTitle());
        }
        if (snapshot.getCharacters() != null && !snapshot.getCharacters().isEmpty()) {
            for (ContextSnapshot.CharacterBrief c : snapshot.getCharacters()) {
                section(sb, "人物档案", joinFields(
                        c.getName(), prefix("身份：", c.getRoleIdentity()),
                        prefix("设定：", c.getDescription()),
                        prefix("对话示例：", c.getExampleDialogue())));
            }
        }
        if (snapshot.getSettings() != null) {
            for (ContextSnapshot.SettingBrief s : snapshot.getSettings()) {
                section(sb, "故事设定", s.getTitle() + "：" + s.getContent());
            }
        }
        if (snapshot.getPreviousMemories() != null) {
            for (ContextSnapshot.MemoryBrief m : snapshot.getPreviousMemories()) {
                section(sb, "前文记忆", joinFields(
                        prefix("来自：", m.getChapterTitle()), m.getMemory()));
            }
        }
        if (StringUtils.hasText(snapshot.getPreviousContentTail())) {
            section(sb, "上一章正文尾部", snapshot.getPreviousContentTail());
        }
        if (StringUtils.hasText(snapshot.getCurrentContent())) {
            section(sb, "现有正文", snapshot.getCurrentContent());
            if (GenerationConst.TYPE_CONTINUE.equals(taskType)) {
                sb.append("（续写时从上文自然衔接，不要复述已有内容）\n");
            }
        }
        section(sb, "剧情方案", snapshot.getConfirmedPlan());
        section(sb, "选中文本", snapshot.getSelection());
        if (StringUtils.hasText(snapshot.getBeforeSelection())) {
            section(sb, "选中文本前文", snapshot.getBeforeSelection());
        }
        if (StringUtils.hasText(snapshot.getAfterSelection())) {
            section(sb, "选中文本后文", snapshot.getAfterSelection());
        }
        section(sb, "创作指令", snapshot.getInstruction());
        sb.append("\n请开始输出。");
        return sb.toString();
    }

    private String taskLabel(String taskType) {
        return switch (taskType) {
            case GenerationConst.TYPE_PLOT_PLAN -> "生成剧情方案（分 3-5 个情节点，含冲突与收束钩子）";
            case GenerationConst.TYPE_CONTENT -> "依据剧情方案生成整章正文";
            case GenerationConst.TYPE_CONTINUE -> "续写现有正文";
            case GenerationConst.TYPE_REWRITE -> "改写选中文本，保持情节不变";
            case GenerationConst.TYPE_EXPAND_SHRINK -> "扩写或缩写选中文本";
            case GenerationConst.TYPE_CHAPTER_MEMORY -> "提炼章节记忆（摘要/重要事实/未解决冲突/人物状态变化）";
            default -> taskType;
        };
    }

    private void section(StringBuilder sb, String marker, String value) {
        if (!StringUtils.hasText(value)) {
            return;
        }
        sb.append("【").append(marker).append("】").append(value.trim()).append('\n');
    }

    private String joinFields(String... fields) {
        StringBuilder sb = new StringBuilder();
        for (String field : fields) {
            if (StringUtils.hasText(field)) {
                if (!sb.isEmpty()) {
                    sb.append("；");
                }
                sb.append(field.trim());
            }
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    private String prefix(String label, String value) {
        return StringUtils.hasText(value) ? label + value : null;
    }
}
