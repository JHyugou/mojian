package com.mojian.chapter.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.mojian.chapter.dto.ChapterMemorySaveRequest;
import com.mojian.chapter.dto.ChapterSaveRequest;
import com.mojian.chapter.entity.Chapter;
import com.mojian.chapter.entity.ChapterMemory;
import com.mojian.chapter.mapper.ChapterMapper;
import com.mojian.chapter.mapper.ChapterMemoryMapper;
import com.mojian.chapter.vo.BookVO;
import com.mojian.common.api.ErrorCode;
import com.mojian.common.exception.BusinessException;
import com.mojian.generation.mapper.GenerationTaskMapper;
import com.mojian.outline.entity.PlotOutline;
import com.mojian.outline.service.OutlineService;
import com.mojian.project.entity.StoryProject;
import com.mojian.project.service.ProjectService;
import com.mojian.version.entity.ChapterVersion;
import com.mojian.version.mapper.ChapterVersionMapper;
import com.mojian.version.service.VersionNumbers;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ChapterService extends ServiceImpl<ChapterMapper, Chapter> {

    private final ChapterMemoryMapper chapterMemoryMapper;
    private final GenerationTaskMapper generationTaskMapper;
    private final ChapterVersionMapper chapterVersionMapper;
    private final ProjectService projectService;
    private final OutlineService outlineService;

    /**
     * 章节列表：不加载正文大字段，仅返回概要信息。
     */
    public List<Chapter> list(Long projectId) {
        projectService.requireById(projectId);
        return lambdaQuery()
                .select(Chapter.class, f -> !"content".equals(f.getColumn()))
                .eq(Chapter::getProjectId, projectId)
                .orderByAsc(Chapter::getCreatedAt)
                .list();
    }

    public Chapter create(Long projectId, ChapterSaveRequest request) {
        projectService.requireById(projectId);
        if (request.getOutlineNodeId() != null) {
            requireOutlineNodeInProject(projectId, request.getOutlineNodeId());
        }
        Chapter chapter = new Chapter();
        BeanUtils.copyProperties(request, chapter);
        chapter.setProjectId(projectId);
        chapter.setCurrentVersionNo(0);
        chapter.setStatus(StringUtils.hasText(request.getStatus()) ? request.getStatus() : "DRAFT");
        chapter.setWritingRequirementStatus(StringUtils.hasText(chapter.getWritingRequirement())
                ? "PENDING" : "NONE");
        save(chapter);
        return chapter;
    }

    /**
     * 只调整章节状态（策划审稿通过 / 作者要求），不碰正文与其他字段。
     *
     * <p>不用全量 update 接口：那个接口是属性覆盖语义，为了改一个状态传整章风险太大。
     */
    @Transactional
    public Chapter updateStatus(Long chapterId, String status) {
        Chapter chapter = requireById(chapterId);
        if (!StringUtils.hasText(status)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "章节状态不能为空");
        }
        String normalized = status.trim().toUpperCase();
        if (!"DRAFT".equals(normalized) && !"WRITING".equals(normalized)
                && !"COMPLETED".equals(normalized)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "不支持的章节状态：" + status + "（只支持 DRAFT / WRITING / COMPLETED）");
        }
        chapter.setStatus(normalized);
        updateById(chapter);
        return chapter;
    }

    /**
     * 只改章节标题，不碰正文、状态和版本号。
     *
     * <p>标题是元数据，不产生新正文版本；真正需要版本的是正文内容。
     */
    @Transactional
    public Chapter rename(Long chapterId, String title) {
        Chapter chapter = requireById(chapterId);
        if (!StringUtils.hasText(title)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "章节标题不能为空");
        }
        String normalized = title.trim();
        if (normalized.length() > 200) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "章节标题不能超过 200 字");
        }
        chapter.setTitle(normalized);
        updateById(chapter);
        return chapter;
    }

    public Chapter get(Long chapterId) {
        return requireById(chapterId);
    }

    /**
     * 单独更新本章写作要求（风格、人称、篇幅、禁忌等）。
     *
     * <p>为什么不并进 {@code ChapterSaveRequest}：那个 DTO 走
     * {@code BeanUtils.copyProperties} 全量覆盖，新加一个可空字段会在前端
     * 提交其他字段时把写作要求清空 —— 那是个静默数据丢失的坑。
     * 单独一个端点最安全，语义也更清楚。
     */
    @Transactional
    public Chapter updateWritingRequirement(Long chapterId, String requirement) {
        Chapter chapter = requireById(chapterId);
        String normalized = StringUtils.hasText(requirement) ? requirement.trim() : null;
        chapter.setWritingRequirement(normalized);
        chapter.setWritingRequirementStatus(normalized == null ? "NONE" : "PENDING");
        chapter.setWritingRequirementExecutedAt(null);
        updateById(chapter);
        return chapter;
    }

    /** 写作任务按写作要求成功写完后调用；只改状态，不碰正文。 */
    @Transactional
    public Chapter markWritingRequirementExecuted(Long chapterId) {
        Chapter chapter = requireById(chapterId);
        if (!StringUtils.hasText(chapter.getWritingRequirement())) {
            return chapter;
        }
        chapter.setWritingRequirementStatus("EXECUTED");
        chapter.setWritingRequirementExecutedAt(LocalDateTime.now());
        updateById(chapter);
        return chapter;
    }

    /**
     * 成书（FR-020）：按创建顺序装配全书全文，供阅读视图与导出。
     * 与 list() 不同，这里需要正文大字段。
     * 草稿（status=DRAFT）章节不收录成书。
     */
    public BookVO book(Long projectId) {
        StoryProject project = projectService.requireById(projectId);
        List<Chapter> chapters = lambdaQuery()
                .eq(Chapter::getProjectId, projectId)
                .ne(Chapter::getStatus, "DRAFT")
                .orderByAsc(Chapter::getCreatedAt)
                .list();

        BookVO vo = new BookVO();
        vo.setProjectName(project.getName());
        vo.setGenre(project.getGenre());
        vo.setIntro(project.getIntro());
        List<BookVO.ChapterSection> sections = new ArrayList<>();
        int totalChars = 0;
        for (Chapter c : chapters) {
            BookVO.ChapterSection section = new BookVO.ChapterSection();
            section.setId(c.getId());
            section.setTitle(c.getTitle());
            section.setContent(c.getContent());
            section.setChars(c.getContent() == null ? 0 : c.getContent().length());
            totalChars += section.getChars();
            sections.add(section);
        }
        vo.setChapters(sections);
        vo.setChapterCount(sections.size());
        vo.setTotalChars(totalChars);
        return vo;
    }

    /**
     * 手动保存章节（FR-007）。createVersion=true 时为新内容创建 MANUAL 版本快照（FR-016），
     * 更新与版本写入在同一事务内，失败整体回滚。
     */
    @Transactional(rollbackFor = Exception.class)
    public Chapter update(Long chapterId, ChapterSaveRequest request) {
        return update(chapterId, request, "MANUAL");
    }

    /**
     * 保存章节并指定版本来源。
     *
     * <p>{@code source} 是版本列表里"这版是谁写的"的唯一依据：旧实现把 AI 写的正文
     * 也记成 MANUAL，作者在版本历史里根本分不清哪些是模型生成的。
     *
     * <p>另外两个防丢字段的处理：{@code status} 与 {@code content} 在请求里缺省时
     * 保持原值。这个接口是"全量属性覆盖"语义，直接 BeanUtils 拷贝 null 会把
     * COMPLETED 降成 DRAFT、把正文清空 —— 都是真实的丢数据路径。
     */
    @Transactional(rollbackFor = Exception.class)
    public Chapter update(Long chapterId, ChapterSaveRequest request, String versionSource) {
        Chapter chapter = requireById(chapterId);
        if (request.getOutlineNodeId() != null) {
            requireOutlineNodeInProject(chapter.getProjectId(), request.getOutlineNodeId());
        }
        String previousStatus = chapter.getStatus();
        String previousContent = chapter.getContent();
        BeanUtils.copyProperties(request, chapter);
        if (request.getStatus() == null && StringUtils.hasText(previousStatus)) {
            chapter.setStatus(previousStatus);
        }
        if (request.getContent() == null && previousContent != null) {
            chapter.setContent(previousContent);
        }
        if (!StringUtils.hasText(chapter.getStatus())) {
            chapter.setStatus("DRAFT");
        }
        updateById(chapter);

        if (Boolean.TRUE.equals(request.getCreateVersion())) {
            int versionNo = VersionNumbers.next(chapterVersionMapper, chapterId, chapter.getCurrentVersionNo());
            ChapterVersion version = new ChapterVersion();
            version.setChapterId(chapterId);
            version.setVersionNo(versionNo);
            version.setContent(chapter.getContent());
            version.setSource(StringUtils.hasText(versionSource) ? versionSource : "MANUAL");
            version.setRemark(request.getRemark());
            chapterVersionMapper.insert(version);
            chapter.setCurrentVersionNo(versionNo);
            updateById(chapter);
        }
        return chapter;
    }

    /**
     * 删除章节及其记忆、版本和任务记录，事务保证不残留孤儿数据。
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long chapterId) {
        requireById(chapterId);
        chapterMemoryMapper.deleteByChapterId(chapterId);
        chapterVersionMapper.deleteByChapterId(chapterId);
        generationTaskMapper.deleteByChapterId(chapterId);
        removeById(chapterId);
    }

    public ChapterMemory getMemory(Long chapterId) {
        requireById(chapterId);
        ChapterMemory memory = chapterMemoryMapper.selectByChapterId(chapterId);
        if (memory == null) {
            memory = new ChapterMemory();
            memory.setChapterId(chapterId);
        }
        return memory;
    }

    public ChapterMemory saveMemory(Long chapterId, ChapterMemorySaveRequest request) {
        requireById(chapterId);
        ChapterMemory memory = chapterMemoryMapper.selectByChapterId(chapterId);
        if (memory == null) {
            memory = new ChapterMemory();
            memory.setChapterId(chapterId);
            BeanUtils.copyProperties(request, memory);
            chapterMemoryMapper.insert(memory);
        } else {
            BeanUtils.copyProperties(request, memory);
            chapterMemoryMapper.updateById(memory);
        }
        return memory;
    }

    public Chapter requireById(Long chapterId) {
        Chapter chapter = getById(chapterId);
        if (chapter == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "章节不存在");
        }
        return chapter;
    }

    private void requireOutlineNodeInProject(Long projectId, Long outlineNodeId) {
        PlotOutline node = outlineService.requireById(outlineNodeId);
        if (!node.getProjectId().equals(projectId)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "蓝图节点不属于当前项目");
        }
    }
}
