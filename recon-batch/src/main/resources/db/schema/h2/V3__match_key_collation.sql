-- ============================================================================
-- 遗留② 修复 (H2 方言, 测试库): NO-OP。
-- 由 Flyway {vendor}=h2 目录装配 (仅 H2 库执行)。
--
-- H2 不支持列级 CHARACTER SET / COLLATE utf8mb4_bin 语法, 但 H2 默认字符串比较即按 <b>Unicode 码点序</b>
-- (且 no-pad, 'K1' ≠ 'K1 '), BMP 内与 Java MatchKey.compareTo (UTF-16 码元序) 已一致 —— per-bucket 游标
-- `ORDER BY (match_key IS NULL), match_key` 天然与 Java 归并对齐, 无需额外 pin。故本迁移仅占位 (保持 H2 与
-- MySQL/PG 的版本历史平行), 不执行任何 DDL。
--
-- 诚实边界: 尾随空白差异 (MySQL PAD SPACE) 已在标准化处 (StandardizeProcessor + KeyNormalizer) 统一 trim,
-- 与本 H2 no-op 正交; 真库 (MySQL8/PG) collation 效果由 CollationRealDbIT (Testcontainers, 无 Docker 时优雅跳过)
-- 验证, H2 侧由 CollationOrderRegressionTest 覆盖排序序 + idx_merge 访问。
-- ============================================================================

-- (intentionally empty: H2 code-point ordering already matches Java UTF-16 within the BMP)
SELECT 1;
