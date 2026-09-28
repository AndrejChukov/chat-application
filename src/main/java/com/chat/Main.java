package com.chat;

import io.vertx.core.Vertx;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        try {
            runDatabaseMigrations();
        } catch (Exception e) {
            log.error("Database migration failed! Shutting down.", e);
            System.exit(1);
        }

        Vertx vertx = Vertx.vertx();
        vertx.deployVerticle(new ChatVerticle())
                .onSuccess(id -> log.info("ChatVerticle successfully deployed: {}", id))
                .onFailure(err -> {
                    log.error("Failed to deploy ChatVerticle", err);
                    vertx.close();
                });
    }

    private static void runDatabaseMigrations() {
        String host = getEnv("DB_HOST", "localhost");
        String port = getEnv("DB_PORT", "5432");
        String dbName = getEnv("DB_NAME", "chat_application");
        String user = getEnv("DB_USER", "postgres");
        String password = getEnv("DB_PASSWORD", "postgres");

        String jdbcUrl = String.format("jdbc:postgresql://%s:%s/%s", host, port, dbName);
        log.info("Executing Flyway migrations against {}", jdbcUrl);

        Flyway flyway = Flyway.configure()
                .dataSource(jdbcUrl, user, password)
                .baselineOnMigrate(true)
                .load();

        flyway.migrate();
        log.info("Flyway migrations executed successfully.");
    }

    private static String getEnv(String key, String defaultValue) {
        String value = System.getenv(key);
        return value != null && !value.isBlank() ? value : defaultValue;
    }
}