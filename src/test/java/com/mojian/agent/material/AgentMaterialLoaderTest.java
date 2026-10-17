package com.mojian.agent.material;

import com.mojian.agent.context.AgentContextAssembler;
import com.mojian.agent.context.AgentContextAssembly;
import com.mojian.agent.context.AgentContextBudget;
import com.mojian.agent.context.AgentContextSection;
import com.mojian.chapter.entity.Chapter;
import com.mojian.chapter.entity.ChapterMemory;
import com.mojian.chapter.mapper.ChapterMapper;
import com.mojian.chapter.mapper.ChapterMemoryMapper;
import com.mojian.project.dto.ProjectSaveRequest;
import com.mojian.project.service.ProjectService;
import com.mojian.world.character.entity.CharacterProfile;
import com.mojian.world.character.mapper.CharacterProfileMapper;
import com.mojian.world.setting.entity.SettingEntry;
import com.mojian.world.setting.mapper.SettingEntryMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 取数器测试（测试库，事务回滚）。
 *
 * <p>它要保证的是：<b>Agent 睁眼看到的世界是对的</b>。
 * 取数漏了人物、章节顺序反了、未开写的章节被当成空内容 ——
 * 这些都不会报异常，只会表现为"Agent 的建议莫名其妙"，很难倒查。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AgentMaterialLoaderTest {

    /** 真实模型是 1M 窗口；这里按 1M 上限、预留 16K 输出算预算 */
    private static final AgentContextBudget MILLION_BUDGET =
            AgentContextBudget.of(1_048_576, 16_384);

    @Autowired
    private AgentMaterialLoader loader;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private CharacterProfileMapper characterMapper;
    @Autowired
    private SettingEntryMapper settingMapper;
    @Autowired
    private ChapterMapper chapterMapper;
    @Autowired
    private ChapterMemoryMapper chapterMemoryMapper;

    private final AgentContextAssembler assembler = new AgentContextAssembler();

    // ==================== 造数据 ====================

    private Long newProject() {
        ProjectSaveRequest request = new ProjectSaveRequest();
        request.setName("取数器测试项目");
        return projectService.create(request).getId();
    }

    private void addCharacter(Long projectId, String name, String role, String description, boolean enabled) {
        CharacterProfile character = new CharacterProfile();
        character.setProjectId(projectId);
        character.setName(name);
        character.setRoleIdentity(role);
        character.setDescription(description);
        character.setEnabled(enabled);
        characterMapper.insert(character);
    }

    private void addSetting(Long projectId, String title, String content, String tags) {
        SettingEntry setting = new SettingEntry();
        setting.setProjectId(projectId);
        setting.setTitle(title);
        setting.setContent(content);
        setting.setTags(tags);
        setting.setPriority(0);
        setting.setEnabled(true);
        settingMapper.insert(setting);
    }

    /** createdAt 显式设置，让"章节顺序"在测试里是确定的，不依赖数据库返回顺序 */
    private Chapter addChapter(Long projectId, String title, String content, int order) {
        Chapter chapter = new Chapter();
        chapter.setProjectId(projectId);
        chapter.setTitle(title);
        chapter.setContent(content);
        chapter.setStatus("COMPLETED");
        chapter.setCurrentVersionNo(0);
        chapter.setCreatedAt(LocalDateTime.of(2026, 1, 1, 0, 0).plusMinutes(order));
        chapterMapper.insert(chapter);
        return chapter;
    }

    private void addMemory(Long chapterId, String memory) {
        ChapterMemory entity = new ChapterMemory();
        entity.setChapterId(chapterId);
        entity.setMemory(memory);
        chapterMemoryMapper.insert(entity);
    }

    private List<AgentContextSection> sectionsOf(String kind, Long projectId) {
        return loader.loadAll(projectId).stream().filter(s -> kind.equals(s.kind())).toList();
    }

    // ==================== 用例 ====================

    @Test
    @DisplayName("全量取数：人物、设定、前情摘要、章节正文四类都取到")
    void 四类材料都取到() {
        Long projectId = newProject();
        addCharacter(projectId, "寒舟", "道盟成员", "女，32 岁，伪装成小摊小贩。", true);
        addSetting(projectId, "道炁", "一种可以修炼的能量。", "修炼｜核心");
        Chapter chapter = addChapter(projectId, "夜市的跟踪者", "深夜市集收摊……", 1);
        addMemory(chapter.getId(), "陌察觉被人跟踪。");

        List<AgentContextSection> sections = loader.loadAll(projectId);

        assertThat(sections).extracting(AgentContextSection::kind)
                .contains("人物", "设定", "摘要", "原文");
        assertThat(sections).extracting(AgentContextSection::title)
                .contains("人物档案", "世界观设定", "前情摘要", "第 1 章｜夜市的跟踪者");
    }

    @Test
    @DisplayName("章节一章一块，最近的一章排在最前、优先级最低（裁剪才会从最远的开始砍）")
    void 章节分块且按距离排优先级() {
        Long projectId = newProject();
        addChapter(projectId, "第一章", "正一", 1);
        addChapter(projectId, "第二章", "正二", 2);
        addChapter(projectId, "第三章", "正三", 3);

        List<AgentContextSection> chapters = sectionsOf("原文", projectId);

        assertThat(chapters).hasSize(3);
        assertThat(chapters.get(0).title()).contains("第 3 章");
        assertThat(chapters.get(1).title()).contains("第 2 章");
        assertThat(chapters.get(2).title()).contains("第 1 章");

        assertThat(chapters.get(0).priority())
                .isLessThan(chapters.get(1).priority());
        assertThat(chapters.get(1).priority())
                .isLessThan(chapters.get(2).priority());
    }

    @Test
    @DisplayName("优先顺序正确：摘要 < 人物 < 设定 < 章节（数字小=先装）")
    void 优先级顺序符合设计() {
        Long projectId = newProject();
        addCharacter(projectId, "寒舟", "道盟成员", "描述", true);
        addSetting(projectId, "道炁", "内容", "标签");
        Chapter chapter = addChapter(projectId, "第一章", "正文", 1);
        addMemory(chapter.getId(), "摘要");

        List<AgentContextSection> sections = loader.loadAll(projectId);
        int memory = priorityOf(sections, "摘要");
        int character = priorityOf(sections, "人物");
        int setting = priorityOf(sections, "设定");
        int chapterPriority = priorityOf(sections, "原文");

        assertThat(memory).isLessThan(character);
        assertThat(character).isLessThan(setting);
        assertThat(setting).isLessThan(chapterPriority);
    }

    private int priorityOf(List<AgentContextSection> sections, String kind) {
        return sections.stream().filter(s -> kind.equals(s.kind()))
                .findFirst().orElseThrow().priority();
    }

    @Test
    @DisplayName("未开写的章节标注清楚，而不是让模型以为它是空白")
    void 未开写章节有标注() {
        Long projectId = newProject();
        addChapter(projectId, "已写", "已写好的正文", 1);
        addChapter(projectId, "待写", null, 2);

        List<AgentContextSection> chapters = sectionsOf("原文", projectId);

        assertThat(chapters.get(0).title()).contains("第 2 章");
        assertThat(chapters.get(0).content()).contains("尚未开写");
        assertThat(chapters.get(1).content()).isEqualTo("已写好的正文");
    }

    @Test
    @DisplayName("停用的人物不进入资料（停用就是不想让它出现）")
    void 停用人物被排除() {
        Long projectId = newProject();
        addCharacter(projectId, "寒舟", "道盟成员", "正常人物", true);
        addCharacter(projectId, "废弃角色", "旧设定", "这个不该出现", false);

        List<AgentContextSection> characters = sectionsOf("人物", projectId);

        assertThat(characters).hasSize(1);
        assertThat(characters.get(0).content()).contains("寒舟").doesNotContain("废弃角色");
    }

    @Test
    @DisplayName("空项目：不报错。概况（书名）仍在，其余内容类材料为空")
    void 空项目安全() {
        List<AgentContextSection> all = loader.loadAll(newProject());

        // 概况是"这本书是什么"，只要项目存在就有（书名至少有一个）。
        // 它必须是 REQUIRED —— 宁可裁掉几章原文，也不能让 Agent 不知道
        // 自己在写什么题材的书。
        // 注意：required() 造出来的 kind 统一是"必需"，靠 title 区分
        assertThat(all.stream().filter(section -> "本书概况".equals(section.title())).toList())
                .singleElement()
                .satisfies(section -> assertThat(section.required()).isTrue());

        // 内容类材料（摘要 / 蓝图 / 人物 / 设定 / 章节）应当一个都没有
        assertThat(all).noneMatch(section -> List.of("摘要", "蓝图", "人物", "设定", "原文")
                .contains(section.kind()));
    }

    @Test
    @DisplayName("⭐ 联调组装器：真实规模的资料塞进 1M 预算，零裁剪 —— 这就是'不做检索'的前提")
    void 全塞进百万预算时零裁剪() {
        Long projectId = newProject();
        addCharacter(projectId, "寒舟", "道盟成员", "女，32 岁，伪装成小摊小贩。".repeat(20), true);
        addSetting(projectId, "道炁", "一种可以修炼的能量。".repeat(50), "修炼");
        for (int i = 1; i <= 10; i++) {
            Chapter chapter = addChapter(projectId, "第 " + i + " 章", "正文内容。".repeat(2000), i);
            addMemory(chapter.getId(), "本章发生了什么。".repeat(50));
        }

        List<AgentContextSection> sections = loader.loadAll(projectId);
        AgentContextAssembly assembly = assembler.assemble(sections, MILLION_BUDGET);

        assertThat(assembly.report().trimmedTitles())
                .as("1M 预算下不该裁掉任何材料")
                .isEmpty();
        assertThat(assembly.report().usedTokens()).isLessThan(MILLION_BUDGET.usableTokens());
        assertThat(assembly.text())
                .contains("【人物档案】")
                .contains("【世界观设定】")
                .contains("【前情摘要】")
                .contains("【第 10 章");

        // 顺带把用量打出来，方便肉眼确认"离装不下还有多远"
        System.out.println("联调用量：" + assembly.report().summary());
    }
}
