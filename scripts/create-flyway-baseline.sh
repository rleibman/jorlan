#!/bin/bash
# Create Flyway baseline for existing database
# This script generates SQL to mark all existing migrations as applied

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SQL_DIR="${SCRIPT_DIR}/../server/src/main/sql"

echo "-- Flyway Baseline Script"
echo "-- Generated: $(date)"
echo "-- This marks all existing migrations (V001-V015) as already applied"
echo ""
echo "CREATE TABLE IF NOT EXISTS flyway_schema_history ("
echo "  installed_rank INT NOT NULL,"
echo "  version VARCHAR(50),"
echo "  description VARCHAR(200) NOT NULL,"
echo "  type VARCHAR(20) NOT NULL,"
echo "  script VARCHAR(1000) NOT NULL,"
echo "  checksum INT,"
echo "  installed_by VARCHAR(100) NOT NULL,"
echo "  installed_on TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,"
echo "  execution_time INT NOT NULL,"
echo "  success BOOLEAN NOT NULL,"
echo "  PRIMARY KEY (installed_rank)"
echo ") ENGINE=InnoDB;"
echo ""
echo "-- Mark existing migrations as applied"

rank=1
for file in $(ls -1 "$SQL_DIR"/V*.sql | sort -V); do
    basename=$(basename "$file")
    # Extract version (e.g., "001" from "V001__schema.sql")
    version=$(echo "$basename" | sed 's/V\([0-9]*\)__.*/\1/')
    # Extract description (e.g., "schema" from "V001__schema.sql")
    description=$(echo "$basename" | sed 's/V[0-9]*__\(.*\)\.sql/\1/')

    # Calculate checksum (Flyway uses CRC32 but we'll use 0 for manual entries)
    checksum="NULL"

    echo "INSERT INTO flyway_schema_history (installed_rank, version, description, type, script, checksum, installed_by, execution_time, success)"
    echo "VALUES ($rank, '$version', '$description', 'SQL', '$basename', $checksum, 'baseline', 0, true);"

    rank=$((rank + 1))
done

echo ""
echo "-- Verify baseline"
echo "SELECT * FROM flyway_schema_history ORDER BY installed_rank;"
