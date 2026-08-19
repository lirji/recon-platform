#!/usr/bin/env bash
#
# 一键部署 recon-platform 前后端 Docker 容器。
#
# 后端 recon-batch(Spring Boot 组合根)+ 前端 recon-console(React/Vite → nginx)。
# 默认起真实 MySQL 8 持久化库(compose.yml + compose.mysql.yml);--h2 退回 H2 file 快速模式。
# 默认 profile 走 DevSecurityConfig(permitAll),无需 Casdoor,可直接验证。
#
# 用法:
#   ./deploy.sh                 启动(默认 MySQL 8 持久化库)= ./deploy.sh up
#   ./deploy.sh --h2            启动(H2 file 库,快速验证,无 DB 容器)
#   ./deploy.sh --mysql         启动 MySQL 8(与默认等价,显式别名)
#   ./deploy.sh --secure        启动并启用 Casdoor 统一登录(后端 secure + 前端 oidc,走登录页)
#   ./deploy.sh up --no-build   启动但不重建镜像(用已有镜像)
#   ./deploy.sh status          查看容器与健康状态
#   ./deploy.sh logs [服务]     跟随日志(可选 backend/console/db)
#   ./deploy.sh restart         重启容器(不重建)
#   ./deploy.sh down            停止并移除容器(保留数据卷)
#   ./deploy.sh down --purge    停止并删除容器 + 数据卷(清库)
#
set -euo pipefail

# ── 定位仓库根(脚本所在目录),使脚本可在任意 cwd 调用 ────────────────────
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

# ── 前端/后端宿主机端口(与 compose.yml 的 ports 映射一致) ────────────────
CONSOLE_URL="http://127.0.0.1:8088"
BACKEND_HEALTH_URL="http://127.0.0.1:8180/actuator/health"
BACKEND_READINESS_URL="http://127.0.0.1:8180/actuator/health/readiness"
CONSOLE_HEALTH_URL="http://127.0.0.1:8088/healthz"
CONSOLE_PROXY_PROBE="http://127.0.0.1:8088/recon/dashboard"   # 经 nginx 代理打到后端, 验证整条链路(非仅静态健康)

WAIT_TIMEOUT="${WAIT_TIMEOUT:-600}"   # 健康等待上限(秒),首次含镜像构建请给足

# ── 颜色输出(非 TTY 自动降级为无色) ─────────────────────────────────────
if [[ -t 1 ]]; then
  C_RESET=$'\033[0m'; C_INFO=$'\033[36m'; C_OK=$'\033[32m'; C_WARN=$'\033[33m'; C_ERR=$'\033[31m'; C_DIM=$'\033[2m'
else
  C_RESET=""; C_INFO=""; C_OK=""; C_WARN=""; C_ERR=""; C_DIM=""
fi
info() { printf '%s▶ %s%s\n' "$C_INFO" "$*" "$C_RESET"; }
ok()   { printf '%s✔ %s%s\n' "$C_OK"   "$*" "$C_RESET"; }
warn() { printf '%s⚠ %s%s\n' "$C_WARN" "$*" "$C_RESET"; }
die()  { printf '%s✘ %s%s\n' "$C_ERR"  "$*" "$C_RESET" >&2; exit 1; }

# ── 参数解析:第一个非 -- 参数当子命令,其余当 flag ───────────────────────
CMD="up"
USE_H2=0            # 默认 0 = MySQL 8 真库;--h2 置 1 退回 H2 file 快速模式
USE_SECURE=0       # 1 = 叠加 Casdoor 统一登录(后端 secure + 前端 oidc)
NO_BUILD=0
PURGE=0
LOG_SVC=""
for arg in "$@"; do
  case "$arg" in
    up|down|status|logs|restart) CMD="$arg" ;;
    --h2)       USE_H2=1 ;;
    --mysql)    USE_H2=0 ;;   # 默认即 MySQL,保留为显式别名
    --secure)   USE_SECURE=1 ;;
    --no-build) NO_BUILD=1 ;;
    --purge)    PURGE=1 ;;
    -h|--help)  awk 'NR>1 && /^set -euo/{exit} NR>1{print}' "$0"; exit 0 ;;
    backend|console|db) LOG_SVC="$arg" ;;
    *) die "未知参数: $arg(用 -h 看用法)" ;;
  esac
