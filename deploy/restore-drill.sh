#!/usr/bin/env bash
set -Eeuo pipefail

umask 077

if [[ $# -ne 1 ]]; then
    echo "Usage: BACKUP_AGE_IDENTITY_FILE=/secure/key.txt $0 <backup.tar.gz.age>" >&2
    exit 1
fi

archive="$1"
identity_file="${BACKUP_AGE_IDENTITY_FILE:-}"
repo_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
compose_file="${COMPOSE_FILE:-$repo_dir/docker-compose.yml}"

for required_command in docker mc age tar gzip sha256sum; do
    command -v "$required_command" >/dev/null 2>&1 || {
        echo "Missing required command: $required_command" >&2
        exit 1
    }
done

if [[ ! -f "$archive" ]]; then
    echo "Backup archive does not exist: $archive" >&2
    exit 1
fi
if [[ -z "$identity_file" || ! -f "$identity_file" ]]; then
    echo "BACKUP_AGE_IDENTITY_FILE must point to the private age identity." >&2
    exit 1
fi

work_dir="$(mktemp -d)"
restore_dir="$work_dir/restore"
payload_archive="$work_dir/payload.tar.gz"
mkdir -p -- "$restore_dir"
export MC_CONFIG_DIR="$work_dir/mc-config"
stamp="$(date -u +%Y%m%d%H%M%S)"
restore_database="cloudmind_restore_verify_$stamp"
restore_bucket="cloudmind-restore-verify-$stamp"
database_created=false
bucket_created=false
compose=(docker compose -f "$compose_file")

cleanup() {
    if [[ "$database_created" == true ]]; then
        "${compose[@]}" exec -T -e RESTORE_DATABASE="$restore_database" mysql sh -c \
            'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "DROP DATABASE IF EXISTS \`$RESTORE_DATABASE\`"' \
            >/dev/null 2>&1 || true
    fi
    if [[ "$bucket_created" == true ]]; then
        mc rb --force "cloudmind-restore/$restore_bucket" >/dev/null 2>&1 || true
    fi
    rm -rf -- "$work_dir"
}
trap cleanup EXIT

"${compose[@]}" config --quiet
age --decrypt -i "$identity_file" "$archive" > "$payload_archive"
if tar -tzf "$payload_archive" | grep -Eq '(^/|(^|/)\.\.(/|$))'; then
    echo "Backup archive contains an unsafe path." >&2
    exit 1
fi
if tar -tvzf "$payload_archive" \
    | awk 'substr($1, 1, 1) == "l" || substr($1, 1, 1) == "h" { found = 1 } END { exit found ? 0 : 1 }'; then
    echo "Backup archive must not contain symbolic or hard links." >&2
    exit 1
fi
tar --extract --gzip --file "$payload_archive" --directory "$restore_dir" \
    --no-same-owner --no-same-permissions
for required_path in mysql.sql.gz metadata.txt SHA256SUMS; do
    if [[ ! -f "$restore_dir/$required_path" || -L "$restore_dir/$required_path" ]]; then
        echo "Backup archive is missing a safe $required_path file." >&2
        exit 1
    fi
done
if [[ ! -d "$restore_dir/minio" || -L "$restore_dir/minio" ]]; then
    echo "Backup archive is missing a safe MinIO payload directory." >&2
    exit 1
fi
while IFS= read -r checksum_line; do
    checksum_path="${checksum_line#*  }"
    if [[ "$checksum_path" != ./*
            || "$checksum_path" == ../*
            || "$checksum_path" == *"/../"* ]]; then
        echo "Checksum manifest contains an unsafe path." >&2
        exit 1
    fi
done < "$restore_dir/SHA256SUMS"
(
    cd -- "$restore_dir"
    sha256sum --check --strict SHA256SUMS
)

"${compose[@]}" exec -T -e RESTORE_DATABASE="$restore_database" mysql sh -c \
    'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "CREATE DATABASE \`$RESTORE_DATABASE\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"' \
    >/dev/null
database_created=true

gzip -dc "$restore_dir/mysql.sql.gz" \
    | "${compose[@]}" exec -T -e RESTORE_DATABASE="$restore_database" mysql sh -c \
        'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" "$RESTORE_DATABASE"'

table_count="$("${compose[@]}" exec -T -e RESTORE_DATABASE="$restore_database" mysql sh -c \
    'exec mysql -N -uroot -p"$MYSQL_ROOT_PASSWORD" "$RESTORE_DATABASE" -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()"' \
    | tr -d '\r')"
if [[ ! "$table_count" =~ ^[0-9]+$ || "$table_count" -eq 0 ]]; then
    echo "Database restore verification failed: no tables found." >&2
    exit 1
fi

minio_user="${MINIO_ROOT_USER:-$("${compose[@]}" exec -T minio printenv MINIO_ROOT_USER | tr -d '\r')}"
minio_password="${MINIO_ROOT_PASSWORD:-$("${compose[@]}" exec -T minio printenv MINIO_ROOT_PASSWORD | tr -d '\r')}"
minio_endpoint="${MINIO_ENDPOINT:-http://127.0.0.1:${MINIO_API_BIND_PORT:-9000}}"
mc alias set cloudmind-restore "$minio_endpoint" "$minio_user" "$minio_password" --api S3v4 >/dev/null
mc mb "cloudmind-restore/$restore_bucket" >/dev/null
bucket_created=true
mc mirror --overwrite "$restore_dir/minio" "cloudmind-restore/$restore_bucket" >/dev/null

local_object_count="$(find "$restore_dir/minio" -type f | wc -l | tr -d ' ')"
remote_object_count="$(mc find "cloudmind-restore/$restore_bucket" --type f | wc -l | tr -d ' ')"
if [[ "$local_object_count" != "$remote_object_count" ]]; then
    echo "Object restore verification failed: local=$local_object_count remote=$remote_object_count" >&2
    exit 1
fi

echo "Restore drill passed: tables=$table_count objects=$remote_object_count"
