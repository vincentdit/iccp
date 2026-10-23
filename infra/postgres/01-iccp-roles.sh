#!/bin/sh
# Runs once when the PostgreSQL container initialises an empty data directory.
# Creates the ICCP database roles (Doc 06 §6.1) and the iccp database.
# Passwords come from environment variables set in docker-compose.yml / .env — never from Git.
set -e
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres <<SQL
CREATE ROLE iccp_owner     LOGIN PASSWORD '${ICCP_OWNER_PASSWORD}';
CREATE ROLE iccp_app       LOGIN PASSWORD '${ICCP_APP_PASSWORD}';
CREATE ROLE iccp_retention LOGIN PASSWORD '${ICCP_RETENTION_PASSWORD}';
CREATE ROLE iccp_report    LOGIN PASSWORD '${ICCP_REPORT_PASSWORD}';
CREATE ROLE iccp_auditor   LOGIN PASSWORD '${ICCP_AUDITOR_PASSWORD}';
CREATE DATABASE iccp OWNER iccp_owner ENCODING 'UTF8' TEMPLATE template0;
SQL
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname iccp -c "CREATE EXTENSION IF NOT EXISTS pgcrypto;"
