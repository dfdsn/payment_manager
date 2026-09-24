#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
ENV_FILE=${1:-"$SCRIPT_DIR/.env"}
COMPOSE_FILE="$SCRIPT_DIR/compose.prod.yml"

if [ "${BACKUP_CONFIRMED:-}" != "yes" ]; then
  echo "Interrompido: confirme um backup íntegro com BACKUP_CONFIRMED=yes." >&2
  exit 2
fi

docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" pull db backend frontend
docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" --profile migration run --rm migrate
docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" up -d db backend frontend
docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" ps
