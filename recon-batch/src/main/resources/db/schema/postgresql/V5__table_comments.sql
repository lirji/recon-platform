-- ============================================================================
-- V5 · 表/列注释 (PostgreSQL 方言)。由 Flyway {vendor}=postgresql 目录装配 (仅 PG 库执行)。
-- PG/H2 用标准 COMMENT ON (无需重述列定义, 天然只加注释不动结构); MySQL 版见 db/schema/mysql/V5。
-- 注释文本不含 ASCII 单引号, 三方言共用同一批文案。语义来源: 设计 §5 + V1/V4 源码行内注解。
-- ============================================================================

COMMENT ON TABLE  recon_run                   IS '对账执行实例: 唯一键(场景+账期+序号) + cutoff + T~T+1 匹配窗口 + revision 乐观锁';
COMMENT ON COLUMN recon_run.run_id            IS '运行主键 (UUID)';
COMMENT ON COLUMN recon_run.scenario_code     IS '场景码';
COMMENT ON COLUMN recon_run.accounting_period IS '日账期 YYYY-MM-DD (A5)';
COMMENT ON COLUMN recon_run.sequence_no       IS '同场景同账期内的重跑序号 (串行分配, 修补⑦)';
COMMENT ON COLUMN recon_run.cutoff_time       IS '截止时间';
COMMENT ON COLUMN recon_run.match_window_from IS '匹配窗口起 T';
COMMENT ON COLUMN recon_run.match_window_to   IS '匹配窗口止 T+1';
COMMENT ON COLUMN recon_run.bucket_count      IS '分桶数 (1..4096)';
COMMENT ON COLUMN recon_run.status            IS '运行状态 (RUNNING/COMPLETED/FAILED 等)';
COMMENT ON COLUMN recon_run.revision          IS '乐观锁版本';
COMMENT ON COLUMN recon_run.created_at        IS '创建时间';
COMMENT ON COLUMN recon_run.updated_at        IS '更新时间';
COMMENT ON COLUMN recon_run.started_at        IS '开始时间';
COMMENT ON COLUMN recon_run.finished_at       IS '结束时间';

COMMENT ON TABLE  recon_run_seq                   IS '序号分配计数器: 按(场景,账期)串行化 sequence_no 分配 (修补⑦)';
COMMENT ON COLUMN recon_run_seq.scenario_code     IS '场景码';
COMMENT ON COLUMN recon_run_seq.accounting_period IS '日账期 YYYY-MM-DD';
COMMENT ON COLUMN recon_run_seq.next_seq          IS '下一个可用序号';

COMMENT ON TABLE  recon_record                     IS 'staging 标准化统一模型: 流式 sort-merge 勾兑入库源, 排序介质 idx_merge';
COMMENT ON COLUMN recon_record.record_id           IS '全局唯一键 run:segment:side:raw_ref (同源行被多 segment/side 读取不撞 PK)';
COMMENT ON COLUMN recon_record.run_id              IS '所属 run';
COMMENT ON COLUMN recon_record.segment_id          IS '段: SEG1_MKT_ACCT | SEG2_ACCT_CHANNEL';
COMMENT ON COLUMN recon_record.side                IS '侧: LEFT | RIGHT';
COMMENT ON COLUMN recon_record.source_role         IS '来源角色: MARKETING | ACCOUNTING | CHANNEL';
COMMENT ON COLUMN recon_record.match_key           IS 'sort-merge 勾兑键(可空); collation 由 V3 pin C(码点序)与 Java 码元序对齐; 尾随空白已标准化 trim';
COMMENT ON COLUMN recon_record.group_key           IS '分桶键(bucket 由它算); 不变式: match_key 必为其细分';
COMMENT ON COLUMN recon_record.bucket              IS 'floorMod(hash(group_key), bucket_count)';
COMMENT ON COLUMN recon_record.currency            IS '币种 ISO 4217 三字母';
COMMENT ON COLUMN recon_record.signed_amount_minor IS '带符号金额(分, 红蓝字), 禁 double';
COMMENT ON COLUMN recon_record.base_amount_minor   IS '本位币金额 (留位·只读·MVP 不比较)';
COMMENT ON COLUMN recon_record.fx_rate             IS '汇率 (留位·只读)';
COMMENT ON COLUMN recon_record.fx_rate_time        IS '汇率时间 (留位·只读)';
COMMENT ON COLUMN recon_record.fx_rate_source      IS '汇率来源 (留位·只读)';
COMMENT ON COLUMN recon_record.entry_type          IS '记账方向: ISSUE | REFUND | REVERSAL';
COMMENT ON COLUMN recon_record.biz_status          IS '业务状态';
COMMENT ON COLUMN recon_record.biz_time            IS '业务时间';
COMMENT ON COLUMN recon_record.posting_time        IS '记账时间';
COMMENT ON COLUMN recon_record.claimed_run_id      IS 'TIMING 跨 Run 认领的 run_id (修补⑧)';
COMMENT ON COLUMN recon_record.raw_ref             IS '血缘: file:line 或 table:pk';
COMMENT ON COLUMN recon_record.created_at          IS '创建时间';

