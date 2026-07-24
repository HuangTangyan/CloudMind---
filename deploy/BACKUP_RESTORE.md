# CloudMind backup and restore drill

The production backup contains a consistent MySQL logical dump and a MinIO
bucket mirror. The resulting archive is always encrypted with
[age](https://age-encryption.org/); the scripts never create a final plaintext
backup.

## Prerequisites

- `docker compose`, `mc`, `age`, `tar`, `gzip`, and `sha256sum`
- Running `mysql` and `minio` services from `docker-compose.yml`
- An age recipient/private identity stored outside this repository
- A backup destination on a different disk or mounted remote filesystem

Generate the age identity once and keep it outside the project:

```bash
age-keygen -o /secure/cloudmind-backup-key.txt
```

Copy the printed public recipient into `BACKUP_AGE_RECIPIENT`. Never put the
private identity in `.env`, Git, the application directory, or the backup
archive.

## Daily encrypted backup

```bash
export BACKUP_ROOT=/mnt/cloudmind-backups
export BACKUP_RETENTION_DAYS=14
export BACKUP_AGE_RECIPIENT=age1...
./deploy/backup.sh
```

Run this once per day using the server scheduler. Replicate the encrypted
archive to an off-site location with separate credentials. Alert when the
script exits non-zero or no new archive appears within 26 hours.

## Isolated restore drill

```bash
export BACKUP_AGE_IDENTITY_FILE=/secure/cloudmind-backup-key.txt
./deploy/restore-drill.sh /mnt/cloudmind-backups/cloudmind-YYYYMMDDTHHMMSSZ.tar.gz.age
```

The drill verifies file hashes, imports MySQL into a timestamped temporary
database, restores objects into a timestamped temporary bucket, compares object
counts, and then removes both temporary targets. It never imports into the
production database or production bucket. Before extraction it rejects unsafe
paths and symbolic/hard links, and the backup payload never includes the
temporary MinIO client credentials.

Run the drill before launch and monthly thereafter. Record the archive name,
start/end time, table count, object count, operator, and result in the
operations log.
