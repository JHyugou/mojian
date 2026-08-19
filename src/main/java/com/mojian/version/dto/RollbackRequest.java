package com.mojian.version.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class RollbackRequest {

    @NotNull(message = "目标版本不能为空")
    private Long versionId;
}
