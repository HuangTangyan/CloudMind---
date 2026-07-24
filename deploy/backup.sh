#!/usr/bin/env bash
set -Eeuo pipefail

umask 077

repo_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
compose_file="${COMPOSE_FILE:-$repo_dir/docker-compose.yml}"
backup_root="${BACKUP_ROOT:-$repo_dir/backups}"
retention_days="${BACKUP_RETENTION_DAYS:-14}"
age_recipient="${BACKUP_AGE_RECIPIENT:-}"

for required_command in docker mc age tar gzip sha256sum; do
    command -v "$required_command" >/dev/null 2>&1 || {
        echo "Missing required command: $required_command" >&2
        exit 1
    }
done

if [[ -z "$age_recipient" ]]; then
    echo "BACKUP_AGE_RECIPIENT is required; plaintext backups are not allowed." >&2
    exit 1
fi
if [[ -z "$backup_root" || "$backup_root" == "/" ]]; then
    echo "BACKUP_ROOT must be a dedicated non-root directory." >&2
    exit 1
fi
if [[ ! "$retention_days" =~ ^[0-9]+$ ]]; then
    echo "BACKUP_RETENTION_DAYS must be a non-negative integer." >&2
    exit 1
fi

mkdir -p -- "$backup_root"
work_dir="$(mktemp -d "$backup_root/.cloudmind-backup.XXXXXX")"
payload_dir="$work_dir/payload"
mkdir -p -- "$payload_dir"
export MC_CONFIG_DIR="$work_dir/mc-config"
cleanup() {
    rm -rf -- "$work_dir"
}
trap cleanup EXIT

compose=(docker compose -f "$compose_file")
"${compose[@]}" config --quiet

database_name="${MYSQL_DATABASE:-$("${compose[@]}" exec -T mysql printenv MYSQL_DATABASE | tr -d '\r')}"
minio_user="${MINIO_ROOT_USER:-$("${compose[@]}" exec -T minio printenv MINIO_ROOT_USER | tr -d '\r')}"
minio_password="${MINIO_ROOT_PASSWORD:-$("${compose[@]}" exec -T minio printenv MINIO_ROOT_PASSWORD | tr -d '\r')}"
minio_bucket="${MINIO_BUCKET:-$("${compose[@]}" exec -T minio printenv MINIO_BUCKET | tr -d '\r')}"
minio_endpoint="${MINIO_ENDPOINT:-http://127.0.0.1:${MINIO_API_BIND_PORT:-9000}}"

if [[ -z "$database_name" || -z "$minio_user" || -z "$minio_password" || -z "$minio_bucket" ]]; then
    echo "Database or MinIO backup settings are incomplete." >&2
    exit 1
fi

"${compose[@]}" exec -T mysql sh -c \
    'exec mysqldump --single-transaction --routines --events --hex-blob -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE"' \
    | gzip -9 > "$payload_dir/mysql.sql.gz"

mc alias set cloudmind-backup "$minio_endpoint" "$minio_user" "$minio_password" --api S3v4 >/dev/null
mkdir -p -- "$payload_dir/minio"
mc mirror --overwrite "cloudmind-backup/$minio_bucket" "$payload_dir/minio" >/dev/null

created_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
printf '%s\n' \
    "formatVersion=1" \
    "createdAt=$created_at" \
    "database=$database_name" \
    "bucket=$minio_bucket" \
    > "$payload_dir/metadata.txt"

(
    cd -- "$payload_dir"
    find . -type f ! -name SHA256SUMS -print0 \
        | sort -z \
        | xargs -0 sha256sum > SHA256SUMS
)

stamp="$(date -u +%Y%m%dT%H%M%SZ)"
archive="$backup_root/cloudmind-$stamp.tar.gz.age"
tar -C "$payload_dir" -czf - . | age -r "$age_recipient" -o "$archive"
chmod 600 "$archive"

find "$backup_root" -maxdepth 1 -type f -name 'cloudmind-*.tar.gz.age' \
    -mtime "+$retention_days" -delete

echo "Encrypted backup created: $archive"
