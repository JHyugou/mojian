package com.mojian.world.setting.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mojian.world.setting.entity.SettingEntry;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface SettingEntryMapper extends BaseMapper<SettingEntry> {

    default List<SettingEntry> selectByProjectId(Long projectId) {
        return selectList(new LambdaQueryWrapper<SettingEntry>()
                .eq(SettingEntry::getProjectId, projectId));
    }

    default int deleteByProjectId(Long projectId) {
        return delete(new LambdaQueryWrapper<SettingEntry>()
                .eq(SettingEntry::getProjectId, projectId));
    }
}
