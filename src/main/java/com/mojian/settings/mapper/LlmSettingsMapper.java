package com.mojian.settings.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mojian.settings.entity.LlmSettings;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface LlmSettingsMapper extends BaseMapper<LlmSettings> {

    /** 全局唯一配置行，id 固定为 1 */
    default LlmSettings selectSingleton() {
        return selectById(1L);
    }
}
