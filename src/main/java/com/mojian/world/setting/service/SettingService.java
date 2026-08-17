package com.mojian.world.setting.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.mojian.common.api.ErrorCode;
import com.mojian.common.exception.BusinessException;
import com.mojian.project.service.ProjectService;
import com.mojian.world.setting.dto.SettingSaveRequest;
import com.mojian.world.setting.entity.SettingEntry;
import com.mojian.world.setting.mapper.SettingEntryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SettingService extends ServiceImpl<SettingEntryMapper, SettingEntry> {

    private final ProjectService projectService;

    /**
     * 按项目列出设定条目，支持按名称/标签/触发关键词模糊搜索（FR-004）。
     */
    public List<SettingEntry> list(Long projectId, String keyword) {
        projectService.requireById(projectId);
        return lambdaQuery()
                .eq(SettingEntry::getProjectId, projectId)
                .and(StringUtils.hasText(keyword), w -> w
                        .like(SettingEntry::getTitle, keyword)
                        .or().like(SettingEntry::getTags, keyword)
                        .or().like(SettingEntry::getTriggerKeywords, keyword))
                .orderByDesc(SettingEntry::getPriority)
                .orderByAsc(SettingEntry::getCreatedAt)
                .list();
    }

    public SettingEntry create(Long projectId, SettingSaveRequest request) {
        projectService.requireById(projectId);
        SettingEntry entry = new SettingEntry();
        BeanUtils.copyProperties(request, entry);
        entry.setProjectId(projectId);
        entry.setPriority(request.getPriority() == null ? 0 : request.getPriority());
        entry.setEnabled(request.getEnabled() == null || request.getEnabled());
        save(entry);
        return entry;
    }

    public SettingEntry update(Long settingId, SettingSaveRequest request) {
        SettingEntry entry = requireById(settingId);
        BeanUtils.copyProperties(request, entry);
        if (request.getPriority() != null) {
            entry.setPriority(request.getPriority());
        }
        if (request.getEnabled() != null) {
            entry.setEnabled(request.getEnabled());
        }
        updateById(entry);
        return entry;
    }

    public void delete(Long settingId) {
        requireById(settingId);
        removeById(settingId);
    }

    public SettingEntry requireById(Long settingId) {
        SettingEntry entry = getById(settingId);
        if (entry == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "设定条目不存在");
        }
        return entry;
    }
}
