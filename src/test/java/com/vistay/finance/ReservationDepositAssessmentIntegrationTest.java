package com.vistay.finance;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.*;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReservationDepositAssessmentIntegrationTest {

    private static PostgreSQLContainer<?> postgres;
    private static String jdbcUrl;
    private static String username;
    private static String password;

    @BeforeAll
    static void setUpAll() {
        if (isReachable("127.0.0.1", 5433)) {
            jdbcUrl = "jdbc:postgresql://127.0.0.1:5433/vistay_finance_test";
            username = "vistay";
            password = "vistay";
        } else {
            postgres = new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("vistay_finance_test")
                    .withUsername("vistay")
                    .withPassword("vistay");
            postgres.start();
            jdbcUrl = postgres.getJdbcUrl();
            username = postgres.getUsername();
            password = postgres.getPassword();
        }

        Flyway flyway = Flyway.configure()
                .dataSource(jdbcUrl, username, password)
                .schemas("app")
                .defaultSchema("app")
                .cleanDisabled(false)
                .locations("classpath:db/migration")
                .load();
        flyway.clean();
        flyway.migrate();
    }

    private static boolean isReachable(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 1000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @AfterAll
    static void tearDownAll() {
        if (postgres != null && postgres.isRunning()) {
            postgres.stop();
        }
    }

    private Connection createConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, username, password);
    }

    @Test
    @DisplayName("AC01 & AC03: V5 migration creates reservation_deposit_assessments and finance_command_idempotency")
    void verifyTablesAndConstraintsCreated() throws Exception {
        try (Connection conn = createConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT table_name FROM information_schema.tables
                     WHERE table_schema = 'app' AND table_name IN ('reservation_deposit_assessments', 'finance_command_idempotency')
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
    @DisplayName("AC03: Partial unique index ux_deposit_assessment_current prevents multiple active current assessments for same target_key")
    void partialUniqueIndex_enforcesSingleActiveCurrentPerTarget() throws Exception {
        String targetKey = "hold:" + UUID.randomUUID();
        UUID propId = UUID.randomUUID();

        try (Connection conn = createConnection()) {
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO app.reservation_deposit_assessments (
                        id, target_key, property_id, assessment_revision, is_current,
                        input_fingerprint, rule_version, required_amount, status, expires_at,
                        idempotency_key, correlation_id
                    ) VALUES (?, ?, ?, 1, true, 'fp-1', 1, 500000, 'PAYMENT_REQUIRED', now() + interval '1 hour', ?, ?)
                    """)) {
                ps.setObject(1, UUID.randomUUID());
                ps.setString(2, targetKey);
                ps.setObject(3, propId);
                ps.setString(4, "idemp-part-1");
                ps.setString(5, "corr-part-1");
                ps.executeUpdate();
            }

            // Inserting a second record with is_current = true for the same targetKey must fail with unique violation
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO app.reservation_deposit_assessments (
                        id, target_key, property_id, assessment_revision, is_current,
                        input_fingerprint, rule_version, required_amount, status, expires_at,
                        idempotency_key, correlation_id
                    ) VALUES (?, ?, ?, 2, true, 'fp-2', 1, 600000, 'PAYMENT_REQUIRED', now() + interval '1 hour', ?, ?)
                    """)) {
                ps.setObject(1, UUID.randomUUID());
                ps.setString(2, targetKey);
                ps.setObject(3, propId);
                ps.setString(4, "idemp-part-2");
                ps.setString(5, "corr-part-2");

                assertThatThrownBy(ps::executeUpdate)
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("ux_deposit_assessment_current");
            }
        }
    }

    @Test
    @DisplayName("AC02: ck_deposit_waiver_approved rejects manual waiver without approver and timestamp")
    void checkConstraint_rejectsUnapprovedManualWaiver() throws Exception {
        String targetKey = "hold:" + UUID.randomUUID();
        UUID propId = UUID.randomUUID();

        try (Connection conn = createConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO app.reservation_deposit_assessments (
                         id, target_key, property_id, assessment_revision, is_current,
                         input_fingerprint, rule_version, required_amount, waiver_mode,
                         status, expires_at, idempotency_key, correlation_id
                     ) VALUES (?, ?, ?, 1, true, 'fp-waive-invalid', 1, 500000, 'MANUAL_WAIVER', 'WAIVED_FINAL', now() + interval '1 hour', ?, ?)
                     """)) {
            ps.setObject(1, UUID.randomUUID());
            ps.setString(2, targetKey);
            ps.setObject(3, propId);
            ps.setString(4, "idemp-waive-invalid");
            ps.setString(5, "corr-waive-invalid");

            assertThatThrownBy(ps::executeUpdate)
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("ck_deposit_waiver_approved");
        }
    }
}
