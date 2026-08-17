package com.mojian.outline.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.mojian.common.api.ErrorCode;
import com.mojian.common.exception.BusinessException;
import com.mojian.outline.dto.OutlineNodeSaveRequest;
import com.mojian.outline.entity.PlotOutline;
import com.mojian.outline.mapper.PlotOutlineMapper;
import com.mojian.outline.vo.OutlineNodeVO;
import com.mojian.project.service.ProjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.*;

@Service
@RequiredArgsConstructor
public class OutlineService extends ServiceImpl<PlotOutlineMapper, PlotOutline> {

    private final ProjectService projectService;
    private final com.mojian.chapter.mapper.ChapterMapper chapterMapper;

    /**
     * 返回项目的完整蓝图树：一次查询全部节点后内存装配（FR-006）。
     */
    public List<OutlineNodeVO> tree(Long projectId) {
        projectService.requireById(projectId);
        List<PlotOutline> nodes = lambdaQuery()
                .eq(PlotOutline::getProjectId, projectId)
                .orderByAsc(PlotOutline::getSortOrder)
                .orderByAsc(PlotOutline::getId)
                .list();

        Map<Long, OutlineNodeVO> voMap = new LinkedHashMap<>();
        for (PlotOutline node : nodes) {
            OutlineNodeVO vo = new OutlineNodeVO();
            BeanUtils.copyProperties(node, vo);
            voMap.put(node.getId(), vo);
        }
        List<OutlineNodeVO> roots = new ArrayList<>();
        for (OutlineNodeVO vo : voMap.values()) {
            if (vo.getParentId() == null) {
                roots.add(vo);
            } else {
                OutlineNodeVO parent = voMap.get(vo.getParentId());
                if (parent != null) {
                    parent.getChildren().add(vo);
                } else {
                    // 父节点缺失（异常数据）时提升为根，保证节点不丢失
                    roots.add(vo);
                }
            }
        }
        return roots;
    }

    public PlotOutline create(Long projectId, OutlineNodeSaveRequest request) {
        projectService.requireById(projectId);
        validateParent(projectId, request.getParentId());
        PlotOutline node = new PlotOutline();
        BeanUtils.copyProperties(request, node);
        node.setProjectId(projectId);
        node.setSortOrder(request.getSortOrder() == null ? 0 : request.getSortOrder());
        node.setStatus(!StringUtils.hasText(request.getStatus()) ? "PLANNED" : request.getStatus());
        save(node);
        return node;
    }

    public PlotOutline update(Long nodeId, OutlineNodeSaveRequest request) {
        PlotOutline node = requireById(nodeId);
        Long projectId = node.getProjectId();
        // 不允许把节点移动到其他项目或自己的子孙节点下
        if (request.getParentId() != null) {
            validateParent(projectId, request.getParentId());
            if (isSelfOrDescendant(nodeId, request.getParentId())) {
                throw new BusinessException(ErrorCode.PARAM_INVALID, "父节点不能是自身或自己的子孙节点");
            }
        }
        BeanUtils.copyProperties(request, node);
        node.setProjectId(projectId);
        updateById(node);
        return node;
    }

    /**
     * 删除节点及其全部子孙节点；关联章节的 outline_node_id 置空，不删除章节正文。
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long nodeId) {
        requireById(nodeId);
        PlotOutline node = getById(nodeId);
        List<Long> subtreeIds = collectSubtreeIds(node.getProjectId(), nodeId);
        chapterMapper.clearOutlineNode(subtreeIds);
        lambdaUpdate().in(PlotOutline::getId, subtreeIds).remove();
    }

    public PlotOutline requireById(Long nodeId) {
        PlotOutline node = getById(nodeId);
        if (node == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "剧情节点不存在");
        }
        return node;
    }

    private void validateParent(Long projectId, Long parentId) {
        if (parentId == null) {
            return;
        }
        PlotOutline parent = requireById(parentId);
        if (!parent.getProjectId().equals(projectId)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "父节点不属于当前项目");
        }
    }

    private boolean isSelfOrDescendant(Long nodeId, Long candidateParentId) {
        Deque<Long> stack = new ArrayDeque<>();
        stack.push(nodeId);
        while (!stack.isEmpty()) {
            Long current = stack.pop();
            if (current.equals(candidateParentId)) {
                return true;
            }
            lambdaQuery()
                    .eq(PlotOutline::getParentId, current)
                    .list()
                    .forEach(child -> stack.push(child.getId()));
        }
        return false;
    }

    private List<Long> collectSubtreeIds(Long projectId, Long rootId) {
        List<PlotOutline> all = lambdaQuery()
                .eq(PlotOutline::getProjectId, projectId)
                .list();
        Map<Long, List<Long>> childrenMap = new HashMap<>();
        for (PlotOutline node : all) {
            if (node.getParentId() != null) {
                childrenMap.computeIfAbsent(node.getParentId(), k -> new ArrayList<>())
                        .add(node.getId());
            }
        }
        List<Long> result = new ArrayList<>();
        Deque<Long> stack = new ArrayDeque<>();
        stack.push(rootId);
        while (!stack.isEmpty()) {
            Long current = stack.pop();
            result.add(current);
            List<Long> children = childrenMap.get(current);
            if (children != null) {
                children.forEach(stack::push);
            }
        }
        return result;
    }
}
