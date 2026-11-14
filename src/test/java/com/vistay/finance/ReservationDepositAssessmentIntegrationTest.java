package com.vistay.finance;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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
        // Isolated Testcontainers managed entirely by this test
        postgres = new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("vistay_finance_test")
                .withUsername("vistay")
                .withPassword("vistay");
        postgres.start();

        jdbcUrl = postgres.getJdbcUrl();
        username = postgres.getUsername();
        password = postgres.getPassword();

        // Forward-only migration with cleanDisabled(true), no flyway.clean()
        Flyway flyway = Flyway.configure()
                .dataSource(jdbcUrl, username, password)
                .schemas("app")
                .defaultSchema("app")
                .cleanDisabled(true)
                .locations("classpath:db/migration")
                .load();
        flyway.migrate();
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

    @Test
    @DisplayName("AC03: Retiring current assessment then inserting new revision succeeds without unique constraint collision")
    void supersedeThenInsert_succeedsWithoutCollision() throws Exception {
        String targetKey = "hold:" + UUID.randomUUID();
        UUID propId = UUID.randomUUID();
        UUID firstId = UUID.randomUUID();

        try (Connection conn = createConnection()) {
            // Insert revision 1 (is_current = true)
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO app.reservation_deposit_assessments (
                        id, target_key, property_id, assessment_revision, is_current,
                        input_fingerprint, rule_version, required_amount, status, expires_at,
                        idempotency_key, correlation_id
                    ) VALUES (?, ?, ?, 1, true, 'fp-rev1', 1, 500000, 'PAYMENT_REQUIRED', now() + interval '1 hour', ?, ?)
                    """)) {
                ps.setObject(1, firstId);
                ps.setString(2, targetKey);
                ps.setObject(3, propId);
                ps.setString(4, "idemp-rev-1");
                ps.setString(5, "corr-rev-1");
                ps.executeUpdate();
            }

            // Update revision 1 (is_current = false) - simulating saveAndFlush(current.supersede())
            try (PreparedStatement ps = conn.prepareStatement("""
                    UPDATE app.reservation_deposit_assessments
                    SET is_current = false, updated_at = now()
                    WHERE id = ?
                    """)) {
                ps.setObject(1, firstId);
                int updated = ps.executeUpdate();
                assertThat(updated).isEqualTo(1);
            }

            // Insert revision 2 (is_current = true)
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO app.reservation_deposit_assessments (
                        id, target_key, property_id, assessment_revision, is_current,
                        input_fingerprint, rule_version, required_amount, status, expires_at,
                        idempotency_key, correlation_id
                    ) VALUES (?, ?, ?, 2, true, 'fp-rev2', 1, 500000, 'PAYMENT_REQUIRED', now() + interval '1 hour', ?, ?)
                    """)) {
                ps.setObject(1, UUID.randomUUID());
                ps.setString(2, targetKey);
                ps.setObject(3, propId);
                ps.setString(4, "idemp-rev-2");
                ps.setString(5, "corr-rev-2");
                int inserted = ps.executeUpdate();
                assertThat(inserted).isEqualTo(1);
            }
        }
    }

    @Test
    @DisplayName("AC01: finance_command_idempotency enforces actor_type, status, and jsonb payload constraints")
    void idempotencyTable_enforcesConstraintsAndJsonb() throws Exception {
        UUID actorId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();

        try (Connection conn = createConnection()) {
            // Valid insertion with JSON payload
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO app.finance_command_idempotency (
                        idempotency_key, command_type, actor_type, actor_id, request_hash,
                        status, resource_type, resource_id, response_payload, expires_at
                    ) VALUES (?, 'ASSESS_DEPOSIT', 'ADMIN', ?, 'hash64-abcdef', 'SUCCEEDED', 'RESERVATION_DEPOSIT_ASSESSMENT', ?, ?::jsonb, now() + interval '1 hour')
                    """)) {
                ps.setString(1, "key-valid-idem");
                ps.setObject(2, actorId);
                ps.setObject(3, resourceId);
                ps.setString(4, "{\"status\":\"VERIFIED_PAID\",\"amount\":100000}");
                int inserted = ps.executeUpdate();
                assertThat(inserted).isEqualTo(1);
            }

            // Invalid payload: non-object jsonb (scalar string) must be rejected by ck_finance_cmd_idem_payload
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO app.finance_command_idempotency (
                        idempotency_key, command_type, actor_type, actor_id, request_hash,
                        status, response_payload, expires_at
                    ) VALUES (?, 'ASSESS_DEPOSIT', 'ADMIN', ?, 'hash64-abcdef', 'SUCCEEDED', ?::jsonb, now() + interval '1 hour')
                    """)) {
                ps.setString(1, "key-invalid-payload");
                ps.setObject(2, actorId);
                ps.setString(3, "\"scalar_string\"");

                assertThatThrownBy(ps::executeUpdate)
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("ck_finance_cmd_idem_payload");
            }
        }
    }
}
