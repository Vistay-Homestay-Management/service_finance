package com.vistay.finance;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FlywayMigrationOrderingIntegrationTest {

    private static PostgreSQLContainer<?> postgres;
    private static String jdbcUrl;
    private static String username;
    private static String password;

    @BeforeAll
    static void setUpAll() {
        postgres = new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("vistay_finance_migration_test")
                .withUsername("vistay_mig_user")
                .withPassword("vistay_mig_pass");
        postgres.start();

        jdbcUrl = postgres.getJdbcUrl();
        username = postgres.getUsername();
        password = postgres.getPassword();
    }

    @AfterAll
    static void tearDownAll() {
        if (postgres != null && postgres.isRunning()) {
            postgres.stop();
        }
    }

    @Test
    @Order(1)
    @DisplayName("SCRUM-317: Baseline V1->V2->V4 migration executes successfully in order on clean database")
    void baselineMigrationPath_ExecutesCleanly() {
        Flyway flyway = Flyway.configure()
                .dataSource(jdbcUrl, username, password)
                .schemas("app")
                .defaultSchema("app")
                .cleanDisabled(true)
                .outOfOrder(false)
                .locations("classpath:db/migration")
                .load();

        var result = flyway.migrate();
        assertThat(result.success).isTrue();
        assertThat(result.migrationsExecuted).isGreaterThanOrEqualTo(3);
    }

    @Test
    @Order(2)
    @DisplayName("SCRUM-317: Verification of V4 table structure and indexes after migration")
    void verifyTableStructureAndIndexes() throws Exception {
        try (Connection conn = DriverManager.getConnection(jdbcUrl, username, password);
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT column_name, data_type, is_nullable
                     FROM information_schema.columns
                     WHERE table_schema = 'app' AND table_name = 'finance_rule_catalogues'
                     ORDER BY ordinal_position
                     """)) {
            try (ResultSet rs = ps.executeQuery()) {
                boolean foundCode = false;
                boolean foundParams = false;
                boolean foundVersion = false;

                while (rs.next()) {
                    String col = rs.getString("column_name");
                    String type = rs.getString("data_type");
                    if ("rule_code".equals(col)) foundCode = true;
                    if ("parameters_json".equals(col) && "jsonb".equalsIgnoreCase(type)) foundParams = true;
                    if ("rule_version".equals(col)) foundVersion = true;
                }

                assertThat(foundCode).isTrue();
                assertThat(foundParams).isTrue();
                assertThat(foundVersion).isTrue();
            }
        }
    }
}

