-- ============================================================
-- 墨笺 AI 长篇小说创作工作台 - 数据库初始化脚本
-- 执行方式（任选其一）：
--   命令行: mysql -u root -p < init.sql
--           再执行: mysql -u root -p mojian < src/main/resources/schema.sql
--   IDEA Database 面板或 MySQL 客户端中依次执行 init.sql 与 schema.sql
-- ============================================================

CREATE DATABASE IF NOT EXISTS mojian
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;
