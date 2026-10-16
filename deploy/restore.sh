#!/bin/sh
set -eu
KIT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
. "$KIT/common.sh"
[ "$#" -ge 3 ] && [ "$#" -le 4 ] || fail 'Usage: sh restore.sh BACKUP_DIRECTORY NEW_TARGET_DIRECTORY NEW_PROJECT_NAME [PORT]'
backup=$1; target=$2; project=$3; port=${4:-8081}
[ ! -e "$target" ] || fail 'Restore requires a fresh target directory; existing contents are never overwritten.'
case "$project" in ''|[!a-z0-9]*|*[!a-z0-9_-]*) fail 'Invalid new compose project name.';; esac
case "$port" in ''|*[!0-9]*) fail 'Invalid backend port.';; esac
[ "$port" -ge 1 ] && [ "$port" -le 65535 ] || fail 'Invalid backend port.'
[ -f "$backup/SHA256SUMS" ] || fail 'Backup SHA256SUMS missing; target untouched.'
awk 'NF!=2 || length($1)!=64 || $1~/[^0-9a-fA-F]/ || ($2!=".env" && $2!="database.sql") || seen[$2]++ {exit 1} END {if (NR!=2) exit 1}' "$backup/SHA256SUMS" || fail 'Invalid SHA256SUMS entries.'
(cd "$backup"; if command -v sha256sum >/dev/null 2>&1; then sha256sum -c SHA256SUMS; else shasum -a 256 -c SHA256SUMS; fi) >/dev/null || fail 'Backup checksum mismatch; target untouched.'
ENV_FILE="$backup/.env"; check_env
[ "$project" != "$PROJECT" ] || fail 'Restore must use a different compose project name.'
check_docker
[ -z "$(docker ps -aq --filter "label=com.docker.compose.project=$project")" ] || fail 'Target project already owns containers.'
[ -z "$(docker volume ls -q --filter "label=com.docker.compose.project=$project")" ] || fail 'Target project already owns volumes.'
# Compose can reuse same-named volumes even when their project labels are absent.
existing_names=$(docker volume ls -q)
for name in "${project}_mysql-data" "${project}_redis-data"; do
    if printf '%s\n' "$existing_names" | grep -qxF "$name"; then
        fail 'Target data volume already exists; refusing to overwrite data.'
    fi
done
mkdir -m 700 "$target"
for file in docker-compose.yml docker-compose.https.yml Caddyfile common.ps1 common.sh install.ps1 install.sh backup.ps1 backup.sh restore.ps1 restore.sh README.md; do cp "$KIT/$file" "$target/$file"; done
awk -v project="$project" -v port="$port" '
 /^COMPOSE_PROJECT_NAME=/ {print "COMPOSE_PROJECT_NAME="project; next}
 /^ORIVA_PORT=/ {print "ORIVA_PORT="port; next}
 /^ORIVA_DOMAIN=/ {print "ORIVA_DOMAIN="; next}
 {print}' "$backup/.env" > "$target/.env"
chmod 600 "$target/.env"
KIT=$(CDPATH= cd -- "$target" && pwd); ENV_FILE="$KIT/.env"
remote="/tmp/oriva-restore-$(od -An -N16 -tx1 /dev/urandom | tr -d ' \n').sql"
compose up -d --wait --wait-timeout 240 mysql redis
trap 'compose exec -T mysql rm -f "$remote"' EXIT HUP INT TERM
compose cp "$backup/database.sql" "mysql:$remote"
compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --user=root --default-character-set=utf8mb4 planlock < '"$remote"
compose exec -T mysql rm -f "$remote"; trap - EXIT HUP INT TERM
compose up -d --wait --wait-timeout 240 --no-build
result=$(health)
printf '%s' "$result" | grep -Eq '"initialized"[[:space:]]*:[[:space:]]*true' || fail 'Restored database has no completed initialization.'
printf 'Restore complete. Backend port: %s. Use a fresh App workspace for a changed URL; see README.\n' "$port"
