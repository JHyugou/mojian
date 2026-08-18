-- ============================================================
-- 墨笺 · 数据表结构（幂等脚本，可重复执行）
-- 表结构变更时：修改本文件后 DROP DATABASE mojian 重建，
-- 或手动 DROP 对应表后重新执行。
-- ============================================================

-- 1. 创作项目
CREATE TABLE IF NOT EXISTS story_project (
    id            BIGINT       NOT NULL COMMENT '主键（雪花ID）',
    name          VARCHAR(100) NOT NULL COMMENT '项目名称',
    intro         VARCHAR(1000)         DEFAULT NULL COMMENT '简介',
    genre         VARCHAR(50)           DEFAULT NULL COMMENT '题材',
    style         VARCHAR(200)          DEFAULT NULL COMMENT '整体文风',
    writing_rules TEXT COMMENT '项目级默认写作规则',
    created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_project_name (name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '创作项目';

-- 2. 人物档案
CREATE TABLE IF NOT EXISTS character_profile (
    id             BIGINT       NOT NULL COMMENT '主键（雪花ID）',
    project_id     BIGINT       NOT NULL COMMENT '所属项目',
    name           VARCHAR(50)  NOT NULL COMMENT '姓名',
    role_identity  VARCHAR(200)          DEFAULT NULL COMMENT '身份/角色定位',
    appearance     TEXT COMMENT '外貌描述',
    personality    TEXT COMMENT '性格特征',
    core_goal      TEXT COMMENT '核心目标',
    main_conflict  TEXT COMMENT '主要矛盾',
    relationships  TEXT COMMENT '人物关系（自由文本或JSON）',
    speech_style   TEXT COMMENT '语言风格',
    example_dialogue TEXT COMMENT '对话示例（模仿声口最有效的素材，参考SillyTavern角色卡mes_example）',
    current_status TEXT COMMENT '当前状态',
    character_arc  TEXT COMMENT '人物发展弧线',
    enabled        TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否启用：启用才参与默认上下文组装',
    created_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_character_project (project_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '人物档案';

-- 3. 设定条目
CREATE TABLE IF NOT EXISTS setting_entry (
    id               BIGINT       NOT NULL COMMENT '主键（雪花ID）',
    project_id       BIGINT       NOT NULL COMMENT '所属项目',
    title            VARCHAR(100) NOT NULL COMMENT '条目名称',
    content          TEXT         NOT NULL COMMENT '设定内容',
    tags             VARCHAR(500)          DEFAULT NULL COMMENT '标签（逗号分隔）',
    trigger_keywords VARCHAR(500)          DEFAULT NULL COMMENT '触发关键词（逗号分隔）',
    priority         INT          NOT NULL DEFAULT 0 COMMENT '优先级：数值越大越优先注入',
    chapter_scope    VARCHAR(100)          DEFAULT NULL COMMENT '适用章节范围，如 3-30；NULL 表示全部章节',
    enabled          TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否启用',
    created_at       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_setting_project (project_id, enabled)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '故事设定条目';

-- 4. 剧情蓝图节点（卷/篇章/章节三级，parent_id 构成树）
CREATE TABLE IF NOT EXISTS plot_outline (
    id             BIGINT       NOT NULL COMMENT '主键（雪花ID）',
    project_id     BIGINT       NOT NULL COMMENT '所属项目',
    parent_id      BIGINT                DEFAULT NULL COMMENT '父节点ID，NULL 为根（卷）',
    node_type      VARCHAR(20)  NOT NULL COMMENT '节点类型：VOLUME/ARC/CHAPTER',
    name           VARCHAR(100) NOT NULL COMMENT '节点名称',
    summary        TEXT COMMENT '摘要',
    plot_goal      TEXT COMMENT '剧情目标',
    main_characters TEXT COMMENT '章节节点：主要人物档案ID（JSON数组）',
    conflict       TEXT COMMENT '章节节点：冲突',
    scene          TEXT COMMENT '章节节点：场景',
    must_events    TEXT COMMENT '章节节点：必须发生的情节',
    sort_order     INT          NOT NULL DEFAULT 0 COMMENT '同级排序',
    status         VARCHAR(20)  NOT NULL DEFAULT 'PLANNED' COMMENT '状态：PLANNED/WRITING/DONE',
    created_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_outline_project_parent (project_id, parent_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '剧情蓝图节点';

-- 5. 章节
CREATE TABLE IF NOT EXISTS chapter (
    id                 BIGINT       NOT NULL COMMENT '主键（雪花ID）',
    project_id         BIGINT       NOT NULL COMMENT '所属项目',
    outline_node_id    BIGINT                DEFAULT NULL COMMENT '关联的蓝图章节节点',
    title              VARCHAR(200) NOT NULL COMMENT '章节标题',
    content            LONGTEXT COMMENT '当前工作正文',
    status             VARCHAR(20)  NOT NULL DEFAULT 'DRAFT' COMMENT '状态：DRAFT/WRITING/COMPLETED',
    current_version_no INT          NOT NULL DEFAULT 0 COMMENT '当前版本号',
    created_at         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_chapter_project (project_id),
    KEY idx_chapter_outline_node (outline_node_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '章节';

-- 6. 章节记忆（与章节一对一）
CREATE TABLE IF NOT EXISTS chapter_memory (
    id                   BIGINT   NOT NULL COMMENT '主键（雪花ID）',
    chapter_id           BIGINT   NOT NULL COMMENT '所属章节',
    summary              TEXT COMMENT '章节摘要',
    key_facts            TEXT COMMENT '重要事实',
    unresolved_conflicts TEXT COMMENT '未解决冲突',
    character_changes    TEXT COMMENT '人物状态变化',
    created_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_memory_chapter (chapter_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '章节记忆';

-- 7. 生成任务
-- active_slot 为生成列：任务处于 CREATED/RUNNING 时为 1，否则为 NULL；
-- 配合唯一索引 (chapter_id, active_slot)，在数据库层面保证
-- “同一章节同一时间最多只有一个活动任务”（防重复提交的最终兜底）。
CREATE TABLE IF NOT EXISTS generation_task (
    id               BIGINT      NOT NULL COMMENT '主键（雪花ID）',
    chapter_id       BIGINT      NOT NULL COMMENT '目标章节',
    task_type        VARCHAR(30) NOT NULL COMMENT '类型：PLOT_PLAN/CONTENT/CONTINUE/REWRITE/EXPAND_SHRINK/CHAPTER_MEMORY',
    status           VARCHAR(20) NOT NULL DEFAULT 'CREATED' COMMENT '状态：CREATED/RUNNING/SUCCEEDED/FAILED/CANCELLED',
    input_snapshot   LONGTEXT COMMENT '输入快照（JSON，不含API Key）',
    context_snapshot LONGTEXT COMMENT '上下文快照（JSON，不含API Key）',
    result_snapshot  LONGTEXT COMMENT '结果快照（剧情方案文本等）',
    error_message    VARCHAR(1000)        DEFAULT NULL COMMENT '失败原因',
    active_slot      TINYINT GENERATED ALWAYS AS (IF(status IN ('CREATED', 'RUNNING'), 1, NULL)) STORED,
    created_at       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at       DATETIME             DEFAULT NULL,
    finished_at      DATETIME             DEFAULT NULL,
    updated_at       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_chapter_active (chapter_id, active_slot),
    KEY idx_task_chapter (chapter_id),
    KEY idx_task_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'AI生成任务';

-- 8. 章节版本（不可变快照，只插入不更新）
CREATE TABLE IF NOT EXISTS chapter_version (
    id              BIGINT      NOT NULL COMMENT '主键（雪花ID）',
    chapter_id      BIGINT      NOT NULL COMMENT '所属章节',
    version_no      INT         NOT NULL COMMENT '版本号（章节内递增）',
    content         LONGTEXT    NOT NULL COMMENT '正文快照',
    source_task_id  BIGINT               DEFAULT NULL COMMENT '来源生成任务',
    source          VARCHAR(20) NOT NULL COMMENT '来源：AI/MANUAL/ROLLBACK',
    remark          VARCHAR(500)         DEFAULT NULL COMMENT '备注',
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_version_chapter_no (chapter_id, version_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '章节版本';
