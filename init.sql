-- ============================================================
-- 墨笺 AI 长篇小说创作工作台 - 数据库初始化脚本
-- 执行方式（任选其一）：
--   命令行: mysql -u root -p < init.sql
--   IDEA Database 面板或 MySQL 客户端中直接执行本文件
-- ============================================================

CREATE DATABASE IF NOT EXISTS mojian
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE mojian;

-- 数据表将在后续里程碑中通过 schema 脚本加入：
-- story_project / character_profile / setting_entry / plot_outline
-- chapter / chapter_memory / generation_task / chapter_version