COMMENT ON TABLE  recon_record_reject             IS '载入期业务畸形行 reject (逐行拒绝后继续); 不可恢复的语法/编码错误另记并终止当前文件';
COMMENT ON COLUMN recon_record_reject.id          IS '主键';
COMMENT ON COLUMN recon_record_reject.run_id      IS '所属 run (可空)';
COMMENT ON COLUMN recon_record_reject.segment_id  IS '段';
COMMENT ON COLUMN recon_record_reject.source_role IS '来源角色';
COMMENT ON COLUMN recon_record_reject.raw_ref     IS '血缘 file:line 或 table:pk';
COMMENT ON COLUMN recon_record_reject.reason      IS '拒绝原因';
COMMENT ON COLUMN recon_record_reject.raw_payload IS '原始行内容';
COMMENT ON COLUMN recon_record_reject.created_at  IS '创建时间';

COMMENT ON TABLE  discrepancy                       IS '机器判差结果: fingerprint 幂等(uk_disc 让空键类型也幂等); 重跑清 machine_result=1 的行 (修补②/ADR-7)';
COMMENT ON COLUMN discrepancy.discrepancy_id        IS 'run 内由 runId+fingerprint 派生的稳定 UUID';
COMMENT ON COLUMN discrepancy.run_id                IS '所属 run';
COMMENT ON COLUMN discrepancy.segment_id            IS '段';
COMMENT ON COLUMN discrepancy.type                  IS '差异类型 (MISSING/EXTRA/AMOUNT_MISMATCH/BRIDGE_BROKEN 等)';
COMMENT ON COLUMN discrepancy.bridge_break_stage    IS '桥断阶段 SEG1|SEG2 (仅 BRIDGE_BROKEN, 优先级高于 MISSING)';
COMMENT ON COLUMN discrepancy.fingerprint           IS 'SHA-256 canonical 业务身份 (null 键→∅)';
COMMENT ON COLUMN discrepancy.group_key             IS '分组键';
COMMENT ON COLUMN discrepancy.match_key             IS '勾兑键';
COMMENT ON COLUMN discrepancy.currency              IS '币种';
COMMENT ON COLUMN discrepancy.expected_amount_minor IS '期望额(分)';
COMMENT ON COLUMN discrepancy.actual_amount_minor   IS '实际额(分)';
COMMENT ON COLUMN discrepancy.delta_amount_minor    IS '差额(分)';
COMMENT ON COLUMN discrepancy.left_raw_ref          IS '左侧血缘';
COMMENT ON COLUMN discrepancy.right_raw_ref         IS '右侧血缘';
COMMENT ON COLUMN discrepancy.machine_result        IS '1=机器产物(重跑可清), 0=非机器';
COMMENT ON COLUMN discrepancy.created_at            IS '创建时间';
COMMENT ON COLUMN discrepancy.updated_at            IS '更新时间';

COMMENT ON TABLE  discrepancy_disposition                   IS '人工处置: 永不被重跑删除 (ADR-7); 一差一处置 (uk_disp)';
COMMENT ON COLUMN discrepancy_disposition.id                IS '主键';
COMMENT ON COLUMN discrepancy_disposition.fingerprint       IS '对应 discrepancy 的业务身份';
COMMENT ON COLUMN discrepancy_disposition.scenario_code     IS '场景码';
COMMENT ON COLUMN discrepancy_disposition.accounting_period IS '日账期';
COMMENT ON COLUMN discrepancy_disposition.segment_id        IS '段';
COMMENT ON COLUMN discrepancy_disposition.status            IS '处置状态: RESOLVED|CLOSED|SUPPRESSED|REOPENED';
COMMENT ON COLUMN discrepancy_disposition.operator          IS '处置人';
COMMENT ON COLUMN discrepancy_disposition.note              IS '备注';
COMMENT ON COLUMN discrepancy_disposition.last_seen_run_id  IS '最近一次出现该差异的 run';
COMMENT ON COLUMN discrepancy_disposition.version           IS '乐观锁版本';
COMMENT ON COLUMN discrepancy_disposition.created_at        IS '创建时间';
COMMENT ON COLUMN discrepancy_disposition.updated_at        IS '更新时间';

