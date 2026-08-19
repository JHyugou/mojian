package com.mojian.version.vo;

import com.mojian.version.entity.ChapterVersion;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 版本列表项（不含正文大字段，详情走单独接口）。
 */
@Data
public class VersionVO {

    private Long id;

    private Long chapterId;

    private Integer versionNo;

    private String source;

    private Long sourceTaskId;

    private String remark;

    private LocalDateTime createdAt;

    public static VersionVO from(ChapterVersion version) {
        VersionVO vo = new VersionVO();
        vo.setId(version.getId());
        vo.setChapterId(version.getChapterId());
        vo.setVersionNo(version.getVersionNo());
        vo.setSource(version.getSource());
        vo.setSourceTaskId(version.getSourceTaskId());
        vo.setRemark(version.getRemark());
        vo.setCreatedAt(version.getCreatedAt());
        return vo;
    }
}
