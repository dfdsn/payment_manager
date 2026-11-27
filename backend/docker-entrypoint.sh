#!/usr/bin/env sh
set -eu

if [ -n "${SPRING_DATASOURCE_PASSWORD_FILE:-}" ]; then
  SPRING_DATASOURCE_PASSWORD="$(cat "$SPRING_DATASOURCE_PASSWORD_FILE")"
  export SPRING_DATASOURCE_PASSWORD
fi

if [ -n "${APP_SETUP_SECRET_FILE:-}" ]; then
  APP_SETUP_SECRET="$(cat "$APP_SETUP_SECRET_FILE")"
  export APP_SETUP_SECRET
fi

if [ -n "${SMTP_PASSWORD_FILE:-}" ]; then
  SMTP_PASSWORD="$(cat "$SMTP_PASSWORD_FILE")"
  export SMTP_PASSWORD
fi

# T36: Evolution API secrets. An empty file keeps the provider incomplete (nothing is sent).
if [ -n "${EVOLUTION_API_KEY_FILE:-}" ] && [ -f "$EVOLUTION_API_KEY_FILE" ]; then
  EVOLUTION_API_KEY="$(cat "$EVOLUTION_API_KEY_FILE")"
  export EVOLUTION_API_KEY
fi

if [ -n "${EVOLUTION_WEBHOOK_TOKEN_FILE:-}" ] && [ -f "$EVOLUTION_WEBHOOK_TOKEN_FILE" ]; then
  EVOLUTION_WEBHOOK_TOKEN="$(cat "$EVOLUTION_WEBHOOK_TOKEN_FILE")"
  export EVOLUTION_WEBHOOK_TOKEN
fi

exec java ${JAVA_OPTS:-} -jar /app/app.jar "$@"
