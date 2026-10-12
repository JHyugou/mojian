package com.mojian.world.character;

import com.mojian.common.api.ErrorCode;
import com.mojian.common.exception.BusinessException;
import com.mojian.generation.context.ContextAssembler;
import com.mojian.generation.context.ContextSnapshot;
import com.mojian.project.dto.ProjectSaveRequest;
import com.mojian.project.entity.StoryProject;
import com.mojian.project.service.ProjectService;
import com.mojian.world.character.dto.CharacterNodeSaveRequest;
import com.mojian.world.character.dto.CharacterSaveRequest;
import com.mojian.world.character.entity.CharacterNode;
import com.mojian.world.character.entity.CharacterProfile;
import com.mojian.world.character.mapper.CharacterNodeMapper;
import com.mojian.world.character.service.CharacterService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 人物版本节点（起点制）集成测试：
 * 节点 CRUD、起点唯一性、按章解析有效版本、快照覆盖与回落。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CharacterNodeIntegrationTest {

    @Autowired
    private CharacterService characterService;
    @Autowired
    private CharacterNodeMapper nodeMapper;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private ContextAssembler assembler;

    private Long newCharacterWithBase() {
        ProjectSaveRequest req = new ProjectSaveRequest();
        req.setName("节点测试作品");
        Long projectId = projectService.create(req).getId();

        CharacterSaveRequest charReq = new CharacterSaveRequest();
        charReq.setName("琳瑶");
        charReq.setRoleIdentity("客栈少东家");
        charReq.setDescription("凡人之躯，初入江湖，性子倔强");
        return characterService.create(projectId, charReq).getId();
    }

    private CharacterNodeSaveRequest nodeRequest(int chapterFrom, String description) {
        CharacterNodeSaveRequest req = new CharacterNodeSaveRequest();
        req.setChapterFrom(chapterFrom);
        req.setDescription(description);
        return req;
    }

    @Test
    void nodeCrudAndStartValidation() {
        Long characterId = newCharacterWithBase();

        CharacterNode node = characterService.createNode(characterId, nodeRequest(11, "觉醒血脉：筑基期，能驭雷"));
        assertEquals(11, node.getChapterFrom());
        assertEquals(1, characterService.listNodes(characterId).size());

        // 起点重复 / 起点过小 拒绝
        BusinessException dup = assertThrows(BusinessException.class,
                () -> characterService.createNode(characterId, nodeRequest(11, null)));
        assertEquals(ErrorCode.PARAM_INVALID.getCode(), dup.getErrorCode().getCode());

        BusinessException tooEarly = assertThrows(BusinessException.class,
                () -> characterService.createNode(characterId, nodeRequest(1, null)));
        assertEquals(ErrorCode.PARAM_INVALID.getCode(), tooEarly.getErrorCode().getCode());

        // 编辑与删除
        CharacterNode updated = characterService.updateNode(node.getId(), nodeRequest(12, "金丹期掌门，气场威严"));
        assertEquals(12, updated.getChapterFrom());
        characterService.deleteNode(node.getId());
        assertTrue(characterService.listNodes(characterId).isEmpty(), "删除节点后应回落基础档案");
    }

    @Test
    void effectiveVersionFollowsChapterNumber() {
        Long characterId = newCharacterWithBase();
        CharacterProfile base = characterService.requireById(characterId);

        characterService.createNode(characterId, nodeRequest(11, "觉醒血脉：筑基期，能驭雷"));
        characterService.createNode(characterId, nodeRequest(25, "金丹期掌门，气场威严"));

        List<CharacterNode> nodes = nodeMapper.selectByCharacterId(characterId);

        // 第 8 章：无已生效节点 → 基础档案（未来节点不剧透）
        ContextSnapshot at8 = assembler.buildSnapshot(
                baseProject(base), chapterOf(base), 8, null, List.of(),
                List.of(base), List.of(), List.of(), Map.of(),
                null, null, null, resolve(base, nodes, 8), null);
        assertEquals("凡人之躯，初入江湖，性子倔强", at8.getCharacters().get(0).getDescription(), "起点前应使用基础设定");

        // 第 15 章：命中节点（11章起）
        ContextSnapshot at15 = assembler.buildSnapshot(
                baseProject(base), chapterOf(base), 15, null, List.of(),
                List.of(base), List.of(), List.of(), Map.of(),
                null, null, null, resolve(base, nodes, 15), null);
        assertEquals("凡人之躯，初入江湖，性子倔强\n【自第11章起】觉醒血脉：筑基期，能驭雷",
                at15.getCharacters().get(0).getDescription(), "基础设定 + 变化节点按序追加");

        // 第 99 章：两个已生效节点按时间线全部追加
        ContextSnapshot at99 = assembler.buildSnapshot(
                baseProject(base), chapterOf(base), 99, null, List.of(),
                List.of(base), List.of(), List.of(), Map.of(),
                null, null, null, resolve(base, nodes, 99), null);
        assertEquals("凡人之躯，初入江湖，性子倔强\n【自第11章起】觉醒血脉：筑基期，能驭雷"
                        + "\n【自第25章起】金丹期掌门，气场威严",
                at99.getCharacters().get(0).getDescription(), "全部已生效节点按时间线追加");
    }

    // ---- 工具：模拟 GenerationContextService.nodesAppliedBy 的解析逻辑 ----
    private Map<Long, List<CharacterNode>> resolve(CharacterProfile base, List<CharacterNode> nodes, int chapterNo) {
        Map<Long, List<CharacterNode>> effective = new java.util.HashMap<>();
        for (CharacterNode node : nodes) {
            if (node.getChapterFrom() <= chapterNo) {
                effective.computeIfAbsent(node.getCharacterId(), k -> new java.util.ArrayList<>()).add(node);
            }
        }
        return effective;
    }

    private StoryProject baseProject(CharacterProfile base) {
        StoryProject project = new StoryProject();
        project.setId(base.getProjectId());
        project.setName("节点测试作品");
        return project;
    }

    private com.mojian.chapter.entity.Chapter chapterOf(CharacterProfile base) {
        com.mojian.chapter.entity.Chapter chapter = new com.mojian.chapter.entity.Chapter();
        chapter.setId(1L);
        chapter.setProjectId(base.getProjectId());
        chapter.setTitle("任一章");
        return chapter;
    }
}
