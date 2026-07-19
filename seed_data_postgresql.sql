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
	
-- After the application starts, reconnect pgAdmin to lshoe_store and verify:
-- SELECT COUNT(*) FROM category;
-- SELECT COUNT(*) FROM product;
-- SELECT COUNT(*) FROM users;
