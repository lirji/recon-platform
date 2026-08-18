-- ============================================================================
-- 遗留② 修复 (MySQL 方言): pin recon_record.match_key 列 collation = utf8mb4_bin。
-- 由 Flyway {vendor}=mysql 目录装配 (仅 MySQL 库执行)。
--
-- 目的: MySQL 默认 collation (utf8mb4_0900_ai_ci) 大小写/重音不敏感, 其排序序与 Java
-- MatchKey.compareTo (UTF-16 码元二进制序) 发散, 会让 per-bucket sort-merge 游标把本应配对的键拆成
-- 假 MISSING/EXTRA。pin 为 utf8mb4_bin (码点/二进制序) 后, 索引 idx_merge 以二进制序建立, 与 Java 对齐,
-- 且 per-bucket 游标 `ORDER BY (match_key IS NULL), match_key` 经 idx_merge 前缀 ref 访问 (#8: 可移植 NULL 排序)。
-- (ALTER MODIFY 改列 collation 会让 MySQL 自动按新序重建含该列的 idx_merge。)
-- 诚实边界 (不过度承诺, #2/隐患①):
--   * utf8mb4_bin 是 <b>PAD SPACE</b>: 'K1' 与 'K1 ' 视为相等, 与 Java/PG(no-pad) 发散 —— 已在标准化处
--     (StandardizeProcessor + KeyNormalizer) 对入库键 trim 尾随空白消除 PAD SPACE 差异, 不靠本迁移;
--   * 码点序仅 BMP 内与 Java UTF-16 对齐, 星平面 (surrogate pair) 键序仍可能微差 (MVP ASCII/BMP 业务号不受影响)。
-- ============================================================================

ALTER TABLE recon_record
  MODIFY match_key VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL;
