-- ============================================================================
-- B5 · 冲正审批意见 (阶段二) · reversal_suggestion 增审批留痕列
-- 可移植: MySQL 8 / PostgreSQL / H2(MySQL 兼容模式) —— ADD COLUMN VARCHAR NULL 三方言语法一致。
-- 审批(通过/驳回)时经 ReversalDecisionSink 落地 decision_note(与 status/operator 同事务写回)。
-- ADR-7: 只加列存审批留痕,不删不动金额/身份; B3 执行经 COALESCE 不抹此列(见 JdbcReversalSuggestionStore)。
-- ============================================================================
ALTER TABLE reversal_suggestion ADD COLUMN decision_note VARCHAR(512) NULL;
