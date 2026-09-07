#!/usr/bin/env bash
# 幂等准备 recon-platform 在 dev-infra 中使用的 MySQL schema、账号和 Kafka Topic。

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEV_INFRA_DIR="${DEV_INFRA_DIR:-${SCRIPT_DIR}/../dev-infra}"
DEV_INFRA_ENV_FILE="${DEV_INFRA_ENV_FILE:-${DEV_INFRA_DIR}/.env}"
PROJECT_ENV_FILE="${RECON_ENV_FILE:-${SCRIPT_DIR}/.env}"
DEV_INFRA_COMPOSE_FILE="${DEV_INFRA_DIR}/compose.yaml"

for required_file in "${DEV_INFRA_ENV_FILE}" "${PROJECT_ENV_FILE}" "${DEV_INFRA_COMPOSE_FILE}"; do
  if [[ ! -r "${required_file}" ]]; then
    echo "Required file is missing or unreadable: ${required_file}" >&2
    [[ "${required_file}" == "${PROJECT_ENV_FILE}" ]] && \
      echo "Run: cp ${SCRIPT_DIR}/.env.example ${PROJECT_ENV_FILE}, then replace the placeholder password." >&2
    exit 1
  fi
done

set -a
# shellcheck disable=SC1090
source "${DEV_INFRA_ENV_FILE}"
# shellcheck disable=SC1090
source "${PROJECT_ENV_FILE}"
set +a

RECON_DB_NAME="${RECON_DB_NAME:-recon}"
RECON_FLOWABLE_DB_NAME="${RECON_FLOWABLE_DB_NAME:-recon_flowable}"
RECON_DB_USER="${RECON_DB_USER:-recon}"

for value in "${RECON_DB_NAME}" "${RECON_FLOWABLE_DB_NAME}" "${RECON_DB_USER}" "${RECON_DB_PASSWORD:-}"; do
  if [[ ! "${value}" =~ ^[A-Za-z0-9_-]+$ ]]; then
    echo "Database names, user and password may only contain letters, digits, _ and -." >&2
    exit 1
  fi
done
if [[ "${RECON_DB_PASSWORD}" == change-me-* ]]; then
  echo "Replace the placeholder RECON_DB_PASSWORD in ${PROJECT_ENV_FILE}." >&2
  exit 1
fi

export DEV_INFRA_ENV_FILE
"${DEV_INFRA_DIR}/bin/dev-infra" up mysql84 kafka38

compose=(docker compose --env-file "${DEV_INFRA_ENV_FILE}" -f "${DEV_INFRA_COMPOSE_FILE}")
"${compose[@]}" up -d --wait --wait-timeout 120 mysql84 kafka38

# 每个项目使用独立 schema 和账号；业务库与 Flowable 引擎库也相互隔离。
"${compose[@]}" exec -T -e MYSQL_PWD="${MYSQL84_ROOT_PASSWORD}" mysql84 \
  mysql --protocol=socket -uroot <<SQL
CREATE DATABASE IF NOT EXISTS \`${RECON_DB_NAME}\`
  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
CREATE DATABASE IF NOT EXISTS \`${RECON_FLOWABLE_DB_NAME}\`
  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
CREATE USER IF NOT EXISTS '${RECON_DB_USER}'@'%' IDENTIFIED BY '${RECON_DB_PASSWORD}';
ALTER USER '${RECON_DB_USER}'@'%' IDENTIFIED BY '${RECON_DB_PASSWORD}';
GRANT ALL PRIVILEGES ON \`${RECON_DB_NAME}\`.* TO '${RECON_DB_USER}'@'%';
GRANT ALL PRIVILEGES ON \`${RECON_FLOWABLE_DB_NAME}\`.* TO '${RECON_DB_USER}'@'%';
FLUSH PRIVILEGES;
SQL

topics=(
  benefit.fulfillment-event.v1
  benefit.remediation.command.v1
  benefit.remediation.result.v1
  marketing.award-expected.v1
)
for topic in "${topics[@]}"; do
  "${compose[@]}" exec -T kafka38 env KAFKA_HEAP_OPTS="-Xms16m -Xmx64m" \
    /opt/kafka/bin/kafka-topics.sh --bootstrap-server infra-kafka38:9092 \
    --create --if-not-exists --topic "${topic}" --partitions 3 --replication-factor 1
done

"${compose[@]}" exec -T -e MYSQL_PWD="${RECON_DB_PASSWORD}" mysql84 \
  mysql --protocol=tcp -h127.0.0.1 -u"${RECON_DB_USER}" -NBe \
  "SELECT CONCAT('database=', SCHEMA_NAME, ', collation=', DEFAULT_COLLATION_NAME)
     FROM information_schema.SCHEMATA
    WHERE SCHEMA_NAME IN ('${RECON_DB_NAME}', '${RECON_FLOWABLE_DB_NAME}')
    ORDER BY SCHEMA_NAME"
"${compose[@]}" exec -T kafka38 env KAFKA_HEAP_OPTS="-Xms16m -Xmx64m" \
  /opt/kafka/bin/kafka-topics.sh --bootstrap-server infra-kafka38:9092 --list \
  | grep -E '^(benefit\.(fulfillment-event|remediation\.(command|result))|marketing\.award-expected)\.v1$' \
  | sort

echo "recon-platform dev-infra resources are ready."
