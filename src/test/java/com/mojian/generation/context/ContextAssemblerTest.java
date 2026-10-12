package com.mojian.generation.context;

import com.mojian.chapter.entity.Chapter;
import com.mojian.chapter.entity.ChapterMemory;
import com.mojian.generation.context.ContextAssembler;
import com.mojian.generation.context.ContextSnapshot;
import com.mojian.outline.entity.PlotOutline;
import com.mojian.project.entity.StoryProject;
import com.mojian.world.character.entity.CharacterNode;
import com.mojian.world.character.entity.CharacterProfile;
import com.mojian.world.setting.entity.SettingEntry;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 上下文组装引擎纯函数单测（SRS 13.1）：
 * 章节范围解析、关键词/优先级筛选、容量上限、启用过滤、引用人物优先。
 */
class ContextAssemblerTest {

    private final ContextAssembler assembler = new ContextAssembler();

    // ==================== 章节范围解析 ====================

    @Test
    void scopeSingleNumber() {
        assertTrue(assembler.scopeContains("3", 3));
        assertFalse(assembler.scopeContains("3", 4));
    }

    @Test
    void scopeRange() {
        assertTrue(assembler.scopeContains("3-30", 15));
        assertFalse(assembler.scopeContains("3-30", 2));
        assertFalse(assembler.scopeContains("3-30", 31));
    }

    @Test
    void scopeMixedList() {
        assertTrue(assembler.scopeContains("1,5,9-12", 10));
        assertFalse(assembler.scopeContains("1,5,9-12", 7));
    }

    @Test
    void scopeBlankMeansAllChapters() {
        assertTrue(assembler.scopeContains(null, 100));
        assertTrue(assembler.scopeContains("", 100));
    }

    // ==================== 设定筛选 ====================

    @Test
    void settingKeywordHitBeatsPriority() {
        SettingEntry keywordHit = setting("雪山剑冢", "内容", "剑冢", 0);
        SettingEntry highPriorityNoHit = setting("宗门戒律", "内容", "戒律", 99);

        List<SettingEntry> selected = assembler.selectSettings(
                List.of(highPriorityNoHit, keywordHit), "本章发生在剑冢", 1);

        assertEquals("雪山剑冢", selected.get(0).getTitle(), "关键词命中应排在高优先级未命中之前");
    }

    @Test
    void settingScopeFiltersOut() {
        SettingEntry scoped = setting("前期伏笔", "内容", null, 10);
        scoped.setChapterScope("1-10");

        assertFalse(assembler.selectSettings(List.of(scoped), "任意", 11).contains(scoped));
        assertTrue(assembler.selectSettings(List.of(scoped), "任意", 5).contains(scoped));
    }

    @Test
    void disabledSettingNeverSelected() {
        SettingEntry disabled = setting("停用设定", "内容", null, 99);
        disabled.setEnabled(false);

        assertTrue(assembler.selectSettings(List.of(disabled), "任意", 1).isEmpty());
    }

    @Test
    void settingCapacityCappedAtEight() {
        List<SettingEntry> many = new java.util.ArrayList<>();
        for (int i = 0; i < 15; i++) {
            many.add(setting("设定" + i, "内容", null, 0));
        }

        assertEquals(ContextAssembler.MAX_SETTINGS, assembler.selectSettings(many, "", 1).size());
    }

    // ==================== 人物筛选 ====================

    @Test
    void disabledCharacterExcluded() {
        CharacterProfile disabled = character("路人甲");
        disabled.setEnabled(false);

        assertTrue(assembler.selectCharacters(List.of(disabled), List.of()).isEmpty());
    }

    @Test
    void referencedCharacterFirst() {
        CharacterProfile referenced = character("主角");
        CharacterProfile earlier = character("更早创建的配角");
        earlier.setCreatedAt(LocalDateTime.now().minusDays(1));

        List<CharacterProfile> selected = assembler.selectCharacters(
                List.of(earlier, referenced), List.of(referenced.getId()));

        assertEquals("主角", selected.get(0).getName(), "蓝图引用的人物应排在普通人物之前");
    }

    @Test
    void characterCapacityCapped() {
        List<CharacterProfile> many = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            many.add(character("人物" + i));
        }

