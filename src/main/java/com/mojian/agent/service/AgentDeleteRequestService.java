package com.mojian.agent.service;

import com.mojian.agent.entity.AgentDeleteRequest;
import com.mojian.agent.mapper.AgentDeleteRequestMapper;
import com.mojian.common.api.ErrorCode;
import com.mojian.common.exception.BusinessException;
import com.mojian.outline.entity.PlotOutline;
import com.mojian.outline.service.OutlineService;
import com.mojian.world.character.entity.CharacterProfile;
import com.mojian.world.character.service.CharacterService;
import com.mojian.world.setting.entity.SettingEntry;
import com.mojian.world.setting.service.SettingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 策划实体删除申请：工具只登记，作者确认后才真正删除。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentDeleteRequestService {

    private final AgentDeleteRequestMapper requestMapper;
    private final CharacterService characterService;
    private final SettingService settingService;
    private final OutlineService outlineService;

    @Transactional
    public AgentDeleteRequest request(Long projectId, Long conversationId, String entityType,
                                     Long entityId, String entityName, String reason) {
        // 同一个实体已有待确认删除申请时不重复建
        AgentDeleteRequest existing = requestMapper.selectPendingByEntity(projectId, entityType, entityId);
        if (existing != null) {
            return existing;
        }
        AgentDeleteRequest request = new AgentDeleteRequest();
        request.setProjectId(projectId);
        request.setConversationId(conversationId);
        request.setEntityType(entityType);
        request.setEntityId(entityId);
        request.setEntityName(entityName);
        request.setReason(StringUtils.hasText(reason) ? reason.trim() : null);
        request.setStatus("PENDING");
        request.setSource("PLANNER");
        requestMapper.insert(request);
        log.info("策划申请删除 {} {}「{}」：{}", entityType, entityId, entityName, reason);
        return request;
    }

    public List<AgentDeleteRequest> pending(Long projectId) {
        return requestMapper.selectPending(projectId);
    }

    public AgentDeleteRequest require(Long requestId) {
        AgentDeleteRequest request = requestMapper.selectById(requestId);
        if (request == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "删除申请不存在：" + requestId);
        }
        return request;
    }

    @Transactional
    public AgentDeleteRequest approve(Long requestId) {
        AgentDeleteRequest request = require(requestId);
        if (!"PENDING".equals(request.getStatus())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "该删除申请已经处理过了（当前状态：" + request.getStatus() + "）");
        }
        validateOwnership(request);
        switch (request.getEntityType()) {
            case "CHARACTER" -> characterService.delete(request.getEntityId());
            case "SETTING" -> settingService.delete(request.getEntityId());
            case "OUTLINE" -> outlineService.delete(request.getEntityId());
            default -> throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "未知的删除类型：" + request.getEntityType());
        }
        request.setStatus("APPROVED");
        requestMapper.updateById(request);
        log.info("作者确认删除 {} {}「{}」", request.getEntityType(),
                request.getEntityId(), request.getEntityName());
        return request;
    }

    @Transactional
    public AgentDeleteRequest reject(Long requestId) {
        AgentDeleteRequest request = require(requestId);
        if (!"PENDING".equals(request.getStatus())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "该删除申请已经处理过了（当前状态：" + request.getStatus() + "）");
        }
        request.setStatus("REJECTED");
        requestMapper.updateById(request);
        log.info("作者拒绝删除 {} {}「{}」", request.getEntityType(),
                request.getEntityId(), request.getEntityName());
        return request;
    }

    private void validateOwnership(AgentDeleteRequest request) {
        Long projectId = request.getProjectId();
        switch (request.getEntityType()) {
            case "CHARACTER" -> {
                CharacterProfile profile = characterService.requireById(request.getEntityId());
                if (!projectId.equals(profile.getProjectId())) {
                    throw new BusinessException(ErrorCode.PARAM_INVALID, "该人物不属于当前项目");
                }
            }
            case "SETTING" -> {
                SettingEntry entry = settingService.requireById(request.getEntityId());
                if (!projectId.equals(entry.getProjectId())) {
                    throw new BusinessException(ErrorCode.PARAM_INVALID, "该设定不属于当前项目");
                }
            }
            case "OUTLINE" -> {
                PlotOutline node = outlineService.requireById(request.getEntityId());
                if (!projectId.equals(node.getProjectId())) {
                    throw new BusinessException(ErrorCode.PARAM_INVALID, "该蓝图节点不属于当前项目");
                }
            }
            default -> throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "未知的删除类型：" + request.getEntityType());
        }
    }
}