done

# ── 组装 compose 文件参数 ─────────────────────────────────────────────────
# 注意: down/status/logs 也须带同一组 -f, 否则拆不干净叠加层里的服务/环境。
COMPOSE_ARGS=(-f compose.yml)
if [[ $USE_H2 -eq 0 ]]; then
  COMPOSE_ARGS+=(-f compose.mysql.yml)
fi
if [[ $USE_SECURE -eq 1 ]]; then
  COMPOSE_ARGS+=(-f compose.secure.yml)
fi

# secure 登录参数(与 auth-platform/deploy/recon-platform-provision.sh 一致;密码由开通脚本设定,不落仓库)。
SECURE_LOGIN_USER="recon-e2e-admin"
SECURE_CASDOOR_URL="http://localhost:8000"

# ── 前置检查:docker / compose / 守护进程 ─────────────────────────────────
preflight() {
  command -v docker >/dev/null 2>&1 || die "未找到 docker,请先安装 Docker Desktop / Engine。"
  if docker compose version >/dev/null 2>&1; then
    DC=(docker compose)
  elif command -v docker-compose >/dev/null 2>&1; then
    DC=(docker-compose)
  else
    die "未找到 docker compose(v2 插件)或 docker-compose。"
  fi
  docker info >/dev/null 2>&1 || die "Docker 守护进程未运行,请启动 Docker Desktop 后重试。"
}

compose() { "${DC[@]}" "${COMPOSE_ARGS[@]}" "$@"; }

# ── 部署后打印访问信息 + 冒烟 ─────────────────────────────────────────────
print_access() {
  echo
  ok "部署完成,容器已就绪(healthy)。"
  echo
  printf '  %s前端控制台%s  %s\n' "$C_DIM" "$C_RESET" "$CONSOLE_URL"
  printf '  %s后端健康%s    %s\n' "$C_DIM" "$C_RESET" "$BACKEND_HEALTH_URL"
  if [[ $USE_H2 -eq 1 ]]; then
    printf '  %s数据库%s      H2 file(容器内 /data/recon,持久化于卷 recon-platform-data)\n' "$C_DIM" "$C_RESET"
  else
    printf '  %sMySQL 8%s     127.0.0.1:23306  (库 recon / 用户 recon / 密码 recon,持久化于卷 recon-platform-mysql-data)\n' "$C_DIM" "$C_RESET"
  fi
  if [[ $USE_SECURE -eq 1 ]]; then
    printf '  %s认证%s        Casdoor 统一登录(%s);登录用户 %s\n' "$C_DIM" "$C_RESET" "$SECURE_CASDOOR_URL" "$SECURE_LOGIN_USER"
  else
    printf '  %s认证%s        dev 模式(DevSecurityConfig permitAll,免登录)\n' "$C_DIM" "$C_RESET"
  fi
  echo
  info "冒烟检查:"
  local b c p
  b="$(curl -fsS "$BACKEND_READINESS_URL" 2>/dev/null || true)"
  c="$(curl -fsS "$CONSOLE_HEALTH_URL"    2>/dev/null || true)"
  p="$(curl -fsS -o /dev/null -w '%{http_code}' "$CONSOLE_PROXY_PROBE" 2>/dev/null || true)"
  if [[ "$b" == *'"status":"UP"'* ]]; then ok "  后端 readiness: UP"; else warn "  后端 readiness 未返回 UP:$b"; fi
  if [[ "$c" == "ok" ]];             then ok "  前端 /healthz: ok"; else warn "  前端 /healthz 异常:$c"; fi
  if [[ $USE_SECURE -eq 1 ]]; then
    # secure 下未带 Bearer 访问 /recon 必须被拒(401),这正是鉴权已生效的证据。
    if [[ "$p" == "401" ]]; then ok "  鉴权已启用:未登录访问 /recon → HTTP 401(预期)"
    else warn "  鉴权异常:未登录访问 /recon → HTTP ${p:-000}(secure 下应为 401)"; fi
  else
    if [[ "$p" == "200" ]]; then ok "  前端→后端代理 /recon: HTTP 200"
    else warn "  前端→后端代理 /recon 异常: HTTP ${p:-000}(nginx upstream 未通?)"; fi
  fi
  echo
  if [[ $USE_SECURE -eq 1 ]]; then
    printf '%s登录:浏览器打开 %s → 自动跳 Casdoor 登录页,用 %s 登录(密码见开通脚本/管理员)。%s\n' "$C_DIM" "$CONSOLE_URL" "$SECURE_LOGIN_USER" "$C_RESET"
    printf '%s重新开通/校准回调:  RECON_USER=%s PASSWORD=... REDIRECT_URIS=%s/auth/callback bash ../auth-platform/deploy/recon-platform-provision.sh%s\n' "$C_DIM" "$SECURE_LOGIN_USER" "$CONSOLE_URL" "$C_RESET"
  else
    printf '%s提示:浏览器打开 %s 即可验证。日志:./deploy.sh logs  |  停止:./deploy.sh down%s\n' "$C_DIM" "$CONSOLE_URL" "$C_RESET"
  fi
}

