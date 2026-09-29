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

# H08.4: Meta WhatsApp secrets. An empty file keeps the provider incomplete (nothing is sent).
if [ -n "${META_WHATSAPP_TOKEN_FILE:-}" ] && [ -f "$META_WHATSAPP_TOKEN_FILE" ]; then
  META_WHATSAPP_TOKEN="$(cat "$META_WHATSAPP_TOKEN_FILE")"
  export META_WHATSAPP_TOKEN
fi

if [ -n "${META_WHATSAPP_APP_SECRET_FILE:-}" ] && [ -f "$META_WHATSAPP_APP_SECRET_FILE" ]; then
  META_WHATSAPP_APP_SECRET="$(cat "$META_WHATSAPP_APP_SECRET_FILE")"
  export META_WHATSAPP_APP_SECRET
fi

if [ -n "${META_WHATSAPP_VERIFY_TOKEN_FILE:-}" ] && [ -f "$META_WHATSAPP_VERIFY_TOKEN_FILE" ]; then
  META_WHATSAPP_VERIFY_TOKEN="$(cat "$META_WHATSAPP_VERIFY_TOKEN_FILE")"
  export META_WHATSAPP_VERIFY_TOKEN
fi

exec java ${JAVA_OPTS:-} -jar /app/app.jar "$@"
