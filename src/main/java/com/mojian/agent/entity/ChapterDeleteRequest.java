package com.mojian.agent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.mojian.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 写作 Agent 发起的章节删除申请。
 *
 * <p>删除是不可逆的高风险动作：默认必须由作者确认；只有作者在偏好设置里
 * 显式开启"自动批准删除章节"时，工具才会当场删。
 * 即使自动删除，也保留这条记录（APPROVED）以便审计。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("chapter_delete_request")
public class ChapterDeleteRequest extends BaseEntity {

    private Long projectId;

    private Long conversationId;

    private Long chapterId;

    /** 申请时的章节标题快照，删除后仍能看见删了哪一章 */
    private String chapterTitle;

    /** Agent 给出的删除理由，必须展示给作者 */
    private String reason;

    /** PENDING / APPROVED / REJECTED */
    private String status;

    private String source;
}
