-- ============================================================
-- 数据库初始化脚本
--
-- 【执行时机】
--   只在这个目录被 pgvector 镜像首次初始化时执行一次
--   （即 postgres_data 这个数据卷为空的时候）。
--   之后再 docker compose up 不会重复执行。
--
--   所以：这里的语句必须写成「幂等」的（重复执行也不报错），
--   用 IF NOT EXISTS 就是这个目的。
--
--   如果想改这里的脚本后重新执行：
--       docker compose down -v    ← 删掉数据卷
--       docker compose up -d
--   ⚠️ down -v 会连数据一起删掉，阶段 1 灌了种子数据后慎用。
-- ============================================================

-- ------------------------------------------------------------
-- 启用 pgvector 扩展
--
-- 这是整个项目能用向量检索的前提。
-- 没有它，建表时 vector(1024) 这个类型会直接报
--   ERROR: type "vector" does not exist
--
-- 这也是阶段 0 验收标准第 4 条要求手工验证的命令：
--   docker compose exec postgres psql -U xbla -d xbla_rag \
--     -c "CREATE EXTENSION IF NOT EXISTS vector;"
-- 写在这里之后，容器首次启动就自动装好了，不用手工执行。
-- ------------------------------------------------------------
CREATE EXTENSION IF NOT EXISTS vector;

-- ------------------------------------------------------------
-- 打印扩展版本，方便启动日志里直接确认装成功了
-- ------------------------------------------------------------
DO $$
BEGIN
    RAISE NOTICE '========================================';
    RAISE NOTICE 'pgvector 扩展已启用，版本: %',
        (SELECT extversion FROM pg_extension WHERE extname = 'vector');
    RAISE NOTICE '数据库: %', current_database();
    RAISE NOTICE '当前用户: %', current_user;
    RAISE NOTICE '========================================';
END
$$;
