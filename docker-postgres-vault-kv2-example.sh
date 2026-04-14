#!/usr/bin/env bash
set -euo pipefail

# Starts a local Vault dev server with a KV v2 mount and a sample secret.
# Also starts a local Postgres container so you can test the plugin end-to-end.
#
# Usage:
#   ./docker-postgres-vault-kv2-example.sh        # start
#   ./docker-postgres-vault-kv2-example.sh --stop # stop vault + container

export VAULT_ADDR='http://127.0.0.1:8203'
export VAULT_TOKEN='root'

POSTGRES_CONTAINER_NAME='datagrip-vault-kv2-postgres'
POSTGRES_PORT='5434'
POSTGRES_USER='example_user'
POSTGRES_PASSWORD='example_pass'
POSTGRES_DB='postgres'

MODE=${1:-""}

cleanup_container() {
  local name="$1"
  if docker ps -a --format '{{.Names}}' | grep -qx "${name}"; then
    echo "Removing existing container: ${name}"
    docker rm -f "${name}" > /dev/null
  fi
}

kill_vault_on_ports() {
  if ! command -v lsof > /dev/null 2>&1; then
    echo "lsof not found; skipping port checks." >&2
    return 0
  fi
  local ports=("$@")
  local all_pids=""
  for port in "${ports[@]}"; do
    local pids
    pids=$(lsof -tiTCP:"${port}" -sTCP:LISTEN || true)
    if [[ -n "${pids}" ]]; then
      all_pids="${all_pids} ${pids}"
    fi
  done
  local uniq_pids
  uniq_pids=$(echo "${all_pids}" | tr ' ' '\n' | sed '/^$/d' | sort -u | tr '\n' ' ')
  if [[ -n "${uniq_pids}" ]]; then
    for pid in ${uniq_pids}; do
      local cmd
      cmd=$(ps -p "${pid}" -o comm= | tr -d '\n')
      if [[ "${cmd}" == "vault" ]]; then
        echo "Killing vault process on ports ${ports[*]}: PID ${pid}"
        kill "${pid}"
        killed_any=1
      else
        echo "Skipping PID ${pid} (${cmd}); not a vault process." >&2
      fi
    done
  fi
}

ensure_ports_available() {
  if ! command -v lsof > /dev/null 2>&1; then
    echo "lsof not found; skipping port checks." >&2
    return 0
  fi
  local ports=("$@")
  local all_pids=""
  for port in "${ports[@]}"; do
    local pids
    pids=$(lsof -tiTCP:"${port}" -sTCP:LISTEN || true)
    if [[ -n "${pids}" ]]; then
      echo "Port ${port} is already in use by PID(s): ${pids}"
      all_pids="${all_pids} ${pids}"
    fi
  done
  local uniq_pids
  uniq_pids=$(echo "${all_pids}" | tr ' ' '\n' | sed '/^$/d' | sort -u | tr '\n' ' ')
  if [[ -n "${uniq_pids}" ]]; then
    for pid in ${uniq_pids}; do
      local cmd
      cmd=$(ps -p "${pid}" -o comm= | tr -d '\n')
      if [[ -n "${cmd}" ]]; then
        echo "- ${pid}: ${cmd}"
      fi
    done
    read -r -p "Kill these process(es)? [y/N] " answer
    case "${answer}" in
      [yY][eE][sS]|[yY])
        echo "Killing PID(s): ${uniq_pids}"
        kill ${uniq_pids}
        ;;
      *)
        echo "Aborting." >&2
        exit 1
        ;;
    esac
  fi
}

if [[ "${MODE}" == "--stop" ]]; then
  container_removed=0
  killed_any=0
  if docker ps -a --format '{{.Names}}' | grep -qx "${POSTGRES_CONTAINER_NAME}"; then
    cleanup_container "${POSTGRES_CONTAINER_NAME}"
    container_removed=1
  fi
  kill_vault_on_ports 8203 8204
  if [[ "${container_removed}" -eq 1 ]]; then
    echo "Docker container removed: ${POSTGRES_CONTAINER_NAME}"
  fi
  if [[ "${container_removed}" -eq 0 && "${killed_any}" -eq 0 ]]; then
    echo "Everything already stopped."
  fi
  exit 0
fi

cleanup_container "${POSTGRES_CONTAINER_NAME}"
ensure_ports_available 8203 8204

docker run \
  --detach \
  --name "${POSTGRES_CONTAINER_NAME}" \
  -e POSTGRES_USER="${POSTGRES_USER}" \
  -e POSTGRES_PASSWORD="${POSTGRES_PASSWORD}" \
  -e POSTGRES_DB="${POSTGRES_DB}" \
  -p "${POSTGRES_PORT}:5432" \
  --rm \
  postgres

echo "Waiting for Postgres to accept connections..."
for i in {1..30}; do
  if docker exec "${POSTGRES_CONTAINER_NAME}" pg_isready -U "${POSTGRES_USER}" -d "${POSTGRES_DB}" > /dev/null 2>&1; then
    break
  fi
  sleep 1
done

if ! docker exec "${POSTGRES_CONTAINER_NAME}" pg_isready -U "${POSTGRES_USER}" -d "${POSTGRES_DB}" > /dev/null 2>&1; then
  echo "Postgres did not become ready in time." >&2
  exit 1
fi

vault server -dev -dev-root-token-id root -dev-listen-address=127.0.0.1:8203 &
VAULT_PID=$!

sleep 1

vault secrets enable -path=kv kv-v2
vault kv put kv/my_db_credentials db_user="${POSTGRES_USER}" db_pass="${POSTGRES_PASSWORD}"

vault kv get kv/my_db_credentials

cat <<INFO

Vault dev server running (PID: ${VAULT_PID})
- Vault address: ${VAULT_ADDR}
- Vault token: ${VAULT_TOKEN}
- KV v2 secret path: kv/my_db_credentials (read endpoint: kv/data/my_db_credentials)
- KV v2 username key: db_user
- KV v2 password key: db_pass

Postgres container running: ${POSTGRES_CONTAINER_NAME}
- Host: localhost
- Port: ${POSTGRES_PORT}
- Database: ${POSTGRES_DB}
- Username: ${POSTGRES_USER}
- Password: ${POSTGRES_PASSWORD}

Stop the server with: kill ${VAULT_PID}
Stop the container with: docker rm -f ${POSTGRES_CONTAINER_NAME}
INFO