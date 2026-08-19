package com.mojian.version.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 版本对比中的一行：same 表示相同，add 表示新增，del 表示删除。
 */
@Data
@AllArgsConstructor
public class VersionDiffLine {

    private String type;

    private String text;

    public static VersionDiffLine of(String type, String text) {
        return new VersionDiffLine(type, text);
    }
}
