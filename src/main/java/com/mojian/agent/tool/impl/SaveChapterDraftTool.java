package com.mojian.agent.tool.impl;

import com.mojian.agent.AgentContext;
import com.mojian.agent.tool.AgentTool;
import com.mojian.chapter.dto.ChapterSaveRequest;
import com.mojian.chapter.entity.Chapter;
import com.mojian.chapter.service.ChapterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

/**
 * 把写好的正文存进章节（写作 Agent 的唯一出口）。
 *
 * <h3>为什么这个工具不写草稿表，而是直接写章节</h3>
 * 和策划的三个写工具不同 —— 那些会<b>改变书的结构</b>（新增人物、设定、蓝图节点），
 * 加错了会污染整本书的设定体系，所以要挡在待确认后面。
 *
 * <p>而正文本身就是"拿来给人看和改的东西"：它躺在章节里、状态是 DRAFT，
 * 作者看、改、满意后自己标记完成。而且墨笺的章节版本机制已经提供了回滚能力，
 * 等价于安全网。再套一层草稿反而是绕。
 *
 * <h3>两个安全措施</h3>
 * <ol>
 *   <li><b>强制创建版本快照</b>：正文不是"改了就算了"，旧内容随时可以回滚</li>
 *   <li><b>显式回填其他字段</b>：{@code ChapterService.update} 走的是全量属性覆盖，
 *       如果只提交 content，标题、蓝图关联、状态都会被置空 —— 这是真实存在的数据丢失路径</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SaveChapterDraftTool implements AgentTool {

    private final ChapterService chapterService;

    @Override
    public String name() {
        return "save_chapter_draft";
    }

    @Override
    public String description() {
        return "把这一章的正文保存到作品里。写完正文后调用它。"
                + "保存会自动创建版本快照，作者随时可以回滚到旧版本，所以不必担心覆盖。"
                + "一次写完就调一次；如果一章太长要分几次写，可以多次调用，后一次会覆盖前一次"
                + "（但历史版本仍可回溯）。";
    }

    @Override
    public List<String> roles() {
        return List.of("WRITER");
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "content", Map.of("type", "string",
                                "description", "本章正文全文。只写正文，不要带标题、"
                                        + "不要带'以下是正文'这类说明、不要分小标题"),
                        "note", Map.of("type", "string",
                                "description", "版本备注：这次写了什么、哪里做了自由发挥，"
                                        + "便于作者在版本历史里辨认（不超过 200 字）")),
                "required", List.of("content"));
    }

    /**
     * 正文是"大段内容"：可能五千字、模型要写一两分钟。
     * 声明之后，客户端能在它一片片流出时实时推给界面，做到"边写边看"。
     */
    @Override
    public String streamingContentField() {
        return "content";
    }

    @Override
    public String execute(AgentContext context, Map<String, Object> arguments) {
        Long chapterId = context.requireChapterId();
        String content = arguments.get("content") == null ? "" : String.valueOf(arguments.get("content")).trim();
        if (content.isEmpty()) {
            throw new IllegalArgumentException("content 不能为空");
        }

        Chapter existing = chapterService.requireById(chapterId);
        boolean overwrite = StringUtils.hasText(existing.getContent());
        // 首次写入的章节从 DRAFT 推进到 WRITING：成书排除 DRAFT，
        // 不推进的话「AI 写完了一章，成书里却没有」—— 界面和阅读页会互相打架。
        String status = StringUtils.hasText(existing.getStatus()) ? existing.getStatus() : "DRAFT";
        if ("DRAFT".equals(status)) {
            status = "WRITING";
        }

        ChapterSaveRequest request = new ChapterSaveRequest();
        // 全量回填：update() 是属性全量覆盖，漏掉哪个字段哪个字段就被清空
        request.setTitle(existing.getTitle());
        request.setOutlineNodeId(existing.getOutlineNodeId());
        request.setStatus(status);
        request.setContent(content);
        request.setCreateVersion(true);
        request.setRemark(trimNote(arguments.get("note")));

        // 版本来源必须是 AI，而不是默认的 MANUAL —— 作者在版本历史里要能分辨谁写的。
        Chapter saved = chapterService.update(chapterId, request, "AI");
        log.info("写作 Agent 保存第 {} 章正文，{} 字（覆盖已有内容={}）",
                saved.getId(), content.length(), overwrite);

        String base = "已保存正文，共 " + content.length() + " 字，"
                + "版本号 " + saved.getCurrentVersionNo() + "（旧版本可回滚）。";
        return overwrite
                ? base + "\n注意：这一章原本已有内容，已被本次覆盖 —— 请在回复里明确提醒作者，"
                        + "并说明旧内容可通过版本历史找回。"
                : base + "\n请用一两句话向作者说明本章写了什么、哪里做了自由发挥。不要复述正文。";
    }

    /** 版本备注有 200 字上限，超了会被校验拦下 —— 这里主动截断而不是让它报错 */
    private String trimNote(Object note) {
        if (note == null) {
            return "AI 写作";
        }
        String text = String.valueOf(note).trim();
        if (text.isEmpty()) {
            return "AI 写作";
        }
        return text.length() <= 200 ? text : text.substring(0, 197) + "…";
    }
}
