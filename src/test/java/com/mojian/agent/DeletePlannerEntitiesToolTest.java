package com.mojian.agent;

import com.mojian.agent.entity.AgentDeleteRequest;
import com.mojian.agent.service.AgentDeleteRequestService;
import com.mojian.agent.tool.impl.DeleteBlueprintTool;
import com.mojian.agent.tool.impl.DeleteCharacterTool;
import com.mojian.agent.tool.impl.DeleteSettingTool;
import com.mojian.outline.dto.OutlineNodeSaveRequest;
import com.mojian.outline.entity.PlotOutline;
import com.mojian.outline.service.OutlineService;
import com.mojian.project.dto.ProjectSaveRequest;
import com.mojian.project.service.ProjectService;
import com.mojian.world.character.dto.CharacterSaveRequest;
import com.mojian.world.character.entity.CharacterProfile;
import com.mojian.world.character.service.CharacterService;
import com.mojian.world.setting.dto.SettingSaveRequest;
import com.mojian.world.setting.entity.SettingEntry;
import com.mojian.world.setting.service.SettingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 策划删除工具：只生成待确认申请，作者确认后才真正删除；不能跨项目。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DeletePlannerEntitiesToolTest {

    @Autowired
    private DeleteCharacterTool deleteCharacterTool;
    @Autowired
    private DeleteSettingTool deleteSettingTool;
    @Autowired
    private DeleteBlueprintTool deleteBlueprintTool;
    @Autowired
    private AgentDeleteRequestService deleteRequestService;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private CharacterService characterService;
    @Autowired
    private SettingService settingService;
    @Autowired
    private OutlineService outlineService;

    private Long createProject(String name) {
        ProjectSaveRequest request = new ProjectSaveRequest();
        request.setName(name);
        return projectService.create(request).getId();
    }

    private CharacterProfile character(Long projectId, String name) {
        CharacterSaveRequest request = new CharacterSaveRequest();
        request.setName(name);
        return characterService.create(projectId, request);
    }

    private SettingEntry setting(Long projectId, String title) {
        SettingSaveRequest request = new SettingSaveRequest();
        request.setTitle(title);
        request.setContent("内容");
        request.setPriority(0);
        request.setEnabled(true);
        return settingService.create(projectId, request);
    }

    private PlotOutline outline(Long projectId, String name) {
        OutlineNodeSaveRequest request = new OutlineNodeSaveRequest();
        request.setNodeType("CHAPTER");
        request.setName(name);
        request.setSummary("摘要");
        return outlineService.create(projectId, request);
    }

    @Test
    @DisplayName("删除工具只申请，不直接删除；作者确认后才删除")
    void 删除需要作者确认() {
        Long projectId = createProject("策划删除测试");
        CharacterProfile character = character(projectId, "待删人物");
        SettingEntry setting = setting(projectId, "待删设定");
        PlotOutline outline = outline(projectId, "待删蓝图");
        AgentContext context = AgentContext.of(projectId, AgentRole.PLANNER);

        assertThat(deleteCharacterTool.execute(context,
                Map.of("characterId", character.getId(), "reason", "人物合并了")))
                .contains("已提交删除人物卡").contains("尚未删除");
        assertThat(deleteSettingTool.execute(context,
                Map.of("settingId", setting.getId(), "reason", "设定作废")))
                .contains("已提交删除设定").contains("尚未删除");
        assertThat(deleteBlueprintTool.execute(context,
                Map.of("nodeId", outline.getId(), "reason", "剧情重排")))
                .contains("已提交删除蓝图节点").contains("尚未删除");

        // 申请阶段数据必须原样保留
        assertThat(characterService.getById(character.getId())).isNotNull();
        assertThat(settingService.getById(setting.getId())).isNotNull();
        assertThat(outlineService.getById(outline.getId())).isNotNull();
        assertThat(deleteRequestService.pending(projectId)).hasSize(3);

        // 作者逐条确认后才真正删除
        for (AgentDeleteRequest request : deleteRequestService.pending(projectId)) {
            deleteRequestService.approve(request.getId());
        }
        assertThat(characterService.getById(character.getId())).isNull();
        assertThat(settingService.getById(setting.getId())).isNull();
        assertThat(outlineService.getById(outline.getId())).isNull();
    }

    @Test
    @DisplayName("不能跨项目申请删除；理由必填；拒绝后数据保留")
    void 安全校验与拒绝() {
        Long projectA = createProject("项目 A");
        Long projectB = createProject("项目 B");
        CharacterProfile other = character(projectB, "别家的人");
        AgentContext contextA = AgentContext.of(projectA, AgentRole.PLANNER);

        String cross = deleteCharacterTool.execute(contextA,
                Map.of("characterId", other.getId(), "reason", "手滑"));
        assertThat(cross).contains("不属于当前项目");
        assertThat(characterService.getById(other.getId())).isNotNull();
        assertThat(deleteRequestService.pending(projectA)).isEmpty();

        String noReason = deleteSettingTool.execute(contextA,
                Map.of("settingId", 1L));
        assertThat(noReason).contains("reason");

        CharacterProfile local = character(projectA, "保留的人");
        deleteCharacterTool.execute(contextA,
                Map.of("characterId", local.getId(), "reason", "测试拒绝"));
        AgentDeleteRequest request = deleteRequestService.pending(projectA).get(0);
        deleteRequestService.reject(request.getId());
        assertThat(characterService.getById(local.getId())).isNotNull();
        assertThat(deleteRequestService.pending(projectA)).isEmpty();
    }
}
