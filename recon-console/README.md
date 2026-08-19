# recon-console

对账平台的独立运营管理台，技术栈为 React 18、TypeScript、Vite 5、Ant Design 5、React Query 和 ECharts。

## 页面

- `/dashboard`：运行/差异指标、差异类型构成、最近运行。
- `/runs`：运行筛选与分页、发起、重跑、Run 详情和守恒报表。
- `/discrepancies`：差异筛选与分页、金额/血缘、审计、冲正建议、告警状态、人工核销和关闭。

桌面端使用折叠侧栏与表格；小于 768px 时切换为移动卡片和全宽详情抽屉。金额以“币种 + 最小单位整数”展示，不假设所有币种都有两位小数。

## 本地启动

先启动后端：

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./mvnw -pl recon-batch -am spring-boot:run
```

再启动前端：

```bash
cd recon-console
pnpm install
pnpm dev
```

访问 `http://localhost:5173`。Vite 默认把 `/recon` 代理到 `http://localhost:8080`；可复制 `.env.example` 并修改 `VITE_RECON_API_TARGET`。

## 验证

```bash
pnpm test
pnpm build
pnpm e2e:install
pnpm e2e
```

Playwright 使用 mock API，覆盖桌面 Chromium 和 Pixel 5 视口，不写后端数据库。

## 容器

```bash
# 推荐：在仓库根目录同时构建并启动前后端
docker compose up -d --build --remove-orphans

# 单独运行管理台时，RECON_API_URL 必须指向一个可访问的后端
docker build -t recon-platform-console:local ./recon-console
docker run --rm -p 127.0.0.1:8088:8088 \
  -e RECON_API_URL=http://host.docker.internal:8180 \
  recon-platform-console:local
```

Compose 将管理台发布到 `http://localhost:8088`，并把后端诊断端口绑定到 `127.0.0.1:8180`。Nginx 托管 SPA，并把同源 `/recon` 反向代理到 Compose 内部后端；`GET /healthz` 用于容器存活检查，并开放无凭据跨域 GET 供本地能力门户探活。默认具名卷 `recon-platform-data` 保存本地 H2 数据，`docker compose down` 或重新构建不会删除它；只有显式 `docker compose down -v` 才会清除数据。

## 当前安全边界

本阶段按计划没有接入认证鉴权，人工处置的 `operator` 仍由表单填写。只能用于本地或受控内网环境，不能直接暴露公网。后续接入 auth 时应由后端从可信身份上下文取操作者，并在前端 axios 客户端统一注入令牌；业务页面无需自行管理 token。
