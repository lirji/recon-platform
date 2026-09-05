#!/usr/bin/env bash
# Recon Compose 统一入口：本地工作区自动加载 auth-platform 中央门户端口。

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PLATFORM_PORTS_LOADER="${PLATFORM_PORTS_LOADER:-${SCRIPT_DIR}/../auth-platform/deploy/load-platform-ports.sh}"
ENV_ARGS=()
if [[ -r "${PLATFORM_PORTS_LOADER}" ]]; then
  # shellcheck source=/dev/null
  . "${PLATFORM_PORTS_LOADER}"
  ENV_ARGS+=(--env-file "${PLATFORM_PORTS_FILE}")
fi
export RECON_UI_PORT="${RECON_UI_PORT:-8088}"
cd "${SCRIPT_DIR}"
exec docker compose "${ENV_ARGS[@]}" "$@"
