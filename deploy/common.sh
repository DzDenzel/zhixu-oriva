#!/bin/sh
# Sourced only from our own script, never from .env.
set -eu
umask 077
ENV_FILE="$KIT/.env"
fail() { printf '%s\n' "$*" >&2; exit 1; }
env_value() { awk -v key="$1" 'index($0,key"=")==1 {print substr($0,length(key)+2)}' "$ENV_FILE"; }
check_env() {
    [ -f "$ENV_FILE" ] || fail 'Run install.sh first; .env not found.'
    [ ! -L "$ENV_FILE" ] || fail '.env must not be a symbolic link.'
    awk '/^#/ || /^$/ {next} !/^[A-Z][A-Z0-9_]*=[^\r]*$/ {exit 1} {split($0,a,"="); if(seen[a[1]]++) exit 1}' "$ENV_FILE" || fail 'Invalid .env format; file preserved.'
    for key in PLANLOCK_JWT_SECRET PLANLOCK_DB_PASSWORD MYSQL_ROOT_PASSWORD PLANLOCK_REDIS_PASSWORD PLANLOCK_SETUP_TOKEN; do
        value=$(env_value "$key")
        [ "${#value}" -ge 64 ] || fail "Invalid or missing $key; original .env preserved."
        case "$value" in *[!0-9a-f]*) fail "Invalid $key; original .env preserved.";; esac
    done
    PROJECT=$(env_value COMPOSE_PROJECT_NAME)
    case "$PROJECT" in ''|[!a-z0-9]*|*[!a-z0-9_-]*) fail 'Invalid compose project name.';; esac
    PORT=$(env_value ORIVA_PORT)
    case "$PORT" in ''|*[!0-9]*) fail 'Invalid backend port.';; esac
    [ "$PORT" -ge 1 ] && [ "$PORT" -le 65535 ] || fail 'Invalid backend port.'
    [ -n "$(env_value ORIVA_IMAGE)" ] || fail 'Missing backend image.'
    chmod 600 "$ENV_FILE" || fail 'Cannot restrict .env permissions.'
}
check_docker() {
    command -v docker >/dev/null 2>&1 || fail 'Please install and start Docker Desktop / Docker Engine first.'
    docker info --format '{{.ServerVersion}}' >/dev/null || fail 'Docker engine is not ready.'
    version=$(docker compose version --short) || fail 'Docker Compose is required.'
    version=${version#v}
    printf '%s\n' "$version" | awk -F. '/^[0-9]+\.[0-9]+\.[0-9]+/ {if($1>2 || ($1==2 && ($2>24 || ($2==24 && $3+0>=4)))) ok=1} END {exit !ok}' || fail 'Docker Compose 2.24.4 or newer is required.'
}
compose() (
    check_env
    # Compose normally lets shell values override .env; use this installation's values.
    while IFS= read -r line || [ -n "$line" ]; do
        case "$line" in PLANLOCK_*=*|ORIVA_*=*|MYSQL_ROOT_PASSWORD=*|COMPOSE_PROJECT_NAME=*) export "$line";; esac
    done < "$ENV_FILE"
    if [ -n "$(env_value ORIVA_DOMAIN)" ]; then
        docker compose --env-file "$ENV_FILE" -f "$KIT/docker-compose.yml" -f "$KIT/docker-compose.https.yml" --project-name "$PROJECT" "$@"
    else
        docker compose --env-file "$ENV_FILE" -f "$KIT/docker-compose.yml" --project-name "$PROJECT" "$@"
    fi
)
health() { compose exec -T backend java -cp /opt/oriva/health Healthcheck --json; }

# List candidates only; selecting a phone-reachable interface remains the owner's choice.
private_ipv4() {
    command -v ip >/dev/null 2>&1 || return 0
    preferred=$(ip -4 route show default 2>/dev/null | awk '{for(i=1;i<NF;i++) if($i=="dev") {print $(i+1); exit}}')
    ip -o -4 addr show 2>/dev/null | awk -v preferred="$preferred" '{
        interface=$2; sub(/@.*/, "", interface)
        if(interface ~ /^(lo$|docker|br-|veth|virbr|cni|flannel|tailscale|tun|tap|wg|vmnet|vboxnet)/) next
        split($4,a,"/"); n=split(a[1],b,".")
        if(n==4 && (b[1]==10 || (b[1]==192 && b[2]==168) || (b[1]==172 && b[2]>=16 && b[2]<=31)))
            print (interface==preferred ? 0 : 1), a[1]
    }' | sort -u | awk '{print $2 ($1==0 ? "|recommended" : "")}'
}
