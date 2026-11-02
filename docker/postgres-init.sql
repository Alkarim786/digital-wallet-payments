-- One Postgres server, one database per service (database-per-service). Services never read each other tables.
-- The "wallet" database is created by POSTGRES_DB in docker-compose.yml.
CREATE DATABASE auth;
CREATE DATABASE notification;
