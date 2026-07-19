package com.example.lshoestore.config;

/**
 * Database changes are managed by Flyway scripts in {@code src/main/resources/db/migration}.
 * This placeholder overwrites the former startup migration runner so schema changes are no
 * longer executed imperatively every time the application starts.
 */
@Deprecated(forRemoval = true)
public final class DataMigrationRunner {
    private DataMigrationRunner() {
    }
}
