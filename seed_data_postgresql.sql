-- LSHOE PostgreSQL full database reset script
-- WARNING: This permanently deletes all users, products, carts, orders, and tokens.
--
-- Before running:
-- 1. Stop the Spring Boot application and AI services.
-- 2. In pgAdmin, open Query Tool on the "postgres" database,
--    NOT on the "lshoe_store" database.
-- 3. Run this whole script with a PostgreSQL role that can create databases.

-- Close any remaining connections to the old database.
SELECT pg_terminate_backend(pid)
FROM pg_stat_activity
WHERE datname = 'lshoe_store'
  AND pid <> pg_backend_pid();

-- Remove the old database and recreate an empty UTF-8 database.
DROP DATABASE IF EXISTS lshoe_store WITH (FORCE);

CREATE DATABASE lshoe_store
    WITH
    ENCODING = 'UTF8'
    TEMPLATE = template0;

-- The database is intentionally empty here.
-- Start the Spring Boot application after this script finishes.
-- Hibernate will create/update the tables automatically.
--
-- To create demo products, set this in .env before starting the app:
-- SEED_DEMO_DATA=true
--
-- To create the first administrator, also set:
-- BOOTSTRAP_ADMIN_EMAIL=admin@example.com
-- BOOTSTRAP_ADMIN_PASSWORD=replace-with-a-strong-password
--
-- After the application starts, reconnect pgAdmin to lshoe_store and verify:
-- SELECT COUNT(*) FROM category;
-- SELECT COUNT(*) FROM product;
-- SELECT COUNT(*) FROM users;
