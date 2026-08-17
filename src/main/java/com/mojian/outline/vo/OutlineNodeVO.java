package com.mojian.outline.vo;

import com.mojian.outline.entity.PlotOutline;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.List;

/**
 * 剧情蓝图树节点视图，children 按 parentId 装配。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class OutlineNodeVO extends PlotOutline {

    private List<OutlineNodeVO> children = new ArrayList<>();
}
