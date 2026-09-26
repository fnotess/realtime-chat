package com.sithija.chat;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A throwaway Postgres for Spring tests, instead of the dev database.
 * @ServiceConnection points spring.datasource.* at the container, and Flyway migrates it on
 * startup. Test classes that import this config and set the same properties share one Spring
 * context, and with it one container.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfig {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        // Same major version as docker-compose.yml, so tests exercise the production engine.
        return new PostgreSQLContainer("postgres:16");
    }
}
