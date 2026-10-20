package com.mojian.outline.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.mojian.common.api.ErrorCode;
import com.mojian.common.exception.BusinessException;
import com.mojian.outline.dto.OutlineNodeSaveRequest;
import com.mojian.outline.entity.PlotOutline;
import com.mojian.outline.mapper.PlotOutlineMapper;
import com.mojian.outline.vo.OutlineNodeVO;
import com.mojian.project.service.ProjectService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class OutlineService extends ServiceImpl<PlotOutlineMapper, PlotOutline> {

    private final ProjectService projectService;
    private final com.mojian.chapter.mapper.ChapterMapper chapterMapper;
    private final JdbcTemplate jdbcTemplate;

    /**
     * 老库结构演进（幂等）：面向新手作者的精简——章节节点只保留"摘要"，
     * 剧情目标/冲突/场景/必须情节 的已填内容以标签段落并入摘要后删列。
     * 主要人物（main_characters）是功能字段（驱动蓝图引用人物优先注入），保留。
     */
    @PostConstruct
    public void migrateOutlineSchema() {
        if (!columnExists("plot_outline", "plot_goal")) {
            return;
        }
        jdbcTemplate.update("UPDATE plot_outline SET summary = CONCAT_WS(CHAR(10), "
                + "NULLIF(summary, ''), "
                + "NULLIF(CONCAT('【剧情目标】', plot_goal), '【剧情目标】'), "
                + "NULLIF(CONCAT('【冲突】', conflict), '【冲突】'), "
                + "NULLIF(CONCAT('【场景】', scene), '【场景】'), "
                + "NULLIF(CONCAT('【必须情节】', must_events), '【必须情节】')) "
                + "WHERE plot_goal IS NOT NULL AND plot_goal != '' "
                + "OR conflict IS NOT NULL AND conflict != '' "
                + "OR scene IS NOT NULL AND scene != '' "
                + "OR must_events IS NOT NULL AND must_events != ''");
        jdbcTemplate.execute("ALTER TABLE plot_outline "
                + "DROP COLUMN plot_goal, DROP COLUMN conflict, "
                + "DROP COLUMN scene, DROP COLUMN must_events");
        log.info("plot_outline 剧情目标/冲突/场景/必须情节 已并入摘要并删列");
    }

    private boolean columnExists(String table, String column) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                Integer.class, table, column);
        return count != null && count > 0;
    }

    /**
     * 返回项目的完整蓝图树：一次查询全部节点后内存装配（FR-006）。
     */
    public List<OutlineNodeVO> tree(Long projectId) {
        projectService.requireById(projectId);
        List<PlotOutline> nodes = lambdaQuery()
                .eq(PlotOutline::getProjectId, projectId)
                // 蓝图面板按数据库创建顺序展示；sort_order 是旧字段，可能残留手填/导入值，
                // 继续优先使用会把节点排成与创建时间无关的顺序。
                .orderByAsc(PlotOutline::getCreatedAt)
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

    /** 只改蓝图节点名称，保留类型、摘要、父子关系和排序。 */
    @Transactional
    public PlotOutline rename(Long nodeId, String name) {
        PlotOutline node = requireById(nodeId);
        if (!StringUtils.hasText(name)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "蓝图节点名称不能为空");
        }
        String normalized = name.trim();
        if (normalized.length() > 200) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "蓝图节点名称不能超过 200 字");
        }
        node.setName(normalized);
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
