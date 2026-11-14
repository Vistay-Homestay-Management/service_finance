package com.vistay.finance;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CombinedFlywayMigrationIntegrationTest {

    private static PostgreSQLContainer<?> postgres;
    private static String jdbcUrl;
    private static String username;
    private static String password;

    @BeforeAll
    static void setUpAll() {
        postgres = new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("vistay_finance_combined_test")
                .withUsername("vistay_comb_user")
                .withPassword("vistay_comb_pass");
        postgres.start();

        jdbcUrl = postgres.getJdbcUrl();
        username = postgres.getUsername();
        password = postgres.getPassword();

        try (Connection conn = DriverManager.getConnection(jdbcUrl, username, password);
             java.sql.Statement st = conn.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS db_clean_combined");
            st.execute("CREATE DATABASE db_clean_combined");
            st.execute("DROP DATABASE IF EXISTS db_upgrade_combined");
            st.execute("CREATE DATABASE db_upgrade_combined");
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize test databases: " + e.getMessage(), e);
        }
    }

    @AfterAll
    static void tearDownAll() {
        if (postgres != null && postgres.isRunning()) {
            postgres.stop();
        }
    }

    @Test
    @Order(1)
    @DisplayName("Combined clean DB: Sequential V1->V7 executes cleanly on fresh database")
    void cleanDatabaseMigration_V1_to_V7_Succeeds() throws Exception {
        String dbUrl = jdbcUrl.replace("vistay_finance_combined_test", "db_clean_combined");

        Flyway flyway = Flyway.configure()
                .dataSource(dbUrl, username, password)
                .schemas("app")
                .defaultSchema("app")
                .cleanDisabled(true)
                .outOfOrder(false)
                .locations("classpath:combined_migrations")
                .load();

        MigrateResult result = flyway.migrate();
        assertThat(result.success).isTrue();
        assertThat(result.migrationsExecuted).isEqualTo(7);
        assertThat(result.targetSchemaVersion).isEqualTo("7");

        // Verify that all 4 PR tables exist simultaneously in the schema
        try (Connection conn = DriverManager.getConnection(dbUrl, username, password);
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT table_name
                     FROM information_schema.tables
                     WHERE table_schema = 'app' AND table_name IN (
                         'finance_command_idempotency',
                         'reservation_deposit_assessments',
                         'reservation_amendment_assessments',
                         'reservation_cancellation_obligations'
                     )
                     """)) {
            try (ResultSet rs = ps.executeQuery()) {
                int count = 0;
                while (rs.next()) {
                    count++;
                }
                assertThat(count).isEqualTo(4);
            }
        }
    }

    @Test
    @Order(2)
    @DisplayName("Combined upgrade DB: Baseline V1->V4 followed by incremental upgrade V5->V6->V7 succeeds")
    void upgradeDatabaseMigration_V4_to_V7_Succeeds() throws Exception {
        String dbUrl = jdbcUrl.replace("vistay_finance_combined_test", "db_upgrade_combined");

        // Step 1: Deploy baseline up to V4 (simulate existing deployed environment)
        Flyway baselineFlyway = Flyway.configure()
                .dataSource(dbUrl, username, password)
                .schemas("app")
                .defaultSchema("app")
                .cleanDisabled(true)
                .outOfOrder(false)
                .target("4")
                .locations("classpath:combined_migrations")
                .load();

        MigrateResult baselineResult = baselineFlyway.migrate();
        assertThat(baselineResult.success).isTrue();
        assertThat(baselineResult.migrationsExecuted).isEqualTo(4);
        assertThat(baselineResult.targetSchemaVersion).isEqualTo("4");

        // Step 2: Apply incremental upgrade V5 -> V6 -> V7
        Flyway upgradeFlyway = Flyway.configure()
                .dataSource(dbUrl, username, password)
                .schemas("app")
                .defaultSchema("app")
                .cleanDisabled(true)
                .outOfOrder(false)
                .target("7")
                .locations("classpath:combined_migrations")
                .load();

        MigrateResult upgradeResult = upgradeFlyway.migrate();
        assertThat(upgradeResult.success).isTrue();
        assertThat(upgradeResult.migrationsExecuted).isEqualTo(3); // V5, V6, V7
        assertThat(upgradeResult.targetSchemaVersion).isEqualTo("7");

        // Verify tables and partial indexes in the upgraded database
        try (Connection conn = DriverManager.getConnection(dbUrl, username, password)) {
            // Verify all tables present
            try (PreparedStatement ps = conn.prepareStatement("""
                     SELECT count(*) FROM information_schema.tables
                     WHERE table_schema = 'app' AND table_name IN (
                         'finance_command_idempotency',
                         'reservation_deposit_assessments',
                         'reservation_amendment_assessments',
                         'reservation_cancellation_obligations'
                     )
                     """)) {
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertThat(rs.getInt(1)).isEqualTo(4);
                }
            }

            // Verify unique indexes
            try (PreparedStatement ps = conn.prepareStatement("""
                     SELECT indexname FROM pg_indexes
                     WHERE schemaname = 'app' AND indexname IN (
                         'ux_deposit_assessment_current',
                         'ux_amendment_assessment_current'
                     )
                     """)) {
                try (ResultSet rs = ps.executeQuery()) {
                    int idxCount = 0;
                    while (rs.next()) {
                        idxCount++;
                    }
                    assertThat(idxCount).isEqualTo(2);
                }
            }
        }
    }
}
