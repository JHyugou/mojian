package com.mojian.settings.dto;

import com.mojian.settings.vo.LlmModelVO;
import lombok.Data;

import java.util.List;

/** 新增/修改一个模型服务商。apiKey=null 表示沿用已存；空字符串表示清除。models 非 null 时替换模型目录。 */
@Data
public class LlmProviderSaveRequest {

    private Long id;

    private String name;

    private String presetKey;

    private String baseUrl;

    private String apiKey;

    private List<LlmModelVO> models;
}
