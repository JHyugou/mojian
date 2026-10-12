package com.mojian.share;

import com.mojian.chapter.dto.ChapterMemorySaveRequest;
import com.mojian.chapter.dto.ChapterSaveRequest;
import com.mojian.chapter.entity.Chapter;
import com.mojian.chapter.entity.ChapterMemory;
import com.mojian.chapter.mapper.ChapterMapper;
import com.mojian.chapter.mapper.ChapterMemoryMapper;
import com.mojian.chapter.service.ChapterService;
import com.mojian.common.api.ErrorCode;
import com.mojian.common.exception.BusinessException;
import com.mojian.outline.dto.OutlineNodeSaveRequest;
import com.mojian.outline.vo.OutlineNodeVO;
import com.mojian.outline.service.OutlineService;
import com.mojian.project.dto.ProjectSaveRequest;
import com.mojian.project.entity.StoryProject;
import com.mojian.project.service.ProjectService;
import com.mojian.world.character.dto.CharacterNodeSaveRequest;
import com.mojian.world.character.dto.CharacterSaveRequest;
import com.mojian.world.character.entity.CharacterNode;
import com.mojian.world.character.entity.CharacterProfile;
import com.mojian.world.character.mapper.CharacterNodeMapper;
import com.mojian.world.character.service.CharacterService;
import com.mojian.world.setting.dto.SettingSaveRequest;
import com.mojian.world.setting.entity.SettingEntry;
import com.mojian.world.setting.mapper.SettingEntryMapper;
import com.mojian.world.setting.service.SettingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 分享中心（FR-022）：项目级与素材级的 JSON 导入导出。
 *
 * 三个粒度：
 * 1. 项目级（mojian-project/v1）：整本小说全量导出——人物（含演变节点）、世界观设定、
 *    蓝图树、全部章节正文（含草稿）与记忆；导入创建全新项目，整体事务全或无。
 *    版本历史不包含：它是再生产物而非活内容，避免导出文件体积失控。
 * 2. 素材级（mojian-characters/v1 / mojian-settings/v1）：勾选一个或多个人物/设定导出
 *    （人物卡含演变节点时间线），导入追加进当前项目。
 * 3. 蓝图不单独导入导出：脱离小说整体的脉络没有可用性，只随项目导出携带。
 *
 * 导入一律"追加不覆盖"，整体事务：任何一条非法即整批拒绝，不动现有数据。
 * 蓝图中的 mainCharacters 人物 ID 原样保留，跨项目导入时引用安全降级为无引用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShareService {

    public static final String FMT_PROJECT = "mojian-project/v1";
    public static final String FMT_CHARACTERS = "mojian-characters/v1";
    public static final String FMT_SETTINGS = "mojian-settings/v1";

    private final ProjectService projectService;
    private final CharacterService characterService;
    private final CharacterNodeMapper characterNodeMapper;
    private final SettingService settingService;
    private final SettingEntryMapper settingEntryMapper;
    private final OutlineService outlineService;
    private final ChapterService chapterService;
    private final ChapterMapper chapterMapper;
    private final ChapterMemoryMapper chapterMemoryMapper;

    // ==================== 项目级导出 ====================

    /** 整本小说全量导出：人物（含演变节点）+ 世界观设定 + 蓝图树 + 全部章节与记忆 */
    public Map<String, Object> exportProject(Long projectId) {
        StoryProject project = projectService.requireById(projectId);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("format", FMT_PROJECT);
        payload.put("version", 1);
        payload.put("exportedAt", LocalDateTime.now().toString());
        payload.put("sections", List.of("characters", "settings", "outline", "chapters"));
        payload.put("project", new LinkedHashMap<>(Map.of(
                "name", project.getName(),
                "genre", Objects.toString(project.getGenre(), ""),
                "intro", Objects.toString(project.getIntro(), ""))));
        payload.put("characters", exportCharacters(projectId, null));
        payload.put("settings", exportSettings(projectId, null));
        payload.put("outline", exportOutline(projectId));
        payload.put("chapters", exportChapters(projectId));
        return payload;
    }

    /** 导入整本项目：创建全新项目承载，蓝图关联按新旧 ID 映射回填，章节记忆随章节回填 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> importProject(Map<String, Object> payload) {
        requireFormat(payload, FMT_PROJECT, "项目");
        Map<String, Object> project = requireMap(payload, "project");
        String name = requireText(project, "name", "项目名称");
        List<Map<String, Object>> characterItems = dataList(payload, "characters");
        List<Map<String, Object>> settingItems = dataList(payload, "settings");
        List<Map<String, Object>> outlineRoots = dataList(payload, "outline");
        List<Map<String, Object>> chapterItems = dataList(payload, "chapters");

        ProjectSaveRequest projectReq = new ProjectSaveRequest();
        projectReq.setName(name);
        projectReq.setGenre(text(project, "genre"));
        projectReq.setIntro(text(project, "intro"));
        Long projectId = projectService.create(projectReq).getId();

        Map<String, Long> refMap = importOutlineData(projectId, outlineRoots);
        int characters = importCharactersData(projectId, characterItems);
        int settings = importSettingsData(projectId, settingItems);
        int chapters = importChaptersData(projectId, chapterItems, refMap);

        log.info("项目导入完成：新项目 {}（{}），人物 {} / 设定 {} / 蓝图节点 {} / 章节 {}",
                projectId, name, characters, settings, refMap.size(), chapters);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("projectId", String.valueOf(projectId));
        result.put("projectName", name);
        result.put("counts", Map.of(
                "characters", characters,
                "settings", settings,
                "outline", refMap.size(),
                "chapters", chapters));
        return result;
    }

    // ==================== 素材级：人物 ====================

    /** ids 为空表示全部导出；结果按创建顺序（与列表页一致） */
    public List<Map<String, Object>> exportCharacters(Long projectId, List<Long> ids) {
        if (ids != null && ids.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "请至少选择一个要导出的人物");
        }
        Set<Long> selected = ids == null ? null : new HashSet<>(ids);
        List<Map<String, Object>> data = new ArrayList<>();
        for (CharacterProfile c : characterService.list(projectId)) {
            if (selected != null && !selected.contains(c.getId())) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", c.getName());
            m.put("roleIdentity", c.getRoleIdentity());
            m.put("description", c.getDescription());
            m.put("exampleDialogue", c.getExampleDialogue());
            m.put("enabled", c.getEnabled());
            List<Map<String, Object>> nodes = new ArrayList<>();
            for (CharacterNode node : characterService.listNodes(c.getId())) {
                nodes.add(new LinkedHashMap<>(Map.of(
                        "chapterFrom", node.getChapterFrom(),
                        "description", Objects.toString(node.getDescription(), ""))));
            }
            m.put("nodes", nodes);
            data.add(m);
        }
        return data;
    }

    /** 人物导入：追加进当前项目（含演变节点），整体事务全或无 */
    @Transactional(rollbackFor = Exception.class)
    public int importCharacters(Long projectId, Map<String, Object> payload) {
        requireFormat(payload, FMT_CHARACTERS, "人物设定集");
        int imported = 0;
        for (Map<String, Object> item : dataList(payload, "data")) {
            importCharacterItem(projectId, item);
            imported++;
        }
        log.info("人物导入完成：项目 {} 导入 {} 个人物", projectId, imported);
        return imported;
    }

    private void importCharacterItem(Long projectId, Map<String, Object> item) {
        String name = requireText(item, "name", "人物姓名");
        CharacterSaveRequest req = new CharacterSaveRequest();
        req.setName(name);
        req.setRoleIdentity(text(item, "roleIdentity"));
        req.setDescription(text(item, "description"));
        req.setExampleDialogue(text(item, "exampleDialogue"));
        req.setEnabled(bool(item, "enabled", true));
        Long characterId = characterService.create(projectId, req).getId();
        Object rawNodes = item.get("nodes");
        if (rawNodes instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map)) {
                    continue;
                }
                Map<String, Object> node = asStringKeyMap(o);
                CharacterNodeSaveRequest nodeReq = new CharacterNodeSaveRequest();
                nodeReq.setChapterFrom(intValue(node.get("chapterFrom")));
                nodeReq.setDescription(text(node, "description"));
                characterService.createNode(characterId, nodeReq);
            }
        }
    }

    // ==================== 素材级：世界观设定 ====================

    public List<Map<String, Object>> exportSettings(Long projectId, List<Long> ids) {
        if (ids != null && ids.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "请至少选择一条要导出的设定");
        }
        Set<Long> selected = ids == null ? null : new HashSet<>(ids);
        List<Map<String, Object>> data = new ArrayList<>();
        for (SettingEntry s : settingService.list(projectId, null)) {
            if (selected != null && !selected.contains(s.getId())) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("title", s.getTitle());
            m.put("content", s.getContent());
            m.put("tags", s.getTags());
            m.put("triggerKeywords", s.getTriggerKeywords());
            m.put("priority", s.getPriority());
            m.put("chapterScope", s.getChapterScope());
            m.put("enabled", s.getEnabled());
            data.add(m);
        }
        return data;
    }

    @Transactional(rollbackFor = Exception.class)
    public int importSettings(Long projectId, Map<String, Object> payload) {
        requireFormat(payload, FMT_SETTINGS, "世界观设定集");
        int imported = 0;
        for (Map<String, Object> item : dataList(payload, "data")) {
            String title = requireText(item, "title", "设定名称");
            String content = requireText(item, "content", "设定内容");
            SettingSaveRequest req = new SettingSaveRequest();
            req.setTitle(title);
            req.setContent(content);
            req.setTags(text(item, "tags"));
            req.setTriggerKeywords(text(item, "triggerKeywords"));
            req.setPriority(intValue(item.get("priority")) == null ? 0 : intValue(item.get("priority")));
            req.setChapterScope(text(item, "chapterScope"));
            req.setEnabled(bool(item, "enabled", true));
            settingService.create(projectId, req);
            imported++;
        }
        log.info("世界观设定导入完成：项目 {} 导入 {} 条", projectId, imported);
        return imported;
    }

    private int importCharactersData(Long projectId, List<Map<String, Object>> items) {
        int imported = 0;
        for (Map<String, Object> item : items) {
            importCharacterItem(projectId, item);
            imported++;
        }
        return imported;
    }

    private int importSettingsData(Long projectId, List<Map<String, Object>> items) {
        int imported = 0;
        for (Map<String, Object> item : items) {
            importSettingItem(projectId, item);
            imported++;
        }
        return imported;
    }

    private void importSettingItem(Long projectId, Map<String, Object> item) {
        String title = requireText(item, "title", "设定名称");
        String content = requireText(item, "content", "设定内容");
        SettingSaveRequest req = new SettingSaveRequest();
        req.setTitle(title);
        req.setContent(content);
        req.setTags(text(item, "tags"));
        req.setTriggerKeywords(text(item, "triggerKeywords"));
        req.setPriority(intValue(item.get("priority")) == null ? 0 : intValue(item.get("priority")));
        req.setChapterScope(text(item, "chapterScope"));
        req.setEnabled(bool(item, "enabled", true));
        settingService.create(projectId, req);
    }

    // ==================== 项目级内部：蓝图 / 章节 ====================

    private List<Map<String, Object>> exportOutline(Long projectId) {
        List<Map<String, Object>> roots = new ArrayList<>();
        for (OutlineNodeVO node : outlineService.tree(projectId)) {
            roots.add(outlineToMap(node));
        }
        return roots;
    }

    private Map<String, Object> outlineToMap(OutlineNodeVO node) {
        Map<String, Object> m = new LinkedHashMap<>();
        // ref：原节点 ID，供章节导入回填蓝图关联
        m.put("ref", String.valueOf(node.getId()));
        m.put("nodeType", node.getNodeType());
        m.put("name", node.getName());
        m.put("summary", node.getSummary());
        m.put("mainCharacters", node.getMainCharacters());
        m.put("sortOrder", node.getSortOrder());
        m.put("status", node.getStatus());
        List<Map<String, Object>> children = new ArrayList<>();
        for (OutlineNodeVO child : node.getChildren()) {
            children.add(outlineToMap(child));
        }
        m.put("children", children);
        return m;
    }

    private List<Map<String, Object>> exportChapters(Long projectId) {
        List<Chapter> chapters = new ArrayList<>(chapterMapper.selectByProjectId(projectId));
        chapters.sort(Comparator.comparing(
                c -> Optional.ofNullable(c.getCreatedAt()).orElse(LocalDateTime.MIN)));
        List<Map<String, Object>> data = new ArrayList<>();
        for (Chapter c : chapters) {
            ChapterMemory memory = chapterMemoryMapper.selectByChapterId(c.getId());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("title", c.getTitle());
            m.put("content", c.getContent());
            m.put("status", c.getStatus());
            m.put("outlineRef", c.getOutlineNodeId() == null
                    ? null : String.valueOf(c.getOutlineNodeId()));
            m.put("memory", memory == null ? null : memory.getMemory());
            data.add(m);
        }
        return data;
    }

    private Map<String, Long> importOutlineData(Long projectId, List<Map<String, Object>> roots) {
        Map<String, Long> refMap = new HashMap<>();
        for (Map<String, Object> root : roots) {
            importOutlineNode(projectId, root, null, refMap);
        }
        return refMap;
    }

    private void importOutlineNode(Long projectId, Map<String, Object> item,
                                   Long parentId, Map<String, Long> refMap) {
        String name = requireText(item, "name", "节点名称");
        OutlineNodeSaveRequest req = new OutlineNodeSaveRequest();
        req.setNodeType(requireOption(item, "nodeType", "节点类型", Set.of("VOLUME", "ARC", "CHAPTER")));
        req.setName(name);
        req.setSummary(text(item, "summary"));
        req.setMainCharacters(text(item, "mainCharacters"));
        Integer sortOrder = intValue(item.get("sortOrder"));
        req.setSortOrder(sortOrder == null ? 0 : sortOrder);
        req.setStatus(text(item, "status"));
        req.setParentId(parentId);
        Long id = outlineService.create(projectId, req).getId();
        String ref = text(item, "ref");
        if (ref != null) {
            refMap.put(ref, id);
        }
        Object rawChildren = item.get("children");
        if (rawChildren instanceof List<?> children) {
            for (Object o : children) {
                if (o instanceof Map) {
                    importOutlineNode(projectId, asStringKeyMap(o), id, refMap);
                }
            }
        }
    }

    private int importChaptersData(Long projectId, List<Map<String, Object>> items,
                                   Map<String, Long> refMap) {
        int imported = 0;
        for (Map<String, Object> item : items) {
            String title = requireText(item, "title", "章节标题");
            ChapterSaveRequest req = new ChapterSaveRequest();
            req.setTitle(title);
            req.setContent(text(item, "content"));
            req.setStatus(StringUtils.hasText(text(item, "status")) ? text(item, "status") : "DRAFT");
            String outlineRef = text(item, "outlineRef");
            if (StringUtils.hasText(outlineRef)) {
                Long mapped = refMap.get(outlineRef);
                req.setOutlineNodeId(mapped);
            }
            Long chapterId = chapterService.create(projectId, req).getId();
            String memory = text(item, "memory");
            if (StringUtils.hasText(memory)) {
                ChapterMemorySaveRequest memoryReq = new ChapterMemorySaveRequest();
                memoryReq.setMemory(memory);
                chapterService.saveMemory(chapterId, memoryReq);
            }
            imported++;
        }
        return imported;
    }

    // ==================== 校验与转换工具 ====================

    private void requireFormat(Map<String, Object> payload, String expected, String label) {
        Object format = payload.get("format");
        if (!expected.equals(format)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "这不是墨笺的" + label + "导出文件（format=" + format + "）");
        }
    }

    private List<Map<String, Object>> dataList(Map<String, Object> payload, String key) {
        Object data = payload.get(key);
        if (data == null) {
            return List.of();
        }
        if (!(data instanceof List<?> list)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "导入文件的 " + key + " 应为数组");
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map)) {
                throw new BusinessException(ErrorCode.PARAM_INVALID, key + " 数组中存在非对象条目");
            }
            result.add(asStringKeyMap(o));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asStringKeyMap(Object o) {
        Map<String, Object> result = new LinkedHashMap<>();
        ((Map<Object, Object>) o).forEach((k, v) -> result.put(String.valueOf(k), v));
        return result;
    }

    private Map<String, Object> requireMap(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof Map)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "导入文件缺少 " + key + " 信息");
        }
        return asStringKeyMap(value);
    }

    private String requireText(Map<String, Object> map, String key, String label) {
        String value = text(map, key);
        if (!StringUtils.hasText(value)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, label + "不能为空");
        }
        return value;
    }

    private String requireOption(Map<String, Object> map, String key, String label, Set<String> options) {
        String value = text(map, key);
        if (!options.contains(value)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, label + "不合法：" + value);
        }
        return value;
    }

    private String text(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private boolean bool(Map<String, Object> map, String key, boolean defaultValue) {
        Object value = map.get(key);
        return value instanceof Boolean b ? b : defaultValue;
    }

    private Integer intValue(Object value) {
        return value instanceof Number n ? n.intValue() : null;
    }
}
