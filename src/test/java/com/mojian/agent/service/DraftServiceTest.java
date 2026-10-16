package com.mojian.agent.service;

import com.mojian.agent.draft.DraftStatus;
import com.mojian.agent.draft.DraftType;
import com.mojian.agent.entity.AgentDraft;
import com.mojian.common.exception.BusinessException;
import com.mojian.outline.entity.PlotOutline;
import com.mojian.outline.vo.OutlineNodeVO;
import com.mojian.project.dto.ProjectSaveRequest;
import com.mojian.project.service.ProjectService;
import com.mojian.world.character.service.CharacterService;
import com.mojian.world.setting.service.SettingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 草稿与确认的测试。
 *
 * <p>它守的是整个改造里<b>最重要的一条安全属性</b>：
 * Agent 的产出在用户点确认之前，绝不能出现在用户的书里。
 * 这条一旦破了，Agent 就能悄悄改掉作者的人物设定 —— 那是灾难级的。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DraftServiceTest {

    private static final Long CONVERSATION_ID = 999_000_002L;

    @Autowired
    private DraftService draftService;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private CharacterService characterService;
    @Autowired
    private SettingService settingService;
    @Autowired
    private com.mojian.outline.service.OutlineService outlineService;

    private Long newProject() {
        ProjectSaveRequest request = new ProjectSaveRequest();
        request.setName("草稿测试项目");
        return projectService.create(request).getId();
    }

    // ==================== 核心安全属性 ====================

    @Test
    @DisplayName("⭐ 起草只写草稿表：人物表里绝不出现未确认的内容")
    void 起草不污染人物表() {
        Long projectId = newProject();

        AgentDraft draft = draftService.draft(CONVERSATION_ID, projectId, DraftType.CHARACTER, "寒舟",
                Map.of("name", "寒舟", "roleIdentity", "道盟成员", "description", "女，32 岁"),
                "主角需要一个引路人");

        assertThat(draft.getStatus()).isEqualTo(DraftStatus.PENDING);
        assertThat(characterService.list(projectId)).as("未确认时人物表必须为空").isEmpty();
        assertThat(draftService.listPending(CONVERSATION_ID)).hasSize(1);
    }

    @Test
    @DisplayName("⭐ 确认之后才写进业务表，并记下写入的记录 id")
    void 确认后才落业务表() {
        Long projectId = newProject();
        AgentDraft draft = draftService.draft(CONVERSATION_ID, projectId, DraftType.CHARACTER, "寒舟",
                Map.of("name", "寒舟", "roleIdentity", "道盟成员", "description", "女，32 岁"),
                "主角需要一个引路人");

        AgentDraft accepted = draftService.accept(draft.getId());

        assertThat(accepted.getStatus()).isEqualTo(DraftStatus.ACCEPTED);
        assertThat(accepted.getAppliedId()).isNotNull();
        assertThat(characterService.list(projectId))
                .singleElement()
                .satisfies(character -> {
                    assertThat(character.getName()).isEqualTo("寒舟");
                    assertThat(character.getRoleIdentity()).isEqualTo("道盟成员");
                    assertThat(character.getId()).isEqualTo(accepted.getAppliedId());
                });
    }

    @Test
    @DisplayName("放弃的草稿不会写进业务表")
    void 放弃不写入() {
        Long projectId = newProject();
        AgentDraft draft = draftService.draft(CONVERSATION_ID, projectId, DraftType.SETTING, "道炁",
                Map.of("title", "道炁", "content", "一种可修炼的能量"), "支撑修炼体系");

        draftService.reject(draft.getId());

        assertThat(settingService.list(projectId, null)).isEmpty();
        assertThat(draftService.require(draft.getId()).getStatus()).isEqualTo(DraftStatus.REJECTED);
    }

    @Test
    @DisplayName("同一份草稿不能被处理两次（防重复写入）")
    void 不能重复处理() {
        Long projectId = newProject();
        AgentDraft draft = draftService.draft(CONVERSATION_ID, projectId, DraftType.SETTING, "道炁",
                Map.of("title", "道炁", "content", "内容"), null);
        draftService.accept(draft.getId());

        assertThatThrownBy(() -> draftService.accept(draft.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已经处理过");
        assertThat(settingService.list(projectId, null)).hasSize(1);
    }

    // ==================== 蓝图：决策理由必须传下去 ====================

    @Test
    @DisplayName("蓝图确认后落成蓝图节点，且决策理由被写进摘要（双 Agent 的信息损耗在这里补回）")
    void 蓝图带上决策理由() {
        Long projectId = newProject();
        AgentDraft draft = draftService.draft(CONVERSATION_ID, projectId, DraftType.BLUEPRINT, "第十三章 剑客",
                Map.of("name", "第十三章 剑客",
                        "nodeType", "CHAPTER",
                        "summary", "陌在官道上遇一名亦敌亦友的剑客",
                        "detail", "地点：官道；在场：陌、瑜缑、剑客；冲突：剑客奉命追缴殇剑"),
                "选旧识而非仇敌，因为第三章还没到揭示仇怨的时机");

        AgentDraft accepted = draftService.accept(draft.getId());

        List<OutlineNodeVO> tree = outlineService.tree(projectId);
        assertThat(tree).hasSize(1);
        OutlineNodeVO node = tree.get(0);
        assertThat(node.getName()).isEqualTo("第十三章 剑客");
        assertThat(node.getNodeType()).isEqualTo("CHAPTER");
        assertThat(node.getSummary())
                .contains("陌在官道上遇一名亦敌亦友的剑客")
                .contains("地点：官道")
                .contains("【决策理由】")
                .contains("因为第三章还没到揭示仇怨的时机");
        assertThat(accepted.getAppliedId()).isEqualTo(node.getId());
    }

    // ==================== 修改已有内容（引用式改稿的落点） ====================

    @Test
    @DisplayName("⭐ 修改草稿确认后改的是原节点，不会凭空多出一个重复节点")
    void 修改蓝图不新增节点() {
        Long projectId = newProject();

        AgentDraft created = draftService.draft(CONVERSATION_ID, projectId, DraftType.BLUEPRINT, "第一章",
                Map.of("name", "第一章", "nodeType", "CHAPTER", "summary", "原来的内容"), "初始规划");
        Long nodeId = draftService.accept(created.getId()).getAppliedId();
        assertThat(nodeId).isNotNull();

        AgentDraft revision = draftService.draft(CONVERSATION_ID, projectId, DraftType.BLUEPRINT, "第一章",
                Map.of("name", "第一章", "nodeType", "CHAPTER", "summary", "改成白天"), "用户要求改",
                nodeId);

        // 确认之前，蓝图不该有任何变化。
        // 注意摘要里会带上【决策理由】（新增时有意为之：让理由跟着蓝图传下去），
        // 所以这里断言的是"包含原内容且不含新内容"，而不是全等
        OutlineNodeVO before = outlineService.tree(projectId).get(0);
        assertThat(before.getSummary())
                .as("未确认时不能改")
                .contains("原来的内容")
                .doesNotContain("改成白天");

        draftService.accept(revision.getId());

        List<OutlineNodeVO> after = outlineService.tree(projectId);
        assertThat(after).as("修改不能变成新增").hasSize(1);
        assertThat(after.get(0).getId()).isEqualTo(nodeId);
        assertThat(after.get(0).getSummary()).isEqualTo("改成白天");
    }

    @Test
    @DisplayName("修改时没提到的字段保持原样（update 是属性全量覆盖，漏传会被清空）")
    void 修改保留未提及字段() {
        Long projectId = newProject();

        AgentDraft created = draftService.draft(CONVERSATION_ID, projectId, DraftType.BLUEPRINT, "第一卷",
                Map.of("name", "第一卷", "nodeType", "VOLUME", "summary", "卷概要"), "初始");
        Long nodeId = draftService.accept(created.getId()).getAppliedId();

        // 草稿里只带 summary，且故意不带 name / nodeType
        AgentDraft revision = draftService.draft(CONVERSATION_ID, projectId, DraftType.BLUEPRINT, "第一卷",
                Map.of("summary", "改过的卷概要"), "只改概要", nodeId);
        draftService.accept(revision.getId());

        OutlineNodeVO node = outlineService.tree(projectId).get(0);
        assertThat(node.getName()).as("name 没被提到，必须保留").isEqualTo("第一卷");
        assertThat(node.getNodeType()).as("nodeType 没被提到，必须保留").isEqualTo("VOLUME");
        assertThat(node.getSummary()).isEqualTo("改过的卷概要");
    }

    @Test
    @DisplayName("草稿能区分新增与修改（targetId 为空=新增）")
    void 新增与修改可区分() {
        Long projectId = newProject();

        AgentDraft fresh = draftService.draft(CONVERSATION_ID, projectId, DraftType.BLUEPRINT, "甲",
                Map.of("name", "甲", "nodeType", "CHAPTER", "summary", "内容"), null);
        assertThat(fresh.getTargetId()).isNull();
        draftService.accept(fresh.getId());

        AgentDraft created2 = draftService.draft(CONVERSATION_ID, projectId, DraftType.BLUEPRINT, "乙",
                Map.of("name", "乙", "nodeType", "CHAPTER", "summary", "内容"), null);
        Long nodeId = draftService.accept(created2.getId()).getAppliedId();

        AgentDraft revised = draftService.draft(CONVERSATION_ID, projectId, DraftType.BLUEPRINT, "乙",
                Map.of("name", "乙", "nodeType", "CHAPTER", "summary", "新内容"), null, nodeId);
        assertThat(revised.getTargetId()).isEqualTo(nodeId);

        draftService.accept(revised.getId());
        assertThat(outlineService.tree(projectId)).as("一新增一修改，共两个节点").hasSize(2);
    }

    // ==================== 细节 ====================

    @Test
    @DisplayName("草稿内容能原样读回（前端要拿它渲染确认卡片）")
    void 草稿内容可读回() {
        Long projectId = newProject();
        AgentDraft draft = draftService.draft(CONVERSATION_ID, projectId, DraftType.SETTING, "玄峰",
                Map.of("title", "玄峰", "content", "道盟核心据点", "tags", "地理、组织",
                        "triggerKeywords", "玄峰、山门"), "需要一处据点");

        Map<String, String> payload = draftService.payloadOf(draft);

        assertThat(payload)
                .containsEntry("title", "玄峰")
                .containsEntry("content", "道盟核心据点")
                .containsEntry("tags", "地理、组织")
                .containsEntry("triggerKeywords", "玄峰、山门");
    }

    @Test
    @DisplayName("待确认列表只含 PENDING，确认过的自动移出")
    void 待确认列表随处理更新() {
        Long projectId = newProject();
        AgentDraft first = draftService.draft(CONVERSATION_ID, projectId, DraftType.SETTING, "甲",
                Map.of("title", "甲", "content", "内容"), null);
        draftService.draft(CONVERSATION_ID, projectId, DraftType.SETTING, "乙",
                Map.of("title", "乙", "content", "内容"), null);

        assertThat(draftService.listPending(CONVERSATION_ID)).hasSize(2);

        draftService.accept(first.getId());

        assertThat(draftService.listPending(CONVERSATION_ID))
                .singleElement()
                .extracting(AgentDraft::getTitle)
                .isEqualTo("乙");
    }
}
