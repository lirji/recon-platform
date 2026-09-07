# dev-infra 接入与数据迁移

recon-platform 的本地持久化数据库和消息中间件由同级 `dev-infra` 统一管理。项目 Compose 只运行 backend/console；后端同时加入项目默认网络和已有的 `dev-infra` 外部网络，不再创建项目私有 MySQL 或 Kafka。

## 资源清单

| 资源 | 宿主机连接 | Compose 内连接 | 隔离方式 |
|---|---|---|---|
| MySQL 8.4 | `127.0.0.1:43306/recon` | `infra-mysql84:3306/recon` | schema `recon` + 用户 `recon` |
| Flowable MySQL | `127.0.0.1:43306/recon_flowable` | `infra-mysql84:3306/recon_flowable` | 独立 schema，复用项目用户 |
| Kafka 3.8 | `127.0.0.1:49092` | `infra-kafka38:9092` | 项目前缀 Topic/consumer group |

Flowable 仅在叠加 `compose.flowable.yml` 时启用；它现在使用持久化 schema，不再因 backend 重启丢失待办。Redis、RabbitMQ、MinIO、Nacos 和 PostgreSQL 当前没有被项目运行路径使用，因此没有接入或新建。

## 初始化与启动

```bash
cp .env.example .env
# 设置仅本机保存的 RECON_DB_PASSWORD
./bootstrap-dev-infra.sh
./deploy.sh
```

初始化脚本会启动共享 MySQL/Kafka，幂等创建两个 schema、项目账号，以及以下 Topic：

- `benefit.fulfillment-event.v1`
- `benefit.remediation.command.v1`
- `benefit.remediation.result.v1`
- `marketing.award-expected.v1`

数据库凭据只保存在被 Git 忽略的 `.env`；共享 MySQL root 密码只保存在 `dev-infra/.env`。

## 2026-09-05 迁移记录

- 来源：`mysql:8.0.46` 容器 `recon-platform-mysql`，卷 `recon-platform-mysql-data`，schema `recon`。
- 目标：`mysql:8.4.11` 容器 `dev-infra-mysql84-1`，schema `recon`。
- 迁移前停止 backend 写入；使用一致性 `mysqldump` 导出后导入共享库。
- 导出文件为 `backups/recon-mysql80-20260905-220717.sql`（149,913 bytes，SHA-256 `5e2bc4e4716f5c7556d62dd91d5110e555de7e5f4309d0c85d2545d2fc9f5120`；Git 忽略），旧具名卷保留用于回滚。
- 导入时共有 25 张基础表和 1 个视图；25 张表逐表精确行数全部一致。
- 当前代码已在 MySQL 8.4 上成功执行 Flyway V7–V10，schema 达到 V10；readiness、控制台代理 API 和 153 项 Maven 测试均通过。
- 项目原先没有独立 Kafka 容器，相关消费者和 relay 默认关闭，因此没有旧 Kafka 消息需要复制。

## 回滚

旧卷不会被迁移和普通 `docker compose down` 删除。如需回滚，先停止 backend 防止双写，然后临时恢复旧版 `compose.mysql.yml` 的 MySQL 服务与 `jdbc:mysql://db:3306/recon` 连接；如果共享库已产生新写入，必须先比较并合并差异，不能直接用旧库覆盖。

不要对共享实例执行 `DROP DATABASE`、全库清理、删除其他项目 Topic 或 `docker compose down -v`。`./deploy.sh down --purge` 只处理本项目 Compose 的 H2 卷，不会清除共享 MySQL 数据。

## 统一链路追踪

先启动共享 Grafana + Tempo + OpenTelemetry 栈，再叠加项目观测配置启动后端：

```bash
cd ../dev-infra && make marketing-obs
cd ../recon-platform
./compose.sh -f compose.yml -f compose.mysql.yml -f compose.observability.yml up -d --build
```

`recon-platform` 使用共享只读 Java Agent 卷并把 trace 发送到 `infra-otel-collector:4318`；指标和日志仍走既有通道，避免重复导出。Grafana `http://127.0.0.1:3001` 中按 `resource.service.name = recon-platform` 查询。默认本地全采样；可在 `.env` 设置 `OTEL_TRACES_SAMPLER=traceidratio` 与 `OTEL_TRACES_SAMPLER_ARG=0.1` 降采样，或用 `OTEL_SDK_DISABLED=true` 回滚埋点。异步 outbox 会形成新 trace，业务 envelope 的 `traceId` 用于跨异步边界关联。公共脱敏、Service Graph 和排障说明见同级 `dev-infra/docs/observability.md`。
