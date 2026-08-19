package com.mojian.generation.context;

import com.mojian.chapter.entity.Chapter;
import com.mojian.chapter.entity.ChapterMemory;
import com.mojian.generation.context.ContextAssembler;
import com.mojian.generation.context.ContextSnapshot;
import com.mojian.outline.entity.PlotOutline;
import com.mojian.project.entity.StoryProject;
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
            memory.setSummary("第" + i + "章摘要");
            memories.put((long) i, memory);
        }

        Chapter current = chapters.get(4);
        ContextSnapshot snapshot = assembler.buildSnapshot(
                project, current, 5, null, List.of(), List.of(), List.of(),
                chapters, memories, null, null, null);

        assertEquals(3, snapshot.getPreviousMemories().size());
        assertEquals("第2章摘要", snapshot.getPreviousMemories().get(0).getSummary());
        // 当前章（第5章）的记忆不注入，取其之前最近 3 章：2/3/4 章
        assertEquals("第4章摘要", snapshot.getPreviousMemories().get(2).getSummary());
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
