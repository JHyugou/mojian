package com.mojian.version.vo;

import lombok.Data;

import java.util.List;

/**
 * 版本对比结果：返回两个版本的基本信息和按行 diff 结果。
 */
@Data
public class VersionCompareVO {

    private Long baseVersionId;

    private Integer baseVersionNo;

    private Long targetVersionId;

    private Integer targetVersionNo;

    private List<VersionDiffLine> lines;
}
