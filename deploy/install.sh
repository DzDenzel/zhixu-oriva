#!/bin/sh
set -eu
KIT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
. "$KIT/common.sh"
BUILD=false; SHOW=false
for arg in "$@"; do
    case "$arg" in --build) BUILD=true;; --show-setup) SHOW=true;; *) fail 'Usage: sh install.sh [--build] [--show-setup]';; esac
done
check_docker
if [ ! -e "$ENV_FILE" ]; then
    # Directory lock + noclobber prevent concurrent installers replacing secrets.
    mkdir "$KIT/.install-lock" 2>/dev/null || fail 'Another installer is running; original .env preserved.'
    trap 'rmdir "$KIT/.install-lock"' EXIT HUP INT TERM
    if [ ! -e "$ENV_FILE" ]; then
        secret() { od -An -N32 -tx1 /dev/urandom | tr -d ' \n'; }
        (set -C; cat > "$ENV_FILE" <<EOF
COMPOSE_PROJECT_NAME=oriva
ORIVA_IMAGE=ghcr.io/dzdenzel/zhixu-oriva-backend:2.1.0
ORIVA_BIND_ADDRESS=0.0.0.0
ORIVA_PORT=8080
ORIVA_TIMEZONE=UTC
MYSQL_ROOT_PASSWORD=$(secret)
PLANLOCK_DB_PASSWORD=$(secret)
PLANLOCK_REDIS_PASSWORD=$(secret)
PLANLOCK_JWT_SECRET=$(secret)
PLANLOCK_SETUP_TOKEN=$(secret)
PLANLOCK_PUBLIC_PLUGINS_CATALOG_URL=
ORIVA_DOMAIN=
EOF
        ) || fail 'Could not create .env without overwriting an existing file.'
    fi
    rmdir "$KIT/.install-lock"; trap - EXIT HUP INT TERM
fi
check_env
if [ "$SHOW" = false ]; then
    if [ "$BUILD" = true ]; then
        [ -f "$KIT/../backend/Dockerfile" ] || fail 'Local build requires the complete source archive. See README.'
        compose build --pull backend
        compose up -d --wait --wait-timeout 240 --no-build
    else
        compose pull
        compose up -d --wait --wait-timeout 240
    fi
fi
result=$(health) || fail 'Backend is not ready; see README troubleshooting. Your data is preserved.'
if printf '%s' "$result" | grep -Eq '"initialized"[[:space:]]*:[[:space:]]*true'; then
    printf '%s\n' 'Your backend is ready. Sign in with the existing owner account; initialization remains closed.'
else
    domain=$(env_value ORIVA_DOMAIN)
    if [ -n "$domain" ]; then url="https://$domain/setup"; else url="http://localhost:$PORT/setup"; fi
    printf 'Open %s\nOne-time initialization password: %s\n' "$url" "$(env_value PLANLOCK_SETUP_TOKEN)"
    addresses=$(private_ipv4)
    if [ -n "$addresses" ]; then
        for candidate in $addresses; do
            address=${candidate%%|*}
            suffix=''; case "$candidate" in *'|recommended') suffix=' (recommended)';; esac
            printf 'LAN candidate: http://%s:%s/setup%s\n' "$address" "$PORT" "$suffix"
        done
    else
        printf '%s\n' 'No private IPv4 address detected; run ip addr and choose an address your phone can reach.'
    fi
    printf '%s\n' 'On the setup page, confirm an address your phone can reach (LAN IP or HTTPS domain).'
fi
