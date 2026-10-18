package com.mojian.settings.vo;

import com.mojian.settings.llm.ModelsDevCatalog;

import java.util.List;

/**
 * 模型卡片：目录里的一条信息 + "我有没有启用它"。
 *
 * <p>为什么要把"启用"和目录信息分开：目录是<b>公共事实</b>（世上有什么模型、
 * 上下文多长、多少钱），启用是<b>个人选择</b>（我打算用哪几个）。
 * 两者的生命周期完全不同 —— 目录天天在变，而选择是用户定的。
 * 混成一个类型的话，缓存目录时就得连选择一起缓存，迟早出错。
 *
 * @param enabled 是否已启用。默认全部启用 —— 让人先看见全部、再一个个关掉，
 *                比一开始一片空白要好得多
 */
public record LlmModelVO(String id, String name,
                         Integer contextLimit, Double inputCost, Double outputCost,
                         List<String> reasoningLevels, boolean enabled) {

    public static LlmModelVO of(ModelsDevCatalog.ModelInfo info, boolean enabled) {
        return new LlmModelVO(info.id(), info.name(), info.contextLimit(),
                info.inputCost(), info.outputCost(),
                info.reasoningLevels() == null ? List.of() : info.reasoningLevels(),
                enabled);
    }
}