        assertEquals(ContextAssembler.MAX_CHARACTERS, assembler.selectCharacters(many, List.of()).size());
    }

    // ==================== 记忆选取 ====================

    @Test
    void memoryTakesLatestThreeBeforeCurrentChapter() {
        StoryProject project = new StoryProject();
        project.setName("测试作品");

        List<Chapter> chapters = new java.util.ArrayList<>();
        Map<Long, ChapterMemory> memories = new HashMap<>();
        for (int i = 1; i <= 5; i++) {
            Chapter chapter = new Chapter();
            chapter.setId((long) i);
            chapter.setTitle("第" + i + "章");
            chapters.add(chapter);
            ChapterMemory memory = new ChapterMemory();
            memory.setMemory("第" + i + "章记忆全文");
            memories.put((long) i, memory);
        }

        Chapter current = chapters.get(4);
        ContextSnapshot snapshot = assembler.buildSnapshot(
                project, current, 5, null, List.of(), List.of(), List.of(),
                chapters, memories, null, null, null, null, null);

        // 全量注入：之前所有章节的记忆都进窗口，不再只取最近 3 章
        assertEquals(4, snapshot.getPreviousMemories().size());
        assertEquals("第1章记忆全文", snapshot.getPreviousMemories().get(0).getMemory());
        assertEquals("第4章记忆全文", snapshot.getPreviousMemories().get(3).getMemory());
        assertNotNull(snapshot.getPreviousMemories().get(0).getChapterTitle());
    }

    // ==================== 现有正文注入 ====================

    @Test
    void snapshotCarriesCurrentContentForContinuation() {
        StoryProject project = new StoryProject();
        project.setName("测试作品");

        Chapter chapter = new Chapter();
        chapter.setId(1L);
        chapter.setTitle("第一章");
        chapter.setContent("雪落长街，他背着剑走进客栈。");

        ContextSnapshot snapshot = assembler.buildSnapshot(
                project, chapter, 1, null, List.of(), List.of(), List.of(),
                List.of(chapter), Map.of(), null, null, null, null, null);

        assertEquals("雪落长街，他背着剑走进客栈。", snapshot.getCurrentContent(),
                "现有正文必须进入上下文快照（续写/改写/记忆提炼的语境基础）");
    }

    @Test
    void previousChapterTailTruncatedToTail() {
        String longContent = "前".repeat(5000);
        String tail = assembler.previousChapterTail(longContent);
        assertEquals(ContextAssembler.PREVIOUS_TAIL_CHARS, tail.length(), "取末尾 4000 字");
        assertEquals("前".repeat(4000), tail, "截取的是尾部");

        assertNull(assembler.previousChapterTail(null));
        assertNull(assembler.previousChapterTail("  "));
        assertEquals("短结尾", assembler.previousChapterTail("短结尾"));
    }

    @Test
    void previousTailInjectedIntoSnapshot() {
        StoryProject project = new StoryProject();
        project.setName("测试作品");

        Chapter chapter = new Chapter();
        chapter.setId(1L);
        chapter.setTitle("第二章");

        ContextSnapshot snapshot = assembler.buildSnapshot(
                project, chapter, 2, null, List.of(), List.of(), List.of(),
                List.of(chapter), Map.of(), null, null, null, null, "上一章结尾正文");

        assertEquals("上一章结尾正文", snapshot.getPreviousContentTail(),
                "上一章正文尾部应进入快照以衔接文风");
    }

    @Test
    void currentContentOverLimitCondensedKeepingHeadAndTail() {
        String longContent = "江" + "水".repeat(ContextAssembler.CURRENT_CONTENT_MAX_CHARS) + "入海";

        String condensed = assembler.condenseContent(longContent);

        assertTrue(condensed.length() <= ContextAssembler.CURRENT_CONTENT_MAX_CHARS,
                "压缩结果不得超过上限");
        assertTrue(condensed.startsWith("江"), "开头保留（续写衔接处）");
        assertTrue(condensed.endsWith("入海"), "结尾保留（贴近续写点）");
        assertTrue(condensed.contains("已省略"), "中段应有省略标记");
    }

    // ==================== 人物设定合并框 + 节点时间线追加 ====================

    @Test
    void descriptionCarriedIntoSnapshotAndNodeAppendedAsTimeline() {
        StoryProject project = new StoryProject();
        project.setName("测试作品");

        Chapter chapter = new Chapter();
        chapter.setId(9L);
        chapter.setTitle("第二章");

        CharacterProfile profile = character("林晚照");
        profile.setDescription("【外貌】青衫仗剑\n【能力】筑基后期；擅御剑");

        CharacterNode node = new CharacterNode();
        node.setId(900L);
        node.setCharacterId(profile.getId());
        node.setChapterFrom(2);
        node.setDescription("【能力】金丹期；擅雷法\n【状态】气场威严");

        ContextSnapshot snapshot = assembler.buildSnapshot(
                project, chapter, 2, null, List.of(), List.of(profile), List.of(),
                List.of(chapter), Map.of(), null, null, null,
                Map.of(profile.getId(), List.of(node)), null);

        var brief = snapshot.getCharacters().get(0);
        assertEquals("【外貌】青衫仗剑\n【能力】筑基后期；擅御剑"
                        + "\n【自第2章起】【能力】金丹期；擅雷法\n【状态】气场威严",
                brief.getDescription(),
                "增量时间线：基础设定在前，已生效节点的变化按序追加");

        ContextSnapshot withoutNode = assembler.buildSnapshot(
                project, chapter, 2, null, List.of(), List.of(profile), List.of(),
                List.of(chapter), Map.of(), null, null, null, Map.of(), null);
        assertEquals("【外貌】青衫仗剑\n【能力】筑基后期；擅御剑",
                withoutNode.getCharacters().get(0).getDescription(),
                "无节点的章节应沿用基础档案设定");
    }

    private CharacterNode node(Long characterId, int chapterFrom, String description) {
        CharacterNode n = new CharacterNode();
        n.setId((long) chapterFrom * 1000);
        n.setCharacterId(characterId);
        n.setChapterFrom(chapterFrom);
        n.setDescription(description);
        return n;
    }

    // ==================== 对话示例的世界观选取 ====================

    @Test
    void dialogueSettingsPreferKeywordHitsAndDropDisabled() {
        SettingEntry hit = setting("剑冢", "万剑埋骨地", "剑冢", 0);
        SettingEntry missHigh = setting("南疆蛊术", "内容", "蛊术", 99);
        SettingEntry disabled = setting("停用条目", "内容", "剑冢", 100);
        disabled.setEnabled(false);

        List<SettingEntry> selected = assembler.selectSettingsForCharacterDialogue(
                List.of(missHigh, disabled, hit), "琳瑶镇守剑冢多年");

        assertEquals(List.of("剑冢", "南疆蛊术"),
                selected.stream().map(SettingEntry::getTitle).toList(),
                "关键词命中优先于高优先级未命中；停用条目剔除");
        assertTrue(selected.size() <= ContextAssembler.MAX_DIALOGUE_SETTINGS);
    }

    // ==================== 对话示例的其他人物选取 ====================

    @Test
    void dialogueOthersExcludeSelfAndPrioritizeMentioned() {
        CharacterProfile self = character("琳瑶");
        CharacterProfile mentioned = character("顾行舟");
        CharacterProfile stranger = character("路人甲");
        CharacterProfile disabled = character("停用者");
        disabled.setEnabled(false);

        List<CharacterProfile> selected = assembler.selectOthersForCharacterDialogue(
                List.of(self, stranger, disabled, mentioned), self.getId(),
                "她与顾行舟有旧约");

        assertEquals(List.of("顾行舟", "路人甲"),
                selected.stream().map(CharacterProfile::getName).toList(),
                "被主角设定点名的人物排前；自身与停用者剔除");
        assertTrue(selected.size() <= ContextAssembler.MAX_DIALOGUE_OTHERS);
    }

    // ==================== 工具 ====================

    private SettingEntry setting(String title, String content, String keywords, int priority) {
        SettingEntry entry = new SettingEntry();
        entry.setId((long) (Math.random() * 1000000));
        entry.setTitle(title);
        entry.setContent(content);
        entry.setTriggerKeywords(keywords);
        entry.setPriority(priority);
        entry.setEnabled(true);
        entry.setCreatedAt(LocalDateTime.now());
        return entry;
    }

    private CharacterProfile character(String name) {
        CharacterProfile profile = new CharacterProfile();
        profile.setId((long) (Math.random() * 1000000));
        profile.setName(name);
        profile.setEnabled(true);
        profile.setCreatedAt(LocalDateTime.now());
        return profile;
    }
}
