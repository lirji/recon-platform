-- ============================================================================
-- 遗留② 修复 (PostgreSQL 方言): pin recon_record.match_key 列 collation = "C" (字节序)。
-- 由 Flyway {vendor}=postgresql 目录装配 (仅 PG 库执行)。
--
-- 目的: PG 默认 collation 随 DB 的 LC_COLLATE (常为 locale 敏感, 如 en_US.UTF-8), 其排序序与 Java
-- MatchKey.compareTo (UTF-16 码元序) 发散。改列 collation 为 "C" (纯字节/码点序) 后与 Java 对齐 (BMP 内),
-- per-bucket 游标 `ORDER BY (match_key IS NULL), match_key` 经 idx_merge 前缀访问 (#8: 可移植 NULL 排序)。
-- 诚实边界 (不过度承诺, #2/隐患①):
--   * PG 是 <b>no-pad</b> 比较 ('K1' ≠ 'K1 '), 与 Java 一致、与 MySQL(PAD SPACE) 不同 —— 尾随空白已在标准化处
--     (StandardizeProcessor + KeyNormalizer) 统一 trim, 三库口径一致, 不靠本迁移;
--   * 码点/字节序仅 BMP 内与 Java UTF-16 一致, 星平面极端键序微差 (MVP ASCII/BMP 业务号不受影响)。
-- ============================================================================

ALTER TABLE recon_record
  ALTER COLUMN match_key TYPE VARCHAR(128) COLLATE "C";
