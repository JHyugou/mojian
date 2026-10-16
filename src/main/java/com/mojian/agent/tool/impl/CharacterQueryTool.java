package com.mojian.agent.tool.impl;

import com.mojian.agent.AgentContext;
import com.mojian.agent.tool.AgentTool;
import com.mojian.world.character.entity.CharacterProfile;
import com.mojian.world.character.service.CharacterService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

/**
 * 查人物卡 —— 墨笺的第一个 Agent 工具（只读，零风险）。
 *
 * <p>它做的事和"把人物设定塞进提示词"是一样的，区别在于：
 * <b>现在是模型自己决定要不要查、查谁</b>，而不是我们在代码里写死。
 * 这就是 Agent 和普通工作流的分界线。
 *
 * <p>注意 {@link #description()} 的写法：模型看不到这个类的代码，
 * 它只有这段文字可看。写得含糊，它就会在"该查的时候不查、不该查的时候乱查"。
 */
@Component
@RequiredArgsConstructor
public class CharacterQueryTool implements AgentTool {

    /** 单个人物设定最多喂给模型多少字。人物档案可能是几千字的大段描述，
     *  不加限制地全塞进去会迅速耗尽上下文预算（而且贵）。 */
    private static final int MAX_DESCRIPTION_CHARS = 1200;

    private final CharacterService characterService;

    @Override
    public String name() {
        // 必须是 ASCII 标识符：OpenAI tools 规范要求函数名匹配 ^[a-zA-Z0-9_-]+$
        // 中文会直接被服务方以 400 拒绝。中文说明写进 description。
        return "query_character";
    }

    @Override
    public String description() {
        // 描述必须说清"资料里已经有了，别白查一遍"。
        // 实测教训：早先的描述写成"当需要确认某个人物是谁时使用本工具"，
        // 结果模型在一轮里连查两次（先列出全部、再查某个具体人物），
        // 而这两样东西当时都已经在【本书资料】里 —— 白白浪费了两步。
        return "查询本书中人物的设定资料（身份、外貌、性格、能力、人际关系等）。"
                + "注意：本书的人物档案通常已经完整包含在【本书资料】中，"
                + "若资料里已有，直接用即可，不必调用本工具。"
                + "仅在资料未包含、或需要按部分姓名查找时使用。"
                + "参数 name 是人物姓名，支持只给一部分（例如只写「林」）。";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "name", Map.of(
                                "type", "string",
                                "description", "人物姓名，支持部分匹配；留空表示列出本书全部人物")),
                "required", List.of("name"));
    }

    @Override
    public String execute(AgentContext context, Map<String, Object> arguments) {
        Long projectId = context.requireProjectId();
        String keyword = arguments.get("name") == null ? "" : String.valueOf(arguments.get("name")).trim();

        List<CharacterProfile> all = characterService.list(projectId);
        if (all.isEmpty()) {
            return "本书目前还没有建立任何人物档案。";
        }

        if (keyword.isEmpty()) {
            return "本书共 " + all.size() + " 个人物：" + names(all)
                    + "。需要了解谁，请再用姓名查一次。";
        }
        List<CharacterProfile> hits = all.stream()
                .filter(c -> StringUtils.hasText(c.getName()) && c.getName().contains(keyword))
                .toList();

        if (hits.isEmpty()) {
            // 查不到时把"有哪些人"一起告诉模型，它就有机会自己纠正（换个名字再查）
            return "没有找到名字包含「" + keyword + "」的人物。本书现有角色：" + names(all);
        }
        return render(hits);
    }

    private String names(List<CharacterProfile> profiles) {
        return profiles.stream()
                .map(CharacterProfile::getName)
                .filter(StringUtils::hasText)
                .toList()
                .toString();
    }

    private String render(List<CharacterProfile> profiles) {
        StringBuilder sb = new StringBuilder();
        for (CharacterProfile profile : profiles) {
            // 带上记录 ID：要修改某个人物时，update_character 必须拿它定位。
            // 材料里的人物卡片是没有 ID 的，所以只能在这里给。
            sb.append("【").append(profile.getName()).append("】(记录ID ").append(profile.getId()).append(")");
            if (StringUtils.hasText(profile.getRoleIdentity())) {
                sb.append("（").append(profile.getRoleIdentity().trim()).append("）");
            }
            sb.append('\n');
            String description = profile.getDescription();
            if (StringUtils.hasText(description)) {
                String trimmed = description.trim();
                if (trimmed.length() > MAX_DESCRIPTION_CHARS) {
                    sb.append(trimmed, 0, MAX_DESCRIPTION_CHARS)
                            .append("\n…（设定过长已截断，如需更多细节请针对具体方面追问）\n");
                } else {
                    sb.append(trimmed).append('\n');
                }
            } else {
                sb.append("（暂无详细设定）\n");
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }
}
