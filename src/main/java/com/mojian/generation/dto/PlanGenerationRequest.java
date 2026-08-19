package com.mojian.generation.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 剧情方案生成请求（两阶段流程第一阶段，FR-010）。
 */
@Data
public class PlanGenerationRequest {

    /** 创作指令：剧情走向、写作要求、细节限制 */
    @Size(max = 2000, message = "创作指令不能超过2000字")
    private String instruction;
}
