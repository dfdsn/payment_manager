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

exec java ${JAVA_OPTS:-} -jar /app/app.jar "$@"
