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
ORIVA_IMAGE=ghcr.io/dzdenzel/zhixu-oriva-backend:2.2.0
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
cached_base_images_ready() {
    base_images=$(awk '
        { sub(/\r$/, ""); sub(/^[ \t]+/, "") }
        /^[Ff][Rr][Oo][Mm][ \t]+/ {
            n = split($0, fields, /[ \t]+/)
            idx = 2
            while (idx <= n && fields[idx] ~ /^--/) idx++
            if (idx <= n) {
                img = fields[idx]
                if (img != "" && img !~ /^-/ && tolower(img) != "scratch" && !(tolower(img) in stages)) print img
                if (idx + 2 <= n && tolower(fields[idx + 1]) == "as") stages[tolower(fields[idx + 2])] = 1
            }
        }
    ' "$1") || return 1
    [ -n "$base_images" ] || return 1
    printf '%s\n' "$base_images" | (
        found=false
        while IFS= read -r image; do
            [ -n "$image" ] || continue
            found=true
            docker image inspect "$image" >/dev/null 2>&1 || exit 1
        done
        [ "$found" = true ]
    )
}
resolve_maven_mirror() {
    LC_ALL=C awk 'BEGIN {
        v = ENVIRON["ORIVA_MAVEN_MIRROR"]
        if (v == "") exit 0
        if (v ~ /[\001-\040\042\047\134\177]/ || v !~ /^https:\/\/[A-Za-z0-9._:\/-]+$/) exit 2
        rest = substr(v, 9)
        slash = index(rest, "/")
        auth = (slash > 0 ? substr(rest, 1, slash - 1) : rest)
        if (auth == "") exit 2
        colon = index(auth, ":")
        if (colon > 0) {
            host = substr(auth, 1, colon - 1)
            port = substr(auth, colon + 1)
            if (port !~ /^[0-9]+$/) exit 2
            pnum = port + 0
            if (pnum < 1 || pnum > 65535) exit 2
        } else {
            host = auth
        }
        if (host == "" || host !~ /^[A-Za-z0-9._-]+$/ || host ~ /^\.|\.$|\.\./) exit 2
        printf "%s", v
        exit 0
    }'
}
compose_build_backend() {
    pull_flag=${1:-}
    set -- build
    if [ -n "$pull_flag" ]; then
        set -- "$@" "$pull_flag"
    fi
    for proxy_key in HTTP_PROXY HTTPS_PROXY NO_PROXY http_proxy https_proxy no_proxy; do
        proxy_val=$(LC_ALL=C awk -v k="$proxy_key" 'BEGIN {
            v = ENVIRON[k]
            if (v != "" && v !~ /[\001-\040\177]/) printf "%s", v
            exit
        }')
        if [ -n "$proxy_val" ]; then
            set -- "$@" --build-arg "$proxy_key=$proxy_val"
        fi
    done
    if [ -n "${maven_mirror:-}" ]; then
        set -- "$@" --build-arg "MAVEN_MIRROR_URL=$maven_mirror"
    fi
    set -- "$@" backend
    compose "$@"
}
check_env
if [ "$SHOW" = false ]; then
    if [ "$BUILD" = true ]; then
        dockerfile="$KIT/../backend/Dockerfile"
        [ -f "$dockerfile" ] || fail 'Local build requires the complete source archive. See README.'
        maven_mirror=''
        if maven_mirror=$(resolve_maven_mirror); then
            :
        else
            maven_mirror=''
            printf '%s\n' 'Warning: ignoring invalid ORIVA_MAVEN_MIRROR (must be an https:// URL without whitespace, credentials, query/fragment, or XML/shell special characters).' >&2
        fi
        if ! compose_build_backend --pull; then
            if cached_base_images_ready "$dockerfile"; then
                printf '%s\n' 'Warning: compose build --pull failed, but required base images are cached locally; retrying build without --pull.' >&2
                compose_build_backend
            else
                fail 'Backend image build failed and required base images are not cached locally. Check Docker registry connectivity and retry.'
            fi
        fi
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
