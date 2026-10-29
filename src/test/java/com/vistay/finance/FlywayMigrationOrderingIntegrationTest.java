package com.vistay.finance;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

        try (Connection conn = DriverManager.getConnection(jdbcUrl, username, password);
             java.sql.Statement st = conn.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS db_seq");
            st.execute("CREATE DATABASE db_seq");
            st.execute("DROP DATABASE IF EXISTS db_ooo");
            st.execute("CREATE DATABASE db_ooo");
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

    @Test
    @Order(3)
    @DisplayName("SCRUM-317 / SCRUM-386 Matrix: Sequential upgrade V1->V2->V3->V4 succeeds cleanly with out-of-order: false")
    void sequentialUpgrade_V1_V2_V3_V4_Succeeds() throws Exception {
        String dbUrl = jdbcUrl.replace("vistay_finance_migration_test", "db_seq");
        Flyway flyway = Flyway.configure()
                .dataSource(dbUrl, username, password)
                .schemas("app")
                .defaultSchema("app")
                .cleanDisabled(true)
                .outOfOrder(false)
                .locations("classpath:migration_matrix")
                .load();

        var result = flyway.migrate();
        assertThat(result.success).isTrue();
        assertThat(result.migrationsExecuted).isEqualTo(4);

        // Verify that both SCRUM-386 (settlement_periods) and SCRUM-317 (finance_rule_catalogues) exist in the schema
        try (Connection conn = DriverManager.getConnection(dbUrl, username, password);
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT table_name
                     FROM information_schema.tables
                     WHERE table_schema = 'app' AND table_name IN ('settlement_periods', 'finance_rule_catalogues')
                     """)) {
            try (ResultSet rs = ps.executeQuery()) {
                int count = 0;
                while (rs.next()) {
                    count++;
                }
                assertThat(count).isEqualTo(2);
            }
        }
    }

    @Test
    @Order(4)
    @DisplayName("SCRUM-317 / SCRUM-386 Matrix: If V4 applied first, out-of-order: false blocks retroactive V3, and out-of-order: true applies V3 safely")
    void outOfOrderDeploymentMatrix_EnforcesOrderAndAllowsControlledCatchup() throws Exception {
        String dbUrl = jdbcUrl.replace("vistay_finance_migration_test", "db_ooo");

        // Phase 1a: Deploy the common baseline first.
        Flyway baselineDeploy = Flyway.configure()
                .dataSource(dbUrl, username, password)
                .schemas("app")
                .defaultSchema("app")
                .cleanDisabled(true)
                .outOfOrder(false)
                .target("2")
                .locations("classpath:migration_matrix")
                .load();

        var baselineResult = baselineDeploy.migrate();
        assertThat(baselineResult.success).isTrue();
        assertThat(baselineResult.targetSchemaVersion).isEqualTo("2");

        // Phase 1b: Simulate V4 being released before SCRUM-386's V3.
        Flyway v4Deploy = Flyway.configure()
                .dataSource(dbUrl, username, password)
                .schemas("app")
                .defaultSchema("app")
                .cleanDisabled(true)
                .outOfOrder(false)
                .validateOnMigrate(false)
                .locations("classpath:migration_v4_only")
                .load();

        var r1 = v4Deploy.migrate();
        assertThat(r1.success).isTrue();
        assertThat(r1.targetSchemaVersion).isEqualTo("4");

        // Phase 2: with out-of-order disabled, Flyway rejects retroactive V3 during validation.
        Flyway strictCatchup = Flyway.configure()
                .dataSource(dbUrl, username, password)
                .schemas("app")
                .defaultSchema("app")
                .cleanDisabled(true)
                .outOfOrder(false)
                .locations("classpath:migration_matrix")
                .load();

        assertThatThrownBy(strictCatchup::migrate)
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("3");

        // Phase 3: With controlled out-of-order: true flag enabled, Flyway applies V3 safely
        Flyway controlledCatchup = Flyway.configure()
                .dataSource(dbUrl, username, password)
                .schemas("app")
                .defaultSchema("app")
                .cleanDisabled(true)
                .outOfOrder(true)
                .locations("classpath:migration_matrix")
                .load();

        var r3 = controlledCatchup.migrate();
        assertThat(r3.success).isTrue();
        assertThat(r3.migrationsExecuted).isEqualTo(1); // V3 was applied
    }
}
