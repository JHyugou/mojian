package com.mojian.generation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 生成任务创建请求。剧情方案走专用接口 /generation-plans，
 * 此处允许的类型为正文/续写/改写/扩缩写/章节记忆（FR-009、FR-008）。
 */
@Data
public class GenerationTaskRequest {

    @NotBlank(message = "任务类型不能为空")
    @Pattern(regexp = "CONTENT|CONTINUE|REWRITE|EXPAND_SHRINK|CHAPTER_MEMORY",
            message = "任务类型不合法")
    private String taskType;

    @Size(max = 2000, message = "创作指令不能超过2000字")
    private String instruction;

    /** 两阶段流程：用户确认（可修改）后的剧情方案 */
    @Size(max = 8000, message = "剧情方案不能超过8000字")
    private String plan;

    /** 改写/扩缩写的选中文本 */
    @Size(max = 8000, message = "选中文本不能超过8000字")
    private String selection;
}