# ── 子命令 ────────────────────────────────────────────────────────────────
preflight

case "$CMD" in
  up)
    UP_ARGS=(up -d --remove-orphans --wait --wait-timeout "$WAIT_TIMEOUT")
    if [[ $NO_BUILD -eq 0 ]]; then
      UP_ARGS+=(--build)
      info "构建并启动容器(首次含 Maven 编译 + 前端构建,可能数分钟)…"
    else
      info "启动容器(复用已有镜像,--no-build)…"
    fi
    if [[ $USE_H2 -eq 1 ]]; then
      info "数据库:H2 file 快速模式(无 DB 容器,持久化于卷 recon-platform-data)。"
    else
      info "数据库:真实 MySQL 8 持久化库(compose.mysql.yml,持久化于卷 recon-platform-mysql-data)。"
    fi
    if [[ $USE_SECURE -eq 1 ]]; then
      info "认证:Casdoor 统一登录(secure profile + 前端 oidc);需 auth-platform 的 Casdoor 在 ${SECURE_CASDOOR_URL} 运行。"
      curl -fsS -o /dev/null "${SECURE_CASDOOR_URL}/.well-known/openid-configuration" 2>/dev/null \
        || warn "  Casdoor(${SECURE_CASDOOR_URL})不可达 —— 请先启动 auth-platform 的 Casdoor,否则登录会失败。"
    fi
    if compose "${UP_ARGS[@]}"; then
      print_access
    else
      warn "部分服务未在 ${WAIT_TIMEOUT}s 内变 healthy。当前状态:"
      compose ps
      echo
      die "启动失败,请查看日志:./deploy.sh logs"
    fi
    ;;
  down)
    if [[ $PURGE -eq 1 ]]; then
      warn "停止容器并删除数据卷(清库)…"
      compose down -v --remove-orphans
    else
      info "停止并移除容器(保留数据卷)…"
      compose down --remove-orphans
    fi
    ok "已停止。"
    ;;
  status)
    compose ps
    ;;
  logs)
    info "跟随日志(Ctrl-C 退出)…"
    if [[ -n "$LOG_SVC" ]]; then compose logs -f --tail=200 "$LOG_SVC"; else compose logs -f --tail=200; fi
    ;;
  restart)
    info "重启容器…"
    compose restart
    compose ps
    ;;
esac
