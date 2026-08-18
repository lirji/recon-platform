# M6 Code Review Report

## 结论

- M6 本地交付范围通过审查，无未解决的高严重度问题。
- CSV 模块保持外圈边界，DB 默认路径兼容，三方场景不依赖具体数据源实现。
- 生产资格仍取决于 CI/有 Docker 环境的 MySQL 8、PostgreSQL 方言测试，以及真实输入文件质量门禁。

## 已解决发现

| 严重度 | 发现 | 修复 | 回归证据 |
|---|---|---|---|
| Medium | 多个 `SourceAdapter` 直接注册会令现有单 Bean 注入歧义，也可能由容器静默接错扩展实现 | 新增 `RoutingSourceAdapter`，按 sourceType 唯一路由并在重复/未知类型时 fail-fast；注册为 `@Primary` | `RoutingSourceAdapterTest` + 全量 Spring 上下文测试 |
| Medium | 旧重跑清理不包含 `recon_record_reject`，CSV 同一坏行会在每次重跑重复累积 | `JdbcRecordRejectStore.deleteByRunBounded` 接入 `ReconRerunService`，仍使用每批 `REQUIRES_NEW` | `MarketingThreeWayCsvEndToEndTest.csvRerunIsIdempotent...` |
| Medium | Java 默认字符集 decoder 会以替代字符吞掉非法字节，可能让坏文件静默进入键/金额路径 | decoder 改为 malformed/unmappable `REPORT`；BOM 与显式 charset 冲突 fail-fast | `CsvSourceAdapterTest` 编码/BOM/语法测试 |
| Medium | reject 原因可能超过 DDL `VARCHAR(128)`，使“坏行不中断”反而在落 reject 时失败 | reject 持久化统一截断 reason 到 schema 上限，payload 保留完整解析视图 | 目标测试 + H2 全链路 |
| Low | CSV recordId 冗余拼接源 id，增加 512 长度风险；文件绝对路径也可能超过 raw_ref 256 | recordId 只使用 run/segment/side/rawRef；路径超过安全上限时打开即报清晰错误 | CSV 单测 + clean package |

## 边界确认

- 语义坏行可逐行 reject 并继续；未闭合引号/非法编码等失去记录边界后只安全终止当前文件，未伪装成可恢复。
- CSV 标准记录以流式 parser 拉取，不 materialize 全文件；reject 列表峰值受当前 `RecordCursor` SPI 限制，已记入 KI-7。
- 三方账务 CSV 在两个 segment 各读取一次，描述符投影不同键；recordId 含 segment/side，不碰撞，rawRef 保持同一文件行血缘。
- `recon-source-csv` 只依赖 core + Apache Commons CSV；Spring/JDBC/Batch 不泄漏进模块。

## 残余风险

- 本机 Docker 不可用，MySQL/PostgreSQL Testcontainers 命令按测试守卫跳过；workflow 已显式执行该命令。
- KI-1 的 off-by-default sub-bucket restart 配置漂移、KI-6 的跨源 refine 脏数据与 KI-7 的 CSV 边界仍按文档约束处理。
- 未进行容量/性能压测、生产文件传输、鉴权、真实告警或部署，均不在本轮批准范围。
