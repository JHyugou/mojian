package com.mojian.agent.tool.impl;

import com.mojian.agent.AgentContext;
import com.mojian.agent.tool.AgentTool;
import com.mojian.world.setting.entity.SettingEntry;
import com.mojian.world.setting.service.SettingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

/**
 * 查世界观设定（返回记录 ID）。
 *
 * <p>材料里已经有全部设定了，但那份<b>不带 ID</b>。要修改某一条，
 * 就必须先拿到 ID —— 这个工具补的就是这一点。
 */
@Component
@RequiredArgsConstructor
public class QuerySettingTool implements AgentTool {

    /** 单条设定回给模型的字数上限：这个工具是"定位 + 确认"，不是"通读全库" */
    private static final int MAX_CHARS = 1200;

    private final SettingService settingService;

    @Override
    public String name() {
        return "query_setting";
    }

    @Override
    public String description() {
        return "查询本书的世界观设定，返回每条设定的记录 ID、标题与内容。"
                + "当你打算修改某条设定（用 update_setting）时，先用本工具拿到它的记录 ID。"
                + "参数 keyword 可留空表示列出全部设定的标题。";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "keyword", Map.of("type", "string",
                                "description", "按标题、内容或标签过滤；留空只列出全部标题")),
                "required", List.of());
    }

    @Override
    public String execute(AgentContext context, Map<String, Object> arguments) {
        Long projectId = context.requireProjectId();
        String keyword = arguments.get("keyword") == null
                ? "" : String.valueOf(arguments.get("keyword")).trim();

        List<SettingEntry> all = settingService.list(projectId, null);
        if (all.isEmpty()) {
            return "本书目前还没有建立任何世界观设定。";
        }

        if (keyword.isEmpty()) {
            StringBuilder sb = new StringBuilder("本书共 " + all.size() + " 条设定：\n");
            for (SettingEntry entry : all) {
                sb.append("- ").append(entry.getTitle())
                        .append("（记录ID ").append(entry.getId()).append("）\n");
            }
            sb.append("需要看某条的完整内容，请用关键词再查一次。");
            return sb.toString();
        }

        List<SettingEntry> hits = all.stream()
                .filter(e -> contains(e.getTitle(), keyword)
                        || contains(e.getContent(), keyword)
                        || contains(e.getTags(), keyword)
                        || contains(e.getTriggerKeywords(), keyword))
                .toList();

        if (hits.isEmpty()) {
            return "没有匹配「" + keyword + "」的设定。本书现有：" + titles(all);
        }

        StringBuilder sb = new StringBuilder();
        for (SettingEntry entry : hits) {
            sb.append("【").append(entry.getTitle()).append("】(记录ID ").append(entry.getId()).append(")\n");
            if (StringUtils.hasText(entry.getTags())) {
                sb.append("标签：").append(entry.getTags().trim()).append('\n');
            }
            if (StringUtils.hasText(entry.getContent())) {
                String content = entry.getContent().trim();
                sb.append(content.length() > MAX_CHARS
                        ? content.substring(0, MAX_CHARS) + "……" : content).append('\n');
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    private boolean contains(String source, String keyword) {
        return StringUtils.hasText(source) && source.contains(keyword);
    }

    private String titles(List<SettingEntry> entries) {
        return entries.stream().map(SettingEntry::getTitle).filter(StringUtils::hasText).toList().toString();
    }
}
