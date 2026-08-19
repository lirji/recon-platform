# A3 真库端到端 + 生产 DB profile · 实施进度

> 路线图 `docs/PHASE2_ROADMAP.md` Track A · A3(P1 · 工作量 M)。目标:跑通真库(MySQL8 + PG)验证 V3 collation
> ALTER、`fetchSize=Integer.MIN_VALUE` 真流式、方言 batch 元数据;补生产 DB 连接 profile。**已完成 2026-08-19**。

## 背景:为什么不是简单「跑一下 CollationRealDbIT」

`CollationRealDbIT`(Testcontainers)在本机**无法自动起容器**:Testcontainers 1.19.8 内置的 docker-java 与
过新的 Docker Engine(Desktop 4.87 / Engine 29 / API 1.55)不兼容,`/info` 返回 **HTTP 400**,
`DockerClientFactory.isDockerAvailable()` 判否 → `assumeTrue` 优雅跳过(**非代码缺陷**)。且该 IT 只覆盖 collation,
**没测** A3 明确要求的另两项:方言 batch 元数据(V2)、真流式游标。

## 已完成 ✅

| # | 交付 | 文件 | 说明 |
|---|---|---|---|
| 1 | 外部库真库 IT | `recon-batch/src/test/java/.../persistence/RealDbEndToEndIT.java`(新) | 系统属性驱动(`recon.it.{mysql,postgres}.url` 等),直连 `docker run`/compose 起的真库,绕开 Testcontainers 版本不兼容;未配置则 `assumeTrue` 跳过,`./mvnw test` 默认不触真库 |
| 2 | 潜在 bug 修复 | `.../persistence/CollationRealDbIT.java` | `perBucketPlan` 的 `ANALYZE` 改方言相关:MySQL `ANALYZE TABLE <t>` / PG `ANALYZE <t>`(此前 Testcontainers 从未真跑,MySQL 语法坑未暴露) |
| 3 | PG 生产可用 | `recon-batch/pom.xml` | `org.postgresql:postgresql` + `flyway-database-postgresql` 由 **test → runtime**,令「MySQL8/PG 通用」在生产成立;删去 Testcontainers 块里的 test 作用域重复 |
| 4 | 真库端到端部署 | `compose.mysql.yml`(新) | 叠加层:起 MySQL 8 + 后端 `DB_URL` 指过去 + `depends_on: db healthy`;`docker compose -f compose.yml -f compose.mysql.yml up` |
| 5 | 文档 | `README.md`「生产 DB」小节、`docs/KNOWN_ISSUES.md` KI-4、`docs/PHASE2_ROADMAP.md` A3 | env 切换 MySQL/PG、compose 叠加层用法、真库验证命令;KI-4 收口并记录 Testcontainers 版本注意点 |

## RealDbEndToEndIT 验证矩阵(每方言一条)

1. **方言 batch 元数据(V2)**:生产同款 Flyway locations 迁移 V1 领域 + V2 batch + V3 collation;断言 MySQL 表式序列
   `BATCH_JOB_SEQ`(BASE TABLE)/ PG `CREATE SEQUENCE`(information_schema.sequences)真库落地。
2. **V3 collation ALTER 实际效果**:`ORDER BY (match_key IS NULL), match_key` == Java `String.compareTo`(码点序);
   MySQL utf8mb4_bin PAD SPACE(`'K1'=='K1 '`)/ PG no-pad(`'K1'!='K1 '`)。
3. **per-bucket 命中 `idx_merge`**:EXPLAIN(spread 40 桶 + ANALYZE 提升选择性)。
4. **真流式游标(隐患②)**:经生产 `JdbcReconRecordStore.cursor` 逐条取 —— MySQL 触发 `fetchSize=Integer.MIN_VALUE`
   真流式(forward-only + read-only),返回序 == Java 归并序。

## 实测证据(2026-08-19)

- `docker run` 起 **MySQL 8.0.46**(127.0.0.1:23306)+ **PostgreSQL 16.15**(127.0.0.1:26543)。
- `./mvnw -pl recon-batch -am test -Dtest=RealDbEndToEndIT -Drecon.it.mysql.url=... -Drecon.it.postgres.url=...`
  → **Tests run: 2, Failures: 0, Errors: 0**(两方言全绿)。
- `./mvnw -q -pl recon-batch -am test`(默认无真库)→ **BUILD SUCCESS**;`RealDbEndToEndIT`/`CollationRealDbIT` 均优雅跳过。
- `docker compose -f compose.yml -f compose.mysql.yml config` → 合并有效(backend `DB_URL=jdbc:mysql://db:3306/recon`,db=mysql:8.0 为 healthy 依赖)。

## 运行方式(复现真库验证)

```bash
docker run -d --name recon-it-mysql -e MYSQL_ROOT_PASSWORD=root -e MYSQL_DATABASE=recon -p 127.0.0.1:23306:3306 mysql:8.0
docker run -d --name recon-it-pg   -e POSTGRES_DB=recon -e POSTGRES_USER=recon -e POSTGRES_PASSWORD=recon -p 127.0.0.1:26543:5432 postgres:16
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./mvnw -pl recon-batch -am test -Dtest=RealDbEndToEndIT -Dsurefire.failIfNoSpecifiedTests=false \
  -Drecon.it.mysql.url='jdbc:mysql://127.0.0.1:23306/recon' -Drecon.it.mysql.user=root -Drecon.it.mysql.password=root \
  -Drecon.it.postgres.url='jdbc:postgresql://127.0.0.1:26543/recon' -Drecon.it.postgres.user=recon -Drecon.it.postgres.password=recon
```

## 未纳入(诚实边界)

- 未真跑 `docker compose -f compose.yml -f compose.mysql.yml up`(会重建用户当前运行中的 `recon-platform` 栈,故只做 `config` 合并校验);叠加层供用户按需 `up`。
- 未升级 Testcontainers 版本以适配新 Docker Engine(受 spring-boot BOM 托管;`RealDbEndToEndIT` 外部库路径已覆盖需求,不阻塞)。
