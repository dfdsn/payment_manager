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

exec java ${JAVA_OPTS:-} -jar /app/app.jar "$@"