COMMENT ON TABLE  reversal_suggestion                        IS '冲正建议: 幂等键唯一, 永不被重跑删除, MVP 无资金动作 (ADR-7)';
COMMENT ON COLUMN reversal_suggestion.id                     IS '主键';
COMMENT ON COLUMN reversal_suggestion.fingerprint            IS '对应 discrepancy 的业务身份';
COMMENT ON COLUMN reversal_suggestion.run_id                 IS '所属 run';
COMMENT ON COLUMN reversal_suggestion.group_key              IS '分组键';
COMMENT ON COLUMN reversal_suggestion.suggested_amount_minor IS '建议冲正额(分)';
COMMENT ON COLUMN reversal_suggestion.currency               IS '币种';
COMMENT ON COLUMN reversal_suggestion.status                 IS '状态: SUGGESTED|CONFIRMED|DISCARDED';
COMMENT ON COLUMN reversal_suggestion.idempotency_key        IS '幂等键 (uk_rev)';
COMMENT ON COLUMN reversal_suggestion.operator               IS '操作人';
COMMENT ON COLUMN reversal_suggestion.created_at             IS '创建时间';

COMMENT ON TABLE  discrepancy_action                 IS '处置/处理动作审计 + 外部幂等 (uk_action)';
COMMENT ON COLUMN discrepancy_action.id              IS '主键';
COMMENT ON COLUMN discrepancy_action.fingerprint     IS '对应差异的业务身份';
COMMENT ON COLUMN discrepancy_action.action_type     IS '动作类型: LEDGER|REVERSAL_SUGGESTION|MANUAL_RESOLVE|MANUAL_CLOSE';
COMMENT ON COLUMN discrepancy_action.idempotency_key IS '幂等键 (uk_action)';
COMMENT ON COLUMN discrepancy_action.payload         IS '动作载荷';
COMMENT ON COLUMN discrepancy_action.operator        IS '操作人';
COMMENT ON COLUMN discrepancy_action.created_at      IS '创建时间';

COMMENT ON TABLE  alert_outbox                 IS '告警发件箱 (修补⑤/ADR-10): chunk 内只写 outbox, relay 以 REQUIRES_NEW 短事务外发置 SENT/FAILED';
COMMENT ON COLUMN alert_outbox.id              IS '主键';
COMMENT ON COLUMN alert_outbox.run_id          IS '所属 run';
COMMENT ON COLUMN alert_outbox.fingerprint     IS '关联差异的业务身份';
COMMENT ON COLUMN alert_outbox.payload         IS '告警载荷';
COMMENT ON COLUMN alert_outbox.status          IS '状态: PENDING|SENT|FAILED';
COMMENT ON COLUMN alert_outbox.attempt         IS '外发重试次数';
COMMENT ON COLUMN alert_outbox.idempotency_key IS '幂等键 (uk_outbox)';
COMMENT ON COLUMN alert_outbox.created_at      IS '创建时间';
COMMENT ON COLUMN alert_outbox.sent_at         IS '发送成功时间';

