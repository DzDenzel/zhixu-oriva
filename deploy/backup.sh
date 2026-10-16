#!/bin/sh
set -eu
KIT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
. "$KIT/common.sh"
[ "$#" -eq 1 ] || fail 'Usage: sh backup.sh NEW_BACKUP_DIRECTORY'
check_docker; check_env
health >/dev/null || fail 'Backend is not ready.'
destination=$1
[ ! -e "$destination" ] || fail 'Choose a new backup directory; existing backups are never overwritten.'
mkdir -m 700 "$destination"
remote="/tmp/oriva-backup-$(od -An -N16 -tx1 /dev/urandom | tr -d ' \n').sql"
trap 'compose exec -T mysql rm -f "$remote"' EXIT HUP INT TERM
compose exec -T mysql sh -c 'umask 077; MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysqldump --user=root --single-transaction --routines --triggers --no-tablespaces --set-gtid-purged=OFF --default-character-set=utf8mb4 planlock > '"$remote"
compose cp "mysql:$remote" "$destination/database.sql"
cp "$ENV_FILE" "$destination/.env"
chmod 600 "$destination/database.sql" "$destination/.env"
(cd "$destination"; if command -v sha256sum >/dev/null 2>&1; then sha256sum .env database.sql; else shasum -a 256 .env database.sql; fi) > "$destination/SHA256SUMS"
printf 'Backup complete: %s. It contains credentials and personal data; store it privately.\n' "$destination"
