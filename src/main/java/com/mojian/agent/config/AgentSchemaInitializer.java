package com.mojian.agent.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Agent 相关表的幂等初始化。
 *
 * <p>沿用墨笺既有做法（{@code CharacterService.migrateSchema()}、{@code LlmSettingsService.ensureTable()}）：
 * 启动时用 {@code CREATE TABLE IF NOT EXISTS} / 补列检查把库结构追到最新，不需要手动重跑 schema.sql。
 * 好处是任何历史版本的库都能直接启动，坏处是 SQL 散在 Java 里 —— 这是项目既有取舍，这里保持一致。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentSchemaInitializer {

    /**
     * 主 / 副 Agent 各自的模型配置。
     *
     * <p>设计要点：
     * <ul>
     *   <li>固定两行（id=1 PLANNER、id=2 WRITER），像 llm_settings 一样是单例配置</li>
     *   <li><b>模型按角色分开放</b>：策划要稳、要便宜；写作要发散、要文笔</li>
     *   <li>base_url / api_key 留空表示复用 llm_settings —— 同一家服务商只需配一次密钥</li>
     *   <li>context_limit 会被"超限学习"自动更新（见 ContextLimitParser）</li>
     * </ul>
     */
    private static final String AGENT_PROFILE_DDL = """
            CREATE TABLE IF NOT EXISTS agent_profile (
                id                    BIGINT       NOT NULL COMMENT '固定：1=策划 2=写作',
                role                  VARCHAR(20)  NOT NULL COMMENT 'PLANNER / WRITER',
                model                 VARCHAR(100) NOT NULL COMMENT '该角色使用的模型',
                temperature           DECIMAL(3,2) NOT NULL DEFAULT 0.80,
                max_steps             INT          NOT NULL DEFAULT 10 COMMENT '最大步数（第一道刹车）',
                context_limit         INT          NOT NULL DEFAULT 0 COMMENT '上下文上限；0=未知（查实测对照表），撞墙后自动学习',
                reserve_output_tokens INT          NOT NULL DEFAULT 8192 COMMENT '输出与固定开销预留',
                base_url              VARCHAR(300) DEFAULT NULL COMMENT '留空则复用 llm_settings',
                api_key_cipher        VARCHAR(600) DEFAULT NULL COMMENT '留空则复用 llm_settings',
                enabled               TINYINT      NOT NULL DEFAULT 1,
                created_at            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                PRIMARY KEY (id),
                UNIQUE KEY uk_agent_profile_role (role)
            ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'Agent 角色配置'
            """;

    /** 一次策划对话 / 一次写作任务 = 一个会话 */
    private static final String AGENT_CONVERSATION_DDL = """
            CREATE TABLE IF NOT EXISTS agent_conversation (
                id         BIGINT       NOT NULL,
                project_id BIGINT       NOT NULL COMMENT '所属项目',
                agent_role VARCHAR(20)  NOT NULL COMMENT 'PLANNER / WRITER',
                title      VARCHAR(200) DEFAULT NULL COMMENT '会话标题（取首条用户消息前若干字）',
                created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                PRIMARY KEY (id),
                KEY idx_agent_conv_project (project_id, agent_role)
            ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'Agent 会话'
            """;

    /**
     * 会话里的每条消息 —— 这就是那个"会一轮轮长大的包裹"的持久化形态。
     *
     * <p>为什么要落库而不是留在内存：写正文是跨请求的（可能几分钟、可能重启服务），
     * 而且用户要能翻回历史对话看 Agent 当时查了什么、怎么想的。
     */
    private static final String AGENT_MESSAGE_LOG_DDL = """
            CREATE TABLE IF NOT EXISTS agent_message_log (
                id            BIGINT      NOT NULL,
                conversation_id BIGINT    NOT NULL,
                step_no         INT         NOT NULL DEFAULT 0 COMMENT '第几轮（同一步内的多条消息共享）',
                role            VARCHAR(20) NOT NULL COMMENT 'system/user/assistant/tool',
                content         MEDIUMTEXT  DEFAULT NULL COMMENT 'system/user/assistant 的文本；tool 行放工具结果',
                tool_call_id    VARCHAR(64) DEFAULT NULL COMMENT '仅 tool 行：对应哪次调用，模型靠它对号',
                tool_calls_json MEDIUMTEXT  DEFAULT NULL COMMENT '仅 assistant 行：本轮的工具调用意图数组',
                created_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                PRIMARY KEY (id),
                KEY idx_agent_msg_conv (conversation_id, id)
            ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'Agent 消息'
            """;

    /** 主 Agent 给出的剧情走向方案（前端渲染成方案卡，用户选一条） */
    private static final String AGENT_PLAN_OPTION_DDL = """
            CREATE TABLE IF NOT EXISTS agent_plan_option (
                id              BIGINT       NOT NULL,
                conversation_id BIGINT       NOT NULL,
                message_id      BIGINT       DEFAULT NULL COMMENT '产出这批方案的那条 assistant 消息',
                option_index    INT          NOT NULL COMMENT '第几个方案，从 1 开始',
                title           VARCHAR(200) DEFAULT NULL COMMENT '方案名',
                summary         VARCHAR(500) DEFAULT NULL COMMENT '一句话简介（卡片正面）',
                detail          TEXT         DEFAULT NULL COMMENT '详细走向（展开看）',
                selected        TINYINT      NOT NULL DEFAULT 0 COMMENT '用户选了哪个',
                created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                PRIMARY KEY (id),
                KEY idx_agent_option_conv (conversation_id)
            ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'Agent 剧情方案'
            """;

    /**
     * Agent 起草的变更草案 —— 人工确认机制的载体。
     *
     * <p>为什么要有这张独立表：Agent 循环是"一口气跑完"的，没有暂停/恢复，
     * 所以"等用户确认再执行"不能靠循环停住。做法是让工具把产出写进这张表，
     * 用户点确认后才由确认接口写进业务表 —— <b>没确认的东西绝不污染业务数据</b>。
     */
    private static final String AGENT_DRAFT_DDL = """
            CREATE TABLE IF NOT EXISTS agent_draft (
                id              BIGINT       NOT NULL,
                conversation_id BIGINT       NOT NULL,
                project_id      BIGINT       NOT NULL,
                draft_type      VARCHAR(20)  NOT NULL COMMENT 'CHARACTER / SETTING / BLUEPRINT',
                status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / ACCEPTED / REJECTED',
                title           VARCHAR(200) NOT NULL COMMENT '展示用标题',
                payload_json    MEDIUMTEXT   NOT NULL COMMENT '草稿内容（JSON，按类型字段不同）',
                target_id       BIGINT       DEFAULT NULL COMMENT '要修改的对象 id；为空表示新增',
                agent_reason    TEXT         DEFAULT NULL COMMENT 'Agent 为什么起草它',
                applied_id      BIGINT       DEFAULT NULL COMMENT '确认后写入的业务记录 id',
                created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                PRIMARY KEY (id),
                KEY idx_agent_draft_conv (conversation_id, status),
                KEY idx_agent_draft_project (project_id)
            ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'Agent 变更草案'
            """;

    /**
     * 模型调用用量流水 —— 让花销可见。
     *
     * <p>为什么要有：额度是硬上限，用完之后服务直接不可用。没有流水，
     * "这个月花了多少、哪次调用花的"只能靠猜。有了它，界面上能显示实时消费，
     * 开发期也能确认成本有没有失控。
     */
    private static final String AGENT_USAGE_LOG_DDL = """
            CREATE TABLE IF NOT EXISTS agent_usage_log (
                id                BIGINT        NOT NULL,
                project_id        BIGINT        DEFAULT NULL,
                conversation_id   BIGINT        DEFAULT NULL,
                agent_role        VARCHAR(20)   NOT NULL,
                model             VARCHAR(100)  NOT NULL,
                prompt_tokens     INT           NOT NULL DEFAULT 0,
                cached_tokens     INT           NOT NULL DEFAULT 0 COMMENT '命中 prompt 缓存的部分',
                completion_tokens INT           NOT NULL DEFAULT 0,
                estimated_cost    DECIMAL(12,6) NOT NULL DEFAULT 0 COMMENT '估算费用（美元）',
                created_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                PRIMARY KEY (id),
                KEY idx_agent_usage_created (created_at)
            ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'Agent 用量流水'
            """;

    /** 默认配置：策划用便宜快模型求稳，写作先用同一个模型（成本优先，后续可用评测数据换） */
    private static final String SEED_PROFILES = """
            INSERT INTO agent_profile (id, role, model, temperature, max_steps, context_limit, reserve_output_tokens)
            SELECT 1, 'PLANNER', 'deepseek-v4.1-flash', 0.60, 10, 0, 8192
            WHERE NOT EXISTS (SELECT 1 FROM agent_profile WHERE role = 'PLANNER')
            """;

    private static final String SEED_WRITER = """
            INSERT INTO agent_profile (id, role, model, temperature, max_steps, context_limit, reserve_output_tokens)
            SELECT 2, 'WRITER', 'deepseek-v4.1-flash', 0.85, 6, 0, 16384
            WHERE NOT EXISTS (SELECT 1 FROM agent_profile WHERE role = 'WRITER')
            """;

    /**
     * 把"旧默认值"当作"未知"。
     *
     * <p>曾经 {@code context_limit} 默认写 131072，结果造成一个死锁：预算被锁在 131K，
     * 系统永远发不出超限请求，也就永远学不到真实的 1M —— 界面上真实占用 8.6 万却显示 79%，
     * 还触发了"该启用检索"的误报警。现在 0 表示未知，取值改为"存储值 → 实测对照表 → 保守默认"。
     *
     * <p>把 131072 重置为 0 是安全的：即使某个模型真的就是 128K，查表兜底也会给出同样的值。
     */
    private static final String MIGRATE_CONTEXT_LIMIT = """
            UPDATE agent_profile SET context_limit = 0 WHERE context_limit = 131072
            """;

    /**
     * 策划 → 写作 的指令通道。
     *
     * <p>写作 Agent 有两个指令来源：作者与策划 Agent。策划通过工具下发的提示词
     * 先落这张表，写作任务开始时读取；成功写进正文后标记 CONSUMED。
     */
    private static final String WRITER_INSTRUCTION_DDL = """
            CREATE TABLE IF NOT EXISTS writer_instruction (
                id          BIGINT       NOT NULL,
                project_id  BIGINT       NOT NULL,
                chapter_id  BIGINT       DEFAULT NULL,
                content     TEXT         NOT NULL COMMENT '策划写的提示词',
                reason      VARCHAR(500) DEFAULT NULL COMMENT '为什么下这条指令',
                source      VARCHAR(20)  NOT NULL DEFAULT 'PLANNER',
                status      VARCHAR(20)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / CONSUMED',
                created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                PRIMARY KEY (id),
                KEY idx_writer_instruction_project (project_id, status, created_at),
                KEY idx_writer_instruction_chapter (chapter_id, status)
            ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '策划给写作的指令'
            """;

    /**
     * 章节补充要求：策划审稿后追加给写作 Agent 的修正意见，与 writingRequirement 分开。
     */
    private static final String CHAPTER_SUPPLEMENT_DDL = """
            CREATE TABLE IF NOT EXISTS chapter_supplement (
                id            BIGINT       NOT NULL,
                project_id    BIGINT       NOT NULL,
                chapter_id    BIGINT       NOT NULL,
                content       TEXT         NOT NULL COMMENT '具体修改要求',
                reason        VARCHAR(1000) DEFAULT NULL COMMENT '针对什么问题',
                source        VARCHAR(20)  NOT NULL DEFAULT 'PLANNER',
                status        VARCHAR(20)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / EXECUTING / APPLIED',
                executing_task_id BIGINT   DEFAULT NULL COMMENT '单独执行时的写作任务 id',
                created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                PRIMARY KEY (id),
                KEY idx_chapter_supplement_chapter (chapter_id, status, created_at)
            ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '策划审稿后的补充要求'
            """;

    /**
     * 写作 Agent 的章节删除申请。
     *
     * <p>默认必须由作者确认；作者在设置里显式开启"自动批准删除"时才允许当场删除。
     */
    private static final String CHAPTER_DELETE_REQUEST_DDL = """
            CREATE TABLE IF NOT EXISTS chapter_delete_request (
                id             BIGINT       NOT NULL,
                project_id     BIGINT       NOT NULL,
                conversation_id BIGINT      DEFAULT NULL,
                chapter_id     BIGINT       NOT NULL,
                chapter_title  VARCHAR(255) DEFAULT NULL,
                reason         VARCHAR(1000) DEFAULT NULL,
                status         VARCHAR(20)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / APPROVED / REJECTED',
                source         VARCHAR(20)  NOT NULL DEFAULT 'WRITER',
                created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                PRIMARY KEY (id),
                KEY idx_chapter_delete_project (project_id, status, created_at),
                KEY idx_chapter_delete_chapter (chapter_id)
            ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '章节删除申请'
            """;

    /**
     * 策划 Agent 的实体删除申请。
     *
     * <p>人物/设定/蓝图删除不能直接执行：工具只登记申请，作者确认后才真正删除。
     */
    private static final String AGENT_DELETE_REQUEST_DDL = """
            CREATE TABLE IF NOT EXISTS agent_delete_request (
                id              BIGINT       NOT NULL,
                project_id      BIGINT       NOT NULL,
                conversation_id BIGINT       DEFAULT NULL,
                entity_type     VARCHAR(20)  NOT NULL COMMENT 'CHARACTER / SETTING / OUTLINE',
                entity_id       BIGINT       NOT NULL,
                entity_name     VARCHAR(255) DEFAULT NULL,
                reason          VARCHAR(1000) DEFAULT NULL,
                status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / APPROVED / REJECTED',
                source          VARCHAR(20)  NOT NULL DEFAULT 'PLANNER',
                created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                PRIMARY KEY (id),
                KEY idx_agent_delete_project (project_id, status, created_at),
                KEY idx_agent_delete_entity (entity_type, entity_id)
            ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '策划实体删除申请'
            """;

    /**
     * 对话回滚时的数据库撤销日志：记录草稿确认对业务表造成的改动。
     */
    private static final String AGENT_UNDO_LOG_DDL = """
            CREATE TABLE IF NOT EXISTS agent_undo_log (
                id              BIGINT       NOT NULL,
                project_id      BIGINT       NOT NULL,
                conversation_id BIGINT       NOT NULL,
                turn_index      INT          NOT NULL DEFAULT 0 COMMENT '改动时该会话已有多少条 user 消息',
                action_type     VARCHAR(20)  NOT NULL COMMENT 'CREATED / UPDATED',
                entity_type     VARCHAR(20)  NOT NULL COMMENT 'CHARACTER / SETTING / OUTLINE',
                entity_id       BIGINT       NOT NULL,
                draft_id        BIGINT       DEFAULT NULL,
                before_json     LONGTEXT     DEFAULT NULL COMMENT 'UPDATED 时的旧值',
                created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                PRIMARY KEY (id),
                KEY idx_agent_undo_conversation (conversation_id, turn_index, id)
            ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '对话回滚的数据库撤销日志'
            """;

    /**
     * Agent 运行轨迹 —— 每次运行一行。
     *
     * <p>会话消息只存"人话"；工具轨迹、步数、耗时、上下文报表都在这张表里。
     * 它是排查"为什么没查资料 / 为什么这么慢"与做评测的数据底座。
     */
    private static final String AGENT_RUN_DDL = """
            CREATE TABLE IF NOT EXISTS agent_run (
                id                BIGINT       NOT NULL,
                project_id        BIGINT       DEFAULT NULL,
                conversation_id   BIGINT       DEFAULT NULL,
                agent_role        VARCHAR(20)  NOT NULL COMMENT 'PLANNER / WRITER',
                model             VARCHAR(100) DEFAULT NULL,
                status            VARCHAR(20)  NOT NULL COMMENT 'FINISHED / MAX_STEPS / CANCELLED / FAILED',
                steps             INT          NOT NULL DEFAULT 0,
                prompt_tokens     INT          NOT NULL DEFAULT 0,
                cached_tokens     INT          NOT NULL DEFAULT 0,
                completion_tokens INT          NOT NULL DEFAULT 0,
                latency_ms        BIGINT       NOT NULL DEFAULT 0,
                context_summary   TEXT         DEFAULT NULL,
                trace_json        MEDIUMTEXT   DEFAULT NULL,
                error_message     VARCHAR(1000) DEFAULT NULL,
                created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                PRIMARY KEY (id),
                KEY idx_agent_run_project (project_id, created_at),
                KEY idx_agent_run_role (agent_role, created_at)
            ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'Agent 运行轨迹'
            """;

    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void init() {
        jdbcTemplate.execute(AGENT_PROFILE_DDL);
        jdbcTemplate.execute(AGENT_CONVERSATION_DDL);
        jdbcTemplate.execute(AGENT_MESSAGE_LOG_DDL);
        jdbcTemplate.execute(AGENT_PLAN_OPTION_DDL);
        jdbcTemplate.execute(AGENT_USAGE_LOG_DDL);
        jdbcTemplate.execute(AGENT_DRAFT_DDL);
        jdbcTemplate.execute(AGENT_RUN_DDL);
        jdbcTemplate.execute(WRITER_INSTRUCTION_DDL);
        jdbcTemplate.execute(AGENT_UNDO_LOG_DDL);
        jdbcTemplate.execute(CHAPTER_DELETE_REQUEST_DDL);
        jdbcTemplate.execute(AGENT_DELETE_REQUEST_DDL);
        jdbcTemplate.execute(CHAPTER_SUPPLEMENT_DDL);
        addColumnIfMissing("chapter_supplement", "executing_task_id",
                "BIGINT DEFAULT NULL COMMENT '单独执行时的写作任务 id' AFTER status");

        // 章节写作要求：除了"写什么"（蓝图），还要能表达"怎么写"
        // ——风格、人称、篇幅、禁忌、必须出现/必须避开的桥段
        addColumnIfMissing("chapter", "writing_requirement",
                "TEXT DEFAULT NULL COMMENT '本章写作要求：风格/人称/篇幅/禁忌等'");

        // 消息表结构修正：工具调用改为存数组（一轮可以要求调多个工具）
        addColumnIfMissing("agent_message_log", "tool_calls_json",
                "MEDIUMTEXT DEFAULT NULL COMMENT '仅 assistant 行：本轮的工具调用意图数组' AFTER tool_call_id");
        dropColumnIfExists("agent_message_log", "tool_name");
        dropColumnIfExists("agent_message_log", "tool_arguments");
        dropColumnIfExists("agent_message_log", "tool_result");

        // 草稿的「修改」语义：此前草稿只会新增，现在也要支持"改已有的东西"
        // （例如按用户的划词引用修改某个蓝图节点）。target_id 为空=新增，非空=修改它。
        addColumnIfMissing("agent_draft", "target_id",
                "BIGINT DEFAULT NULL COMMENT '要修改的对象 id；为空表示新增' AFTER payload_json");

        /*
         * 思考强度。
         *
         * 值域各家不一样（有的 none/low/medium/high，有的 xhigh/max），
         * 所以只存字符串、不在库里约束 —— 真正的可选值由模型目录告诉界面。
         * 空表示"不传这个字段"，由服务商自己用默认值。
         */
        addColumnIfMissing("agent_profile", "reasoning_effort",
                "VARCHAR(20) DEFAULT NULL COMMENT '思考强度；空=不传，用服务商默认' AFTER temperature");

        jdbcTemplate.execute(SEED_PROFILES);
        jdbcTemplate.execute(SEED_WRITER);
        jdbcTemplate.execute(MIGRATE_CONTEXT_LIMIT);

        log.info("Agent 表结构初始化完成（agent_profile / conversation / message_log / plan_option / usage_log）");
    }

    private void addColumnIfMissing(String table, String column, String definition) {
        if (columnExists(table, column)) {
            return;
        }
        jdbcTemplate.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        log.info("{}.{} 已自动补列", table, column);
    }

    /** MySQL 不支持 DROP COLUMN IF EXISTS，只能先查再删 */
    private void dropColumnIfExists(String table, String column) {
        if (!columnExists(table, column)) {
            return;
        }
        jdbcTemplate.execute("ALTER TABLE " + table + " DROP COLUMN " + column);
        log.info("{}.{} 已删除（结构修正）", table, column);
    }

    private boolean columnExists(String table, String column) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                Integer.class, table, column);
        return count != null && count > 0;
    }
}