COMMENT ON TABLE  recon_report                          IS '勾稽报表: 按(segment,currency)双向守恒; left_residual/right_residual by-construction ≡ 0';
COMMENT ON COLUMN recon_report.report_id                IS '主键';
COMMENT ON COLUMN recon_report.run_id                   IS '所属 run';
COMMENT ON COLUMN recon_report.segment_id               IS '段';
COMMENT ON COLUMN recon_report.currency                 IS '币种 (跨币分桶不相加)';
COMMENT ON COLUMN recon_report.expected_total_minor     IS '左侧期望总额(分)';
COMMENT ON COLUMN recon_report.matched_amount_minor     IS '干净匹配左额(分)';
COMMENT ON COLUMN recon_report.amount_mismatch_minor    IS '金额不符桶(分)';
COMMENT ON COLUMN recon_report.missing_minor            IS '缺失桶(分)';
COMMENT ON COLUMN recon_report.duplicate_minor          IS '重复桶(分)';
COMMENT ON COLUMN recon_report.extra_minor              IS '多余桶(分)';
COMMENT ON COLUMN recon_report.timing_minor             IS '时间性差异桶(分)';
COMMENT ON COLUMN recon_report.status_mismatch_minor    IS '状态不符桶(分)';
COMMENT ON COLUMN recon_report.currency_mismatch_minor  IS '币种不符桶(分)';
COMMENT ON COLUMN recon_report.group_sum_mismatch_minor IS '分组合计不符桶(分)';
COMMENT ON COLUMN recon_report.bridge_broken_minor      IS '桥断桶(分)';
COMMENT ON COLUMN recon_report.right_side_total_minor   IS '右侧总额(分)';
COMMENT ON COLUMN recon_report.left_residual_minor      IS '左残差 (by-construction ≡ 0)';
COMMENT ON COLUMN recon_report.right_residual_minor     IS '右残差 (by-construction ≡ 0)';
COMMENT ON COLUMN recon_report.balanced                 IS '1=守恒, 0=不守恒';
COMMENT ON COLUMN recon_report.created_at               IS '创建时间';

COMMENT ON TABLE  recon_report_partial                             IS 'M3 单遍守恒: 每 partition(bucket) 流式累计的局部结果; 汇总步跨 bucket 合并复算 recon_report (机器产物, 可重跑清)';
COMMENT ON COLUMN recon_report_partial.id                          IS '主键';
COMMENT ON COLUMN recon_report_partial.run_id                      IS '所属 run';
COMMENT ON COLUMN recon_report_partial.segment_id                  IS '段';
COMMENT ON COLUMN recon_report_partial.bucket                      IS '分桶号';
COMMENT ON COLUMN recon_report_partial.sub_index                   IS '二级 sub-bucket 分片号 (未拆 = -1)';
COMMENT ON COLUMN recon_report_partial.currency                    IS '币种';
COMMENT ON COLUMN recon_report_partial.expected_total_minor        IS '左侧期望总额(分)';
COMMENT ON COLUMN recon_report_partial.right_side_total_minor      IS '右侧总额(分)';
COMMENT ON COLUMN recon_report_partial.matched_left_minor          IS '干净匹配左额(分)';
COMMENT ON COLUMN recon_report_partial.matched_right_minor         IS '干净匹配右额 + 有差组右额(分)';
COMMENT ON COLUMN recon_report_partial.missing_minor               IS '缺失(分)';
COMMENT ON COLUMN recon_report_partial.extra_minor                 IS '多余(分)';
COMMENT ON COLUMN recon_report_partial.amount_mismatch_left_minor  IS '金额不符左额(分)';
COMMENT ON COLUMN recon_report_partial.status_left_minor           IS '状态不符左额(分)';
COMMENT ON COLUMN recon_report_partial.timing_left_minor           IS '时间性差异左额(分)';
COMMENT ON COLUMN recon_report_partial.group_sum_left_minor        IS '分组合计不符左额(分)';
COMMENT ON COLUMN recon_report_partial.duplicate_left_minor        IS '重复左额(分)';
COMMENT ON COLUMN recon_report_partial.bridge_broken_left_minor    IS '桥断左额(分)';
COMMENT ON COLUMN recon_report_partial.bridge_broken_right_minor   IS '桥断右额(分)';
COMMENT ON COLUMN recon_report_partial.currency_mismatch_left_minor  IS '币种不符左额(分)';
COMMENT ON COLUMN recon_report_partial.currency_mismatch_right_minor IS '币种不符右额(分)';
COMMENT ON COLUMN recon_report_partial.created_at                  IS '创建时间';

COMMENT ON TABLE  recon_scenario_def                 IS 'B4 配置驱动场景定义: definition_json 经 GenericScenarioAssembler 校验后落库';
COMMENT ON COLUMN recon_scenario_def.code            IS '场景码 (发起 Run 按 code 装配)';
COMMENT ON COLUMN recon_scenario_def.version         IS '定义版本 (乐观并发/审计)';
COMMENT ON COLUMN recon_scenario_def.definition_json IS '声明式 ScenarioDefinition 的 JSON';
COMMENT ON COLUMN recon_scenario_def.enabled         IS '0=停用(不可发起), 1=启用';
COMMENT ON COLUMN recon_scenario_def.created_at      IS '创建时间';
COMMENT ON COLUMN recon_scenario_def.updated_at      IS '更新时间';
